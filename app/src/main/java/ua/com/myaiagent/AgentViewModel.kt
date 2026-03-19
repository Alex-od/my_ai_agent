package ua.com.myaiagent

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ua.com.myaiagent.data.ChatRepository
import ua.com.myaiagent.data.ConversationMessage
import ua.com.myaiagent.data.OpenAiApi
import ua.com.myaiagent.data.mcp.McpClient
import ua.com.myaiagent.data.mcp.McpTool
import ua.com.myaiagent.data.mcp.toToolDefinition
import ua.com.myaiagent.data.ResponsesRequestWithHistory
import ua.com.myaiagent.data.UsageInfo
import ua.com.myaiagent.data.context.BranchingStrategy
import ua.com.myaiagent.data.context.ContextResult
import ua.com.myaiagent.data.context.ContextStrategy
import ua.com.myaiagent.data.context.SlidingWindowStrategy
import ua.com.myaiagent.data.context.StickyFactsStrategy
import ua.com.myaiagent.data.context.StrategyContext
import ua.com.myaiagent.data.context.StrategyType
import ua.com.myaiagent.data.context.SummaryStrategy
import ua.com.myaiagent.data.local.BranchEntity
import ua.com.myaiagent.data.local.FactEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RequestLog(val content: String, val rawJson: String = "")

data class ScheduledTask(
    val taskId: String,
    val description: String,
    val cronExpression: String,
    val toolName: String,
    val status: String,       // "running" | "stopped"
    val lastRunAt: Long?,
    val runCount: Int,
)

data class TaskResult(
    val runAt: Long,
    val success: Boolean,
    val data: String,
)

enum class MessageOrigin { MAIN, RAG }

data class UiMessage(
    val role: String,
    val content: String,
    val origin: MessageOrigin = MessageOrigin.MAIN,
    val citations: List<RagSearchResult> = emptyList(),
)

data class TokenStats(
    // РїРѕСЃР»РµРґРЅРёР№ Р·Р°РїСЂРѕСЃ
    val lastInput: Int = 0,
    val lastOutput: Int = 0,
    val lastTotal: Int = 0,
    val lastTruncated: Boolean = false,
    // РЅР°РєРѕРїРёС‚РµР»СЊРЅРѕ РїРѕ РІСЃРµРјСѓ РґРёР°Р»РѕРіСѓ
    val totalInput: Int = 0,
    val totalOutput: Int = 0,
    val totalAll: Int = 0,
    val requestCount: Int = 0,
    // СЃС‚СЂР°С‚РµРіРёСЏ (СЃСѓРјРјР°СЂРёР·Р°С†РёСЏ / РёР·РІР»РµС‡РµРЅРёРµ С„Р°РєС‚РѕРІ)
    val strategyInput: Int = 0,
    val strategyOutput: Int = 0,
    val strategyCallCount: Int = 0,
)

enum class McpStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class RagSearchResult(
    val text: String,
    val source: String,
    val chunkId: String,
    val score: Float,
    val chunkSize: Int,
    val section: String,
    val strategy: String,
    val rerankerScore: Float? = null,
)

data class RagSearchStats(
    val retrievedCount: Int,
    val filteredCount: Int,
    val finalCount: Int,
)

sealed class RagIndexingState {
    data object Idle : RagIndexingState()
    data class Indexing(val done: Int, val total: Int, val message: String = "") : RagIndexingState()
    data class Done(val result: String) : RagIndexingState()
    data class Error(val message: String) : RagIndexingState()
}

sealed class UiState {
    data object Idle : UiState()
    data object Loading : UiState()
    data class Success(val text: String) : UiState()
    data class Error(val message: String) : UiState()
}

enum class ModelCategory(val label: String) {
    FAST("Р‘С‹СЃС‚СЂС‹Рµ"),
    MEDIUM("РЎСЂРµРґРЅРёРµ"),
    STRONG("РЎРёР»СЊРЅС‹Рµ"),
}

data class AiModel(
    val id: String,
    val displayName: String,
    val category: ModelCategory,
)

val availableModels = listOf(
    // Р‘С‹СЃС‚СЂС‹Рµ
    AiModel("gpt-4.1-nano", "GPT-4.1 Nano", ModelCategory.FAST),
    AiModel("gpt-5-nano", "GPT-5 Nano", ModelCategory.FAST),
    AiModel("gpt-4.1-mini", "GPT-4.1 Mini", ModelCategory.FAST),
    AiModel("gpt-5-mini", "GPT-5 Mini", ModelCategory.FAST),
    AiModel("o3-mini", "o3-mini", ModelCategory.FAST),
    // РЎСЂРµРґРЅРёРµ
    AiModel("gpt-4o", "GPT-4o", ModelCategory.MEDIUM),
    AiModel("gpt-4.1", "GPT-4.1", ModelCategory.MEDIUM),
    AiModel("gpt-5.2", "GPT-5.2", ModelCategory.MEDIUM),
    AiModel("o4-mini", "o4-mini", ModelCategory.MEDIUM),
    // РЎРёР»СЊРЅС‹Рµ
    AiModel("o3", "o3", ModelCategory.STRONG),
    AiModel("gpt-5.2-pro", "GPT-5.2 Pro", ModelCategory.STRONG),
    AiModel("gpt-5.2-codex", "GPT-5.2 Codex", ModelCategory.STRONG),
)

class AgentViewModel(
    private val api: OpenAiApi,
    private val repository: ChatRepository,
    private val strategies: Map<StrategyType, ContextStrategy>,
    private val mcpClient: McpClient,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state

    private val _selectedModel = MutableStateFlow(availableModels.first { it.id == "gpt-4.1-mini" })
    val selectedModel: StateFlow<AiModel> = _selectedModel

    private val _lastRequestLog = MutableStateFlow<RequestLog?>(null)
    val lastRequestLog: StateFlow<RequestLog?> = _lastRequestLog

    private val _tokenStats = MutableStateFlow(TokenStats())
    val tokenStats: StateFlow<TokenStats> = _tokenStats

    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages

    private val _selectedStrategy = MutableStateFlow(StrategyType.SUMMARY)
    val selectedStrategy: StateFlow<StrategyType> = _selectedStrategy

    private val _contextInfo = MutableStateFlow("")
    val contextInfo: StateFlow<String> = _contextInfo

    private val _facts = MutableStateFlow<List<FactEntity>>(emptyList())
    val facts: StateFlow<List<FactEntity>> = _facts

    private val _branches = MutableStateFlow<List<BranchEntity>>(emptyList())
    val branches: StateFlow<List<BranchEntity>> = _branches

    private val _activeBranchName = MutableStateFlow<String?>(null)
    val activeBranchName: StateFlow<String?> = _activeBranchName

    private val _mcpStatus = MutableStateFlow(McpStatus.DISCONNECTED)
    val mcpStatus: StateFlow<McpStatus> = _mcpStatus
    private val _mcpTools = MutableStateFlow<List<McpTool>>(emptyList())
    val mcpTools: StateFlow<List<McpTool>> = _mcpTools
    private val _mcpServerName = MutableStateFlow("")
    val mcpServerName: StateFlow<String> = _mcpServerName
    val mcpUrl = MutableStateFlow(MCP_URL)

    private val _schedulerTasks = MutableStateFlow<List<ScheduledTask>>(emptyList())
    val schedulerTasks: StateFlow<List<ScheduledTask>> = _schedulerTasks

    private val _schedulerResults = MutableStateFlow<List<TaskResult>>(emptyList())
    val schedulerResults: StateFlow<List<TaskResult>> = _schedulerResults

    private val _selectedSchedulerTaskId = MutableStateFlow<String?>(null)
    val selectedSchedulerTaskId: StateFlow<String?> = _selectedSchedulerTaskId

    private val _ragIndexingState = MutableStateFlow<RagIndexingState>(RagIndexingState.Idle)
    val ragIndexingState: StateFlow<RagIndexingState> = _ragIndexingState

    private val _ragEnabled = MutableStateFlow(true)
    val ragEnabled: StateFlow<Boolean> = _ragEnabled

    private val _ragCompareStats = MutableStateFlow<String?>(null)
    val ragCompareStats: StateFlow<String?> = _ragCompareStats

    private val _selectedRagStrategy = MutableStateFlow("structural")
    val selectedRagStrategy: StateFlow<String> = _selectedRagStrategy

    private val _ragTopKFinal = MutableStateFlow(3)
    val ragTopKFinal: StateFlow<Int> = _ragTopKFinal

    private val _ragTopKInitial = MutableStateFlow(DEFAULT_TOP_K_INITIAL)
    val ragTopKInitial: StateFlow<Int> = _ragTopKInitial

    private val _ragRerankerEnabled = MutableStateFlow(false)
    val ragRerankerEnabled: StateFlow<Boolean> = _ragRerankerEnabled

    private val _ragRerankerModel = MutableStateFlow(RERANKER_MODELS[0])
    val ragRerankerModel: StateFlow<String> = _ragRerankerModel

    private val _ragRerankerThreshold = MutableStateFlow(DEFAULT_RERANKER_THRESHOLD)
    val ragRerankerThreshold: StateFlow<Float> = _ragRerankerThreshold

    private val _lastRagResults = MutableStateFlow<List<RagSearchResult>?>(null)
    val lastRagResults: StateFlow<List<RagSearchResult>?> = _lastRagResults

    private val _ragLastSearchStats = MutableStateFlow<RagSearchStats?>(null)
    val ragLastSearchStats: StateFlow<RagSearchStats?> = _ragLastSearchStats

    private var schedulerPollingJob: kotlinx.coroutines.Job? = null
    private var indexingPollingJob: kotlinx.coroutines.Job? = null

    val systemPromptInput = MutableStateFlow("")
    val temperatureInput = MutableStateFlow("")
    val topPInput = MutableStateFlow("")
    val maxTokensInput = MutableStateFlow("")
    val stopInput = MutableStateFlow("")

    private var activeConversationId: Long? = null

    // в”Ђв”Ђ Branch message store в”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђ
    // Separate in-memory message lists per branch. null key = main branch.
    private val branchMessagesStore = mutableMapOf<Long?, MutableList<UiMessage>>()
    private var currentBranchId: Long? = null

    private val currentStrategy: ContextStrategy
        get() = strategies[_selectedStrategy.value]
            ?: strategies[StrategyType.SLIDING_WINDOW]!!

    init {
        viewModelScope.launch {
            val session = repository.getActiveSession() ?: return@launch
            activeConversationId = session.conversation.id
            val sys = session.messages.firstOrNull { it.role == "system" }
            if (sys != null) systemPromptInput.value = sys.content
            val nonSystem = session.messages.filter { it.role != "system" }
            _messages.value = nonSystem.map { UiMessage(it.role, it.content, MessageOrigin.MAIN) }
        }
        connectMcp()
    }

    fun descriptionFor(type: StrategyType): String = strategies[type]?.description ?: ""

    fun selectModel(model: AiModel) {
        _selectedModel.value = model
    }

    fun selectStrategy(type: StrategyType) {
        _selectedStrategy.value = type
        _contextInfo.value = ""
        // Refresh branches/facts display when switching strategy
        viewModelScope.launch { refreshStrategyData() }
    }

    fun send(
        prompt: String,
        useHistory: Boolean = true,
        origin: MessageOrigin = MessageOrigin.MAIN,
    ) {
        if (prompt.isBlank()) return
        val temperature = temperatureInput.value.toDoubleOrNull()
        val topP = topPInput.value.toDoubleOrNull()
        val maxTokens = maxTokensInput.value.toIntOrNull()
        val model = _selectedModel.value
        val systemPrompt = systemPromptInput.value.trim().takeIf { it.isNotBlank() }

        _state.value = UiState.Loading
        viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val prettyJson = Json { prettyPrint = true }
            var requestJson = ""
            try {
                val conversationId = activeConversationId
                    ?: repository.getOrCreateSession(model.id, systemPrompt).also {
                        activeConversationId = it
                    }

                repository.appendUserMessage(conversationId, prompt)
                val updatedMessages = _messages.value + UiMessage("user", prompt, origin)
                _messages.value = updatedMessages
                val conversationMessages = updatedMessages.filter { it.origin == origin }

                // Use the selected strategy to build context
                val strategyCtx = StrategyContext(conversationId, systemPrompt, model.id)
                val contextResult = currentStrategy.buildContext(conversationMessages, strategyCtx)
                _contextInfo.value = contextResult.info

                // Track strategy API usage if any
                contextResult.strategyUsage?.let { usage ->
                    val prev = _tokenStats.value
                    _tokenStats.value = prev.copy(
                        strategyInput = prev.strategyInput + usage.inputTokens,
                        strategyOutput = prev.strategyOutput + usage.outputTokens,
                        strategyCallCount = prev.strategyCallCount + 1,
                    )
                }

                val apiMessages = contextResult.messages
                // RAG: Р°РІС‚РѕРјР°С‚РёС‡РµСЃРєРё РёС‰РµРј СЂРµР»РµРІР°РЅС‚РЅС‹Рµ С‡Р°РЅРєРё Рё РґРѕР±Р°РІР»СЏРµРј РІ РєРѕРЅС‚РµРєСЃС‚
                var ragFallbackReply: String? = null
                val ragContextInjection = if (_ragEnabled.value
                    && _ragIndexingState.value is RagIndexingState.Done
                    && _mcpStatus.value == McpStatus.CONNECTED) {
                    runCatching {
                        val searchArgs = buildJsonObject {
                            put("query", prompt)
                            put("strategy", _selectedRagStrategy.value)
                            put("top_k_final", _ragTopKFinal.value)
                            put("reranker_enabled", _ragRerankerEnabled.value)
                            put("rerank_threshold", _ragRerankerThreshold.value.toDouble())
                            if (_ragRerankerEnabled.value) {
                                put("top_k_initial", _ragTopKInitial.value)
                                put("reranker_model", _ragRerankerModel.value)
                            }
                        }.toString()
                        val raw = mcpClient.callTool("search_documents", searchArgs)
                        val responseObj = Json.parseToJsonElement(raw).jsonObject
                        val results = responseObj["results"]?.jsonArray

                        // РЎРѕС…СЂР°РЅСЏРµРј СЃС‚Р°С‚РёСЃС‚РёРєСѓ РїРѕРёСЃРєР°
                        _ragLastSearchStats.value = RagSearchStats(
                            retrievedCount = responseObj["retrieved_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: (results?.size ?: 0),
                            filteredCount  = responseObj["filtered_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                            finalCount     = responseObj["final_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: (results?.size ?: 0),
                        )

                        if (results.isNullOrEmpty()) {
                            Log.w("RAG", "РџРѕРёСЃРє РЅРµ РІРµСЂРЅСѓР» СЂРµР·СѓР»СЊС‚Р°С‚РѕРІ РґР»СЏ Р·Р°РїСЂРѕСЃР°: $prompt")
                            _lastRagResults.value = emptyList()
                            ragFallbackReply = "не знаю. Уточните запрос."
                        }
                        if (!results.isNullOrEmpty()) {
                            _lastRagResults.value = results.map { el ->
                                val o = el.jsonObject
                                RagSearchResult(
                                    text = o["text"]?.jsonPrimitive?.content ?: "",
                                    source = o["source"]?.jsonPrimitive?.content ?: "",
                                    chunkId = o["chunk_id"]?.jsonPrimitive?.content ?: "",
                                    score = o["score"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f,
                                    chunkSize = o["chunk_size"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                                    section = o["section"]?.jsonPrimitive?.content ?: "",
                                    strategy = _selectedRagStrategy.value,
                                    rerankerScore = o["reranker_score"]?.jsonPrimitive?.content?.toFloatOrNull(),
                                )
                            }
                            val chunks = results.joinToString("\n\n---\n\n") { el ->
                                val o = el.jsonObject
                                val src  = o["source"]?.jsonPrimitive?.content ?: ""
                                val text = o["text"]?.jsonPrimitive?.content ?: ""
                                "[$src]\n$text"
                            }
                            Log.d("RAG", "РќР°Р№РґРµРЅРѕ ${results.size} С‡Р°РЅРєРѕРІ РґР»СЏ Р·Р°РїСЂРѕСЃР° (СЃС‚СЂР°С‚РµРіРёСЏ: ${_selectedRagStrategy.value}, СЂРµСЂР°РЅРєРµСЂ: ${_ragRerankerEnabled.value})")
                            "\n\n=== RAG CONTEXT ===\n$chunks\n==="
                        } else null
                    }.getOrNull()
                } else null

                if (ragFallbackReply != null) {
                    val apiResult = ua.com.myaiagent.data.ApiResult(ragFallbackReply, null)
                    val duration = System.currentTimeMillis() - startTime
                    Log.d("RAG", "Response: ${apiResult.text}")
                    repository.appendAssistantMessage(conversationId, apiResult.text)
                    _messages.value = _messages.value + UiMessage("assistant", apiResult.text, origin)
                    _state.value = UiState.Idle
                    refreshStrategyData()
                    _lastRequestLog.value = buildLog(
                        timestamp, model, prompt, systemPrompt,
                        temperature, topP, null, maxTokens, duration, "Success", apiResult.text, null,
                        rawJson = "// RAG fallback\n${apiResult.text}",
                    )
                    return@launch
                }

                val effectiveSystemPrompt = if (ragContextInjection != null) {
                    (systemPrompt ?: "") + ragContextInjection
                } else systemPrompt

                // RAG РёР»Рё noHistory СЂРµР¶РёРј: С‚РѕР»СЊРєРѕ С‚РµРєСѓС‰РёР№ РІРѕРїСЂРѕСЃ, Р±РµР· РёСЃС‚РѕСЂРёРё РґРёР°Р»РѕРіР°
                val effectiveMessages = if (!useHistory || ragContextInjection != null) {
                    listOf(ua.com.myaiagent.data.ConversationMessage(role = "user", content = prompt))
                } else {
                    apiMessages
                }
                Log.d("RAG", "effectiveSystemPrompt (РїРµСЂРІС‹Рµ 500 СЃРёРјРІРѕР»РѕРІ): ${effectiveSystemPrompt?.take(500)}")

                requestJson = try {
                    prettyJson.encodeToString(ResponsesRequestWithHistory(
                        model = model.id,
                        input = effectiveMessages,
                        instructions = effectiveSystemPrompt?.takeIf { it.isNotBlank() },
                        maxOutputTokens = maxTokens,
                        temperature = temperature,
                        topP = topP,
                    ))
                } catch (se: Exception) {
                    "Serialization error: ${se.message}"
                }

                // Р•СЃР»Рё Р±С‹Р» RAG-РёРЅР¶РµРєС‚ вЂ” РЅРµ РёСЃРїРѕР»СЊР·СѓРµРј agentic loop:
                // С‡Р°РЅРєРё СѓР¶Рµ РІ system prompt, LLM РґРѕР»Р¶РµРЅ РѕС‚РІРµС‡Р°С‚СЊ С‚РѕР»СЊРєРѕ РЅР° РёС… РѕСЃРЅРѕРІРµ.
                val isMcpActive = ragContextInjection == null
                    && _mcpStatus.value == McpStatus.CONNECTED
                    && _mcpTools.value.isNotEmpty()
                val apiResult = if (isMcpActive) {
                    val mcpToolDefs = _mcpTools.value.map { it.toToolDefinition() }
                    val inputItems = buildJsonArray {
                        apiMessages.forEach { msg ->
                            add(buildJsonObject {
                                put("role", msg.role)
                                put("content", msg.content)
                            })
                        }
                    }
                    var currentInput = inputItems
                    var finalText = ""
                    var finalUsage: UsageInfo? = null
                    var iterations = 0
                    while (iterations < 10) {
                        iterations++
                        val r = api.askWithTools(
                            inputItems = currentInput,
                            model = model.id,
                            systemPrompt = effectiveSystemPrompt,
                            tools = mcpToolDefs,
                        )
                        finalUsage = r.usage
                        if (r.toolCalls.isNotEmpty()) {
                            val nextInput = buildJsonArray {
                                currentInput.forEach { add(it) }
                                r.toolCalls.forEach { tc ->
                                    add(buildJsonObject {
                                        put("type", "function_call")
                                        put("id", tc.id)
                                        put("call_id", tc.callId)
                                        put("name", tc.name)
                                        put("arguments", tc.arguments)
                                    })
                                }
                                r.toolCalls.forEach { tc ->
                                    val result = runCatching {
                                        mcpClient.callTool(tc.name, tc.arguments)
                                    }.getOrElse { e -> "Error: ${e.message}" }
                                    Log.d("AgentViewModel", "MCP tool ${tc.name} в†’ $result")
                                    add(buildJsonObject {
                                        put("type", "function_call_output")
                                        put("call_id", tc.callId)
                                        put("output", result)
                                    })
                                }
                            }
                            currentInput = nextInput
                        } else {
                            finalText = r.text
                            break
                        }
                    }
                    ua.com.myaiagent.data.ApiResult(finalText, finalUsage)
                } else {
                    api.askWithHistory(
                        messages = effectiveMessages,
                        model = model.id,
                        systemPrompt = effectiveSystemPrompt,
                        maxTokens = maxTokens,
                        temperature = temperature,
                        topP = topP,
                    )
                }

                val duration = System.currentTimeMillis() - startTime
                Log.d("AgentViewModel", "Response: ${apiResult.text}")
                Log.d("AgentViewModel", "Usage: ${apiResult.usage}")
                val shouldAttachRagCitations = origin == MessageOrigin.RAG &&
                    !apiResult.text.contains("РЅРµ Р·РЅР°СЋ", ignoreCase = true) &&
                    !apiResult.text.contains("СѓС‚РѕС‡РЅРёС‚Рµ", ignoreCase = true)
                val ragCitations: List<RagSearchResult> = _lastRagResults.value ?: emptyList()

                val usage = apiResult.usage
                if (usage != null) {
                    val prev = _tokenStats.value
                    _tokenStats.value = prev.copy(
                        lastInput = usage.inputTokens,
                        lastOutput = usage.outputTokens,
                        lastTotal = usage.totalTokens,
                        lastTruncated = apiResult.truncated,
                        totalInput = prev.totalInput + usage.inputTokens,
                        totalOutput = prev.totalOutput + usage.outputTokens,
                        totalAll = prev.totalAll + usage.totalTokens,
                        requestCount = prev.requestCount + 1,
                    )
                }

                val responseJson = try {
                    prettyJson.encodeToString(buildJsonObject {
                        put("text", apiResult.text)
                        put("truncated", apiResult.truncated)
                        apiResult.usage?.let { u ->
                            putJsonObject("usage") {
                                put("input_tokens", u.inputTokens)
                                put("output_tokens", u.outputTokens)
                                put("total_tokens", u.totalTokens)
                            }
                        }
                    })
                } catch (se: Exception) {
                    "Serialization error: ${se.message}"
                }
                val rawJson = "// Request\n$requestJson\n\n// Response\n$responseJson"

                repository.appendAssistantMessage(conversationId, apiResult.text)
                _messages.value = _messages.value + UiMessage(
                    role = "assistant",
                    content = apiResult.text,
                    origin = origin,
                    citations = if (shouldAttachRagCitations) ragCitations else emptyList(),
                )
                _state.value = UiState.Idle

                // Refresh facts/branches after response
                refreshStrategyData()

                val status = if (apiResult.truncated) "Truncated (max_output_tokens)" else "Success"
                _lastRequestLog.value = buildLog(
                    timestamp, model, prompt, systemPrompt,
                    temperature, topP, null, maxTokens, duration, status, apiResult.text, usage,
                    rawJson = rawJson,
                )
            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - startTime
                Log.e("AgentViewModel", "Error: ${e.message}", e)
                _state.value = UiState.Error(e.message ?: "Unknown error")
                val rawJson = if (requestJson.isNotEmpty())
                    "// Request\n$requestJson\n\n// Error: ${e.message}"
                else
                    "// Error: ${e.message}"
                _lastRequestLog.value = buildLog(
                    timestamp, model, prompt, systemPrompt,
                    temperature, topP, null, maxTokens, duration, "Error", e.message ?: "Unknown error",
                    rawJson = rawJson,
                )
            }
        }
    }

    // в”Ђв”Ђ Branch operations в”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђ

    fun createBranch(name: String) {
        val conversationId = activeConversationId ?: return
        val branchStrategy = strategies[StrategyType.BRANCHING] as? BranchingStrategy ?: return
        viewModelScope.launch {
            // Save current branch messages before forking
            branchMessagesStore[currentBranchId] = _messages.value.toMutableList()
            // Create branch record in DB (stores snapshot)
            val branch = branchStrategy.createBranch(conversationId, name, _messages.value)
            // New branch starts from same fork point (copy)
            branchMessagesStore[branch.id] = _messages.value.toMutableList()
            currentBranchId = branch.id
            _activeBranchName.value = name
            refreshStrategyData()
        }
    }

    fun switchBranch(branchId: Long?) {
        val branchStrategy = strategies[StrategyType.BRANCHING] as? BranchingStrategy ?: return
        // Save current branch before leaving
        branchMessagesStore[currentBranchId] = _messages.value.toMutableList()
        // Restore target branch messages
        _messages.value = branchMessagesStore[branchId] ?: emptyList()
        currentBranchId = branchId
        branchStrategy.switchBranch(branchId)
        _activeBranchName.value = if (branchId != null) {
            _branches.value.find { it.id == branchId }?.name
        } else null
    }

    // в”Ђв”Ђ MCP в”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђ

    companion object {
        const val MCP_URL = "http://127.0.0.1:8083"

        val RERANKER_MODELS = listOf(
            "cross-encoder/ms-marco-MiniLM-L-6-v2",
            "cross-encoder/ms-marco-MiniLM-L-12-v2",
            "BAAI/bge-reranker-base",
        )
        const val DEFAULT_RERANKER_THRESHOLD = 0.3f
        const val DEFAULT_TOP_K_INITIAL = 10
    }

    fun setRagEnabled(enabled: Boolean) {
        _ragEnabled.value = enabled
    }

    fun disconnectMcp() {
        _mcpStatus.value = McpStatus.DISCONNECTED
        _mcpTools.value = emptyList()
        _mcpServerName.value = ""
        schedulerPollingJob?.cancel()
    }

    fun connectMcp(url: String = mcpUrl.value) {
        if (_mcpStatus.value == McpStatus.CONNECTING) return
        mcpUrl.value = url
        _mcpStatus.value = McpStatus.CONNECTING
        viewModelScope.launch {
            runCatching {
                val name = mcpClient.connect(url)
                _mcpServerName.value = name
                _mcpTools.value = mcpClient.listTools()
                _mcpStatus.value = McpStatus.CONNECTED
                startSchedulerPolling()
                checkExistingRagIndex()
            }.onFailure { e ->
                Log.e("AgentViewModel", "MCP error: ${e.message}", e)
                _mcpTools.value = emptyList()
                _mcpServerName.value = ""
                _mcpStatus.value = McpStatus.ERROR
            }
        }
    }

    fun refreshSchedulerTasks() {
        viewModelScope.launch {
            runCatching {
                val raw = mcpClient.callTool("list_tasks", "{}")
                _schedulerTasks.value = parseSchedulerTasks(raw)
            }.onFailure { e ->
                Log.e("AgentViewModel", "refreshSchedulerTasks: ${e.message}", e)
            }
        }
    }

    fun selectSchedulerTask(taskId: String) {
        if (_selectedSchedulerTaskId.value == taskId) {
            _selectedSchedulerTaskId.value = null
            _schedulerResults.value = emptyList()
            return
        }
        _selectedSchedulerTaskId.value = taskId
        viewModelScope.launch {
            runCatching {
                val raw = mcpClient.callTool("get_task_results", """{"taskId":"$taskId","limit":20}""")
                _schedulerResults.value = parseTaskResults(raw)
            }.onFailure { e ->
                Log.e("AgentViewModel", "selectSchedulerTask: ${e.message}", e)
            }
        }
    }

    fun stopSchedulerTask(taskId: String) {
        viewModelScope.launch {
            runCatching { mcpClient.callTool("stop_task", """{"taskId":"$taskId"}""") }
                .onFailure { e -> Log.e("AgentViewModel", "stopSchedulerTask: ${e.message}", e) }
            refreshSchedulerTasks()
        }
    }

    fun clearAllSchedulerTasks() {
        val ids = _schedulerTasks.value.map { it.taskId }
        _schedulerTasks.value = emptyList()
        _schedulerResults.value = emptyList()
        _selectedSchedulerTaskId.value = null
        viewModelScope.launch {
            ids.forEach { taskId ->
                runCatching { mcpClient.callTool("delete_task", """{"taskId":"$taskId"}""") }
            }
        }
    }

    fun deleteSchedulerTask(taskId: String) {
        // РЈР±РёСЂР°РµРј РєР°СЂС‚РѕС‡РєСѓ СЃСЂР°Р·Сѓ, РЅРµ Р¶РґС‘Рј СЃРµСЂРІРµСЂ
        _schedulerTasks.value = _schedulerTasks.value.filter { it.taskId != taskId }
        if (_selectedSchedulerTaskId.value == taskId) {
            _selectedSchedulerTaskId.value = null
            _schedulerResults.value = emptyList()
        }
        viewModelScope.launch {
            runCatching { mcpClient.callTool("delete_task", """{"taskId":"$taskId"}""") }
                .onFailure { e -> Log.e("AgentViewModel", "deleteSchedulerTask: ${e.message}", e) }
        }
    }

    fun scheduleTask(taskId: String, description: String, cronExpression: String, toolName: String, toolArgs: String) {
        viewModelScope.launch {
            val finalToolArgs = translateCityIfNeeded(toolArgs)
            val finalTaskId = if (taskId == autoTaskId(toolName, extractCity(toolArgs)))
                autoTaskId(toolName, extractCity(finalToolArgs))
            else taskId
            val json = buildString {
                append("""{"taskId":${Json.encodeToString(finalTaskId)}""")
                append(""","description":${Json.encodeToString(description)}""")
                append(""","cronExpression":${Json.encodeToString(cronExpression)}""")
                append(""","toolName":${Json.encodeToString(toolName)}""")
                append(""","toolArgs":$finalToolArgs}""")
            }
            runCatching { mcpClient.callTool("schedule_task", json) }
                .onFailure { e -> Log.e("AgentViewModel", "scheduleTask: ${e.message}", e) }
            refreshSchedulerTasks()
        }
    }

    private suspend fun checkExistingRagIndex() {
        runCatching { mcpClient.callTool("get_index_stats", "{}") }
            .onSuccess { raw ->
                val json = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
                val structChunks = json["structural"]?.jsonObject?.get("chunks")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val fixedChunks  = json["fixed"]?.jsonObject?.get("chunks")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val total = structChunks + fixedChunks
                if (total > 0) {
                    Log.d("RAG", "Found existing index: structural=$structChunks, fixed=$fixedChunks")
                    _ragIndexingState.value = RagIndexingState.Done("Готово: $structChunks structural + $fixedChunks fixed")
                } else {
                    // РРЅРґРµРєСЃ РїСѓСЃС‚ вЂ” РІРѕР·РјРѕР¶РЅРѕ СЃРµСЂРІРµСЂ РµС‰С‘ РёРЅРґРµРєСЃРёСЂСѓРµС‚ (Р°РІС‚Рѕ-СЃС‚Р°СЂС‚)
                    runCatching { mcpClient.callTool("get_indexing_status", "{}") }
                        .onSuccess { statusRaw ->
                            val s = runCatching { Json.parseToJsonElement(statusRaw).jsonObject }.getOrNull() ?: return@onSuccess
                            val state = s["state"]?.jsonPrimitive?.content ?: "idle"
                            if (state != "idle" && state != "done" && state != "error") {
                                Log.d("RAG", "РЎРµСЂРІРµСЂ РёРЅРґРµРєСЃРёСЂСѓРµС‚ ($state) вЂ” Р·Р°РїСѓСЃРєР°РµРј polling")
                                _ragIndexingState.value = RagIndexingState.Indexing(0, 0, "РђРІС‚Рѕ-РёРЅРґРµРєСЃР°С†РёСЏвЂ¦")
                                indexingPollingJob?.cancel()
                                indexingPollingJob = viewModelScope.launch { pollIndexingStatus() }
                            }
                        }
                }
            }
    }

    fun resetRagIndexingState() {
        _ragIndexingState.value = RagIndexingState.Idle
        _ragCompareStats.value = null
    }

    fun setRagStrategy(strategy: String) {
        _selectedRagStrategy.value = strategy
    }

    fun setTopKFinal(k: Int) {
        _ragTopKFinal.value = k.coerceIn(1, 10)
    }

    fun setTopKInitial(k: Int) {
        _ragTopKInitial.value = k.coerceIn(1, 20)
    }

    fun setRerankerEnabled(enabled: Boolean) {
        _ragRerankerEnabled.value = enabled
    }

    fun setRerankerModel(model: String) {
        if (model in RERANKER_MODELS) _ragRerankerModel.value = model
    }

    fun setRerankerThreshold(threshold: Float) {
        _ragRerankerThreshold.value = threshold.coerceIn(0f, 1f)
    }

    fun loadRagCompareStats() {
        viewModelScope.launch {
            runCatching { mcpClient.callTool("compare_strategies", "{}") }
                .onSuccess { _ragCompareStats.value = it }
                .onFailure { _ragCompareStats.value = null }
        }
    }

    fun indexDocumentsFromPath(path: String) {
        if (path.isBlank()) return
        indexingPollingJob?.cancel()
        indexingPollingJob = viewModelScope.launch {
            _ragIndexingState.value = RagIndexingState.Indexing(0, 0, "Р—Р°РїСЂРѕСЃ Рє СЃРµСЂРІРµСЂСѓвЂ¦")
            Log.d("RAG", "РРЅРґРµРєСЃРёСЂСѓРµРј РїР°РїРєСѓ РЅР° СЃРµСЂРІРµСЂРµ: $path")
            runCatching {
                val args = buildJsonObject { put("folder_path", path) }.toString()
                mcpClient.callTool("index_documents", args)
            }.onSuccess { response ->
                Log.d("RAG", "РЎРµСЂРІРµСЂ РѕС‚РІРµС‚РёР»: $response")
                pollIndexingStatus()
            }.onFailure { e ->
                Log.e("RAG", "РћС€РёР±РєР° РёРЅРґРµРєСЃР°С†РёРё: ${e.message}")
                _ragIndexingState.value = RagIndexingState.Error(e.message ?: "РћС€РёР±РєР°")
            }
        }
    }

    private suspend fun pollIndexingStatus() {
        Log.d("RAG", "РќР°С‡РёРЅР°РµРј polling СЃС‚Р°С‚СѓСЃР°")
        var networkErrors = 0
        repeat(120) { // РјР°РєСЃРёРјСѓРј 120 РїРѕРїС‹С‚РѕРє (6 РјРёРЅСѓС‚)
            delay(3_000)
            runCatching { mcpClient.callTool("get_indexing_status", "{}") }
                .onSuccess { raw ->
                    networkErrors = 0
                    Log.d("RAG", "РЎС‚Р°С‚СѓСЃ: $raw")
                    val json = runCatching {
                        kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject
                    }.getOrNull() ?: return@onSuccess

                    val state    = json["state"]?.jsonPrimitive?.content ?: "unknown"
                    val progress = json["progress"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    val total    = json["total"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    val message  = json["message"]?.jsonPrimitive?.content ?: ""

                    when (state) {
                        "done"  -> {
                            _ragIndexingState.value = RagIndexingState.Done(message)
                            Log.d("RAG", "РРЅРґРµРєСЃР°С†РёСЏ Р·Р°РІРµСЂС€РµРЅР°: $message")
                            return
                        }
                        "error" -> {
                            _ragIndexingState.value = RagIndexingState.Error(message)
                            Log.e("RAG", "РћС€РёР±РєР° РёРЅРґРµРєСЃР°С†РёРё: $message")
                            return
                        }
                        else -> _ragIndexingState.value = RagIndexingState.Indexing(progress, total, message)
                    }
                }
                .onFailure { e ->
                    networkErrors++
                    Log.e("RAG", "Polling РѕС€РёР±РєР° ($networkErrors/3): ${e.message}")
                    if (networkErrors >= 3) {
                        _ragIndexingState.value = RagIndexingState.Error("РЎРµСЂРІРµСЂ РЅРµРґРѕСЃС‚СѓРїРµРЅ")
                        return
                    }
                }
        }
        _ragIndexingState.value = RagIndexingState.Error("РџСЂРµРІС‹С€РµРЅРѕ РІСЂРµРјСЏ РѕР¶РёРґР°РЅРёСЏ")
    }

    private suspend fun translateCityIfNeeded(toolArgs: String): String {
        val city = extractCity(toolArgs)
        if (city.isBlank() || !city.any { it in '\u0400'..'\u04FF' }) return toolArgs
        return runCatching {
            val result = api.askWithHistory(
                messages = listOf(ua.com.myaiagent.data.ConversationMessage(
                    role = "user",
                    content = "Translate this city name to English. Reply with ONLY the English city name, nothing else: $city",
                )),
                model = "gpt-4.1-nano",
                systemPrompt = null,
                maxTokens = 20,
                temperature = 0.0,
                topP = null,
            )
            val translated = result.text.trim()
            Log.d("AgentViewModel", "City translated: $city в†’ $translated")
            toolArgs.replace("\"$city\"", "\"$translated\"")
        }.getOrElse { toolArgs }
    }

    private fun extractCity(toolArgs: String): String =
        Regex(""""city"\s*:\s*"([^"]+)"""").find(toolArgs)?.groupValues?.get(1) ?: ""

    private fun autoTaskId(toolName: String, city: String): String {
        val prefix = if (toolName == "get_forecast") "forecast" else "weather"
        val slug = city.trim().lowercase().replace(" ", "_").ifEmpty { "city" }
        return "${prefix}_${slug}"
    }

    private fun startSchedulerPolling() {
        schedulerPollingJob?.cancel()
        schedulerPollingJob = viewModelScope.launch {
            while (true) {
                refreshSchedulerTasks()
                kotlinx.coroutines.delay(15_000L)
            }
        }
    }

    private fun parseSchedulerTasks(raw: String): List<ScheduledTask> {
        return try {
            Json.parseToJsonElement(raw).jsonArray.map { el ->
                val o = el.jsonObject
                ScheduledTask(
                    taskId = o["taskId"]?.jsonPrimitive?.content ?: "",
                    description = o["description"]?.jsonPrimitive?.content ?: "",
                    cronExpression = o["cronExpression"]?.jsonPrimitive?.content ?: "",
                    toolName = o["toolName"]?.jsonPrimitive?.content ?: "",
                    status = o["status"]?.jsonPrimitive?.content ?: "stopped",
                    lastRunAt = o["lastRunAt"]?.jsonPrimitive?.longOrNull,
                    runCount = o["runCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                )
            }
        } catch (e: Exception) {
            Log.e("AgentViewModel", "parseSchedulerTasks: ${e.message}", e)
            emptyList()
        }
    }

    private fun parseTaskResults(raw: String): List<TaskResult> {
        return try {
            val root = Json.parseToJsonElement(raw).jsonObject
            root["results"]?.jsonArray?.map { el ->
                val o = el.jsonObject
                TaskResult(
                    runAt = o["runAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                    success = o["success"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    data = o["data"]?.toString() ?: "",
                )
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e("AgentViewModel", "parseTaskResults: ${e.message}", e)
            emptyList()
        }
    }

    // в”Ђв”Ђ Lifecycle в”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђ

    override fun onCleared() {
        super.onCleared()
        schedulerPollingJob?.cancel()
    }

    fun startNewChat() {
        viewModelScope.launch {
            repository.startNewChat()
            activeConversationId = null
            _messages.value = emptyList()
            _contextInfo.value = ""
            _facts.value = emptyList()
            _branches.value = emptyList()
            _activeBranchName.value = null
            branchMessagesStore.clear()
            currentBranchId = null
            systemPromptInput.value = ""
            _tokenStats.value = TokenStats()
            _state.value = UiState.Idle
            _lastRequestLog.value = null
            _lastRagResults.value = null
            _ragLastSearchStats.value = null
            // Reset strategy state
            (strategies[StrategyType.SUMMARY] as? SummaryStrategy)?.resetCompression()
            (strategies[StrategyType.STICKY_FACTS] as? StickyFactsStrategy)?.resetExtraction()
            (strategies[StrategyType.BRANCHING] as? BranchingStrategy)?.resetBranch()
        }
    }

    private suspend fun refreshStrategyData() {
        val conversationId = activeConversationId ?: return
        try {
            val dao = repository.dao
            _facts.value = dao.getFactsForConversation(conversationId)
            _branches.value = dao.getBranchesForConversation(conversationId)
        } catch (e: Exception) {
            Log.e("AgentViewModel", "Failed to refresh strategy data: ${e.message}", e)
        }
    }

    private fun buildLog(
        timestamp: String,
        model: AiModel,
        prompt: String,
        systemPrompt: String?,
        temperature: Double?,
        topP: Double?,
        stop: List<String>?,
        maxTokens: Int?,
        durationMs: Long,
        status: String,
        response: String,
        usage: UsageInfo? = null,
        rawJson: String = "",
    ) = RequestLog(content = buildString {
        appendLine("=== Request Log ===")
        appendLine("Time:       $timestamp")
        appendLine("Model:      ${model.displayName} (${model.id})")
        appendLine("Strategy:   ${_selectedStrategy.value.label}")
        appendLine("Duration:   ${durationMs}ms")
        appendLine()
        appendLine("--- Parameters ---")
        appendLine("Temperature: ${temperature ?: "default"}")
        appendLine("Top P:       ${topP ?: "default"}")
        appendLine("Max Tokens:  ${maxTokens ?: "default"}")
        appendLine("Stop:        ${stop?.joinToString() ?: "none"}")
        appendLine()
        if (usage != null) {
            appendLine("--- Token Usage ---")
            appendLine("Input tokens:  ${usage.inputTokens}")
            appendLine("Output tokens: ${usage.outputTokens}")
            appendLine("Total tokens:  ${usage.totalTokens}")
            appendLine()
        }
        appendLine("--- Context Info ---")
        appendLine(_contextInfo.value)
        appendLine()
        appendLine("--- Messages ---")
        if (systemPrompt != null) appendLine("System: $systemPrompt")
        appendLine("User: $prompt")
        appendLine()
        appendLine("--- Response ($status) ---")
        append(response)
    }, rawJson = rawJson)
}

