package ua.com.myaiagent

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RequestLog(val content: String, val rawJson: String = "")

data class UiMessage(val role: String, val content: String)

data class TokenStats(
    // последний запрос
    val lastInput: Int = 0,
    val lastOutput: Int = 0,
    val lastTotal: Int = 0,
    val lastTruncated: Boolean = false,
    // накопительно по всему диалогу
    val totalInput: Int = 0,
    val totalOutput: Int = 0,
    val totalAll: Int = 0,
    val requestCount: Int = 0,
    // стратегия (суммаризация / извлечение фактов)
    val strategyInput: Int = 0,
    val strategyOutput: Int = 0,
    val strategyCallCount: Int = 0,
)

enum class McpStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

sealed class UiState {
    data object Idle : UiState()
    data object Loading : UiState()
    data class Success(val text: String) : UiState()
    data class Error(val message: String) : UiState()
}

enum class ModelCategory(val label: String) {
    FAST("Быстрые"),
    MEDIUM("Средние"),
    STRONG("Сильные"),
}

data class AiModel(
    val id: String,
    val displayName: String,
    val category: ModelCategory,
)

val availableModels = listOf(
    // Быстрые
    AiModel("gpt-4.1-nano", "GPT-4.1 Nano", ModelCategory.FAST),
    AiModel("gpt-5-nano", "GPT-5 Nano", ModelCategory.FAST),
    AiModel("gpt-4.1-mini", "GPT-4.1 Mini", ModelCategory.FAST),
    AiModel("gpt-5-mini", "GPT-5 Mini", ModelCategory.FAST),
    AiModel("o3-mini", "o3-mini", ModelCategory.FAST),
    // Средние
    AiModel("gpt-4o", "GPT-4o", ModelCategory.MEDIUM),
    AiModel("gpt-4.1", "GPT-4.1", ModelCategory.MEDIUM),
    AiModel("gpt-5.2", "GPT-5.2", ModelCategory.MEDIUM),
    AiModel("o4-mini", "o4-mini", ModelCategory.MEDIUM),
    // Сильные
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

    val mcpUrl = MutableStateFlow("http://192.168.0.12:8080")
    private val _mcpStatus = MutableStateFlow(McpStatus.DISCONNECTED)
    val mcpStatus: StateFlow<McpStatus> = _mcpStatus
    private val _mcpTools = MutableStateFlow<List<McpTool>>(emptyList())
    val mcpTools: StateFlow<List<McpTool>> = _mcpTools
    private val _mcpServerName = MutableStateFlow("")
    val mcpServerName: StateFlow<String> = _mcpServerName

    val systemPromptInput = MutableStateFlow("")
    val temperatureInput = MutableStateFlow("")
    val topPInput = MutableStateFlow("")
    val maxTokensInput = MutableStateFlow("")
    val stopInput = MutableStateFlow("")

    private var activeConversationId: Long? = null

    // ── Branch message store ──────────────────────────────────────────────────
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
            _messages.value = nonSystem.map { UiMessage(it.role, it.content) }
        }
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

    fun send(prompt: String) {
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
                val updatedMessages = _messages.value + UiMessage("user", prompt)
                _messages.value = updatedMessages

                // Use the selected strategy to build context
                val strategyCtx = StrategyContext(conversationId, systemPrompt, model.id)
                val contextResult = currentStrategy.buildContext(updatedMessages, strategyCtx)
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
                requestJson = try {
                    prettyJson.encodeToString(ResponsesRequestWithHistory(
                        model = model.id,
                        input = apiMessages,
                        instructions = systemPrompt?.takeIf { it.isNotBlank() },
                        maxOutputTokens = maxTokens,
                        temperature = temperature,
                        topP = topP,
                    ))
                } catch (se: Exception) {
                    "Serialization error: ${se.message}"
                }
                val isMcpActive = _mcpStatus.value == McpStatus.CONNECTED && _mcpTools.value.isNotEmpty()
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
                            systemPrompt = systemPrompt,
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
                                    Log.d("AgentViewModel", "MCP tool ${tc.name} → $result")
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
                        messages = apiMessages,
                        model = model.id,
                        systemPrompt = systemPrompt,
                        maxTokens = maxTokens,
                        temperature = temperature,
                        topP = topP,
                    )
                }

                val duration = System.currentTimeMillis() - startTime
                Log.d("AgentViewModel", "Response: ${apiResult.text}")
                Log.d("AgentViewModel", "Usage: ${apiResult.usage}")

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
                _messages.value = _messages.value + UiMessage("assistant", apiResult.text)
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

    // ── Branch operations ────────────────────────────────────────────────────

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

    // ── MCP ──────────────────────────────────────────────────────────────────

    fun connectMcp(url: String) {
        if (_mcpStatus.value == McpStatus.CONNECTING) return
        _mcpStatus.value = McpStatus.CONNECTING
        mcpUrl.value = url
        viewModelScope.launch {
            runCatching {
                val name = mcpClient.connect(url)
                _mcpServerName.value = name
                _mcpTools.value = mcpClient.listTools()
                _mcpStatus.value = McpStatus.CONNECTED
            }.onFailure { e ->
                Log.e("AgentViewModel", "MCP error: ${e.message}", e)
                _mcpTools.value = emptyList()
                _mcpServerName.value = ""
                _mcpStatus.value = McpStatus.ERROR
            }
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

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
