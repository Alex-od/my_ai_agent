package ua.com.myaiagent

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ua.com.myaiagent.data.ChatRepository
import ua.com.myaiagent.data.ContextCompressor
import ua.com.myaiagent.data.ConversationMessage
import ua.com.myaiagent.data.OpenAiApi
import ua.com.myaiagent.data.ResponsesRequestWithHistory
import ua.com.myaiagent.data.UsageInfo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    // суммаризация
    val summaryInput: Int = 0,
    val summaryOutput: Int = 0,
    val summaryCount: Int = 0,
)

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

class AgentViewModel(private val api: OpenAiApi, private val repository: ChatRepository, private val compressor: ContextCompressor) : ViewModel() {

    companion object {
        const val RECENT_KEEP = 6
        const val COMPRESS_EVERY = 10
    }

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

    private val _compressionEnabled = MutableStateFlow(true)
    val compressionEnabled: StateFlow<Boolean> = _compressionEnabled

    private val _compressedCount = MutableStateFlow(0)
    val compressedCount: StateFlow<Int> = _compressedCount

    val systemPromptInput = MutableStateFlow("")
    val temperatureInput = MutableStateFlow("")
    val topPInput = MutableStateFlow("")
    val maxTokensInput = MutableStateFlow("")
    val stopInput = MutableStateFlow("")

    private var activeConversationId: Long? = null
    private var lastCompressedAt: Int = 0

    init {
        viewModelScope.launch {
            val session = repository.getActiveSession() ?: return@launch
            activeConversationId = session.conversation.id
            val sys = session.messages.firstOrNull { it.role == "system" }
            if (sys != null) systemPromptInput.value = sys.content
            val nonSystem = session.messages.filter { it.role != "system" }
            _messages.value = nonSystem.map { UiMessage(it.role, it.content) }
            if (session.conversation.summary != null) {
                val compressed = maxOf(0, nonSystem.size - RECENT_KEEP)
                _compressedCount.value = compressed
                lastCompressedAt = compressed
            }
        }
    }

    fun selectModel(model: AiModel) {
        _selectedModel.value = model
    }

    fun setCompressionEnabled(enabled: Boolean) {
        _compressionEnabled.value = enabled
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

                val apiMessages = if (_compressionEnabled.value && updatedMessages.size > RECENT_KEEP) {
                    buildCompressedContext(conversationId, updatedMessages, systemPrompt, model.id)
                } else {
                    updatedMessages.map { ConversationMessage(it.role, it.content) }
                }
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
                val apiResult = api.askWithHistory(
                    messages = apiMessages,
                    model = model.id,
                    systemPrompt = systemPrompt,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    topP = topP,
                )

                val duration = System.currentTimeMillis() - startTime
                Log.d("AgentViewModel", "Response: ${apiResult.text}")
                Log.d("AgentViewModel", "Usage: ${apiResult.usage}")

                // обновляем статистику токенов
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

    private suspend fun buildCompressedContext(
        conversationId: Long,
        messages: List<UiMessage>,
        systemPrompt: String?,
        modelId: String,
    ): List<ConversationMessage> {
        val older = messages.dropLast(RECENT_KEEP)
        val recent = messages.takeLast(RECENT_KEEP)
        val olderCount = older.size

        _compressedCount.value = olderCount

        var currentSummary = repository.getSummary(conversationId)

        if (olderCount >= lastCompressedAt + COMPRESS_EVERY) {
            try {
                val summaryResult = compressor.compress(older, systemPrompt, modelId)
                currentSummary = summaryResult.text
                lastCompressedAt = olderCount
                repository.saveSummary(conversationId, currentSummary)
                summaryResult.usage?.let { usage ->
                    val prev = _tokenStats.value
                    _tokenStats.value = prev.copy(
                        summaryInput = prev.summaryInput + usage.inputTokens,
                        summaryOutput = prev.summaryOutput + usage.outputTokens,
                        summaryCount = prev.summaryCount + 1,
                    )
                }
            } catch (e: Exception) {
                Log.e("AgentViewModel", "Summary generation failed: ${e.message}", e)
            }
        }

        val recentMessages = recent.map { ConversationMessage(it.role, it.content) }
        return if (currentSummary != null) {
            listOf(ConversationMessage("system", "Previous conversation summary:\n$currentSummary")) + recentMessages
        } else {
            recentMessages
        }
    }

    fun startNewChat() {
        viewModelScope.launch {
            repository.startNewChat()
            activeConversationId = null
            _messages.value = emptyList()
            _compressedCount.value = 0
            lastCompressedAt = 0
            systemPromptInput.value = ""
            _tokenStats.value = TokenStats()
            _state.value = UiState.Idle
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
        appendLine("--- Messages ---")
        if (systemPrompt != null) appendLine("System: $systemPrompt")
        appendLine("User: $prompt")
        appendLine()
        appendLine("--- Response ($status) ---")
        append(response)
    }, rawJson = rawJson)
}
