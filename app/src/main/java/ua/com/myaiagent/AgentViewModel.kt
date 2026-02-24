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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RequestLog(val content: String)

data class UiMessage(val role: String, val content: String)

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

class AgentViewModel(private val api: OpenAiApi, private val repository: ChatRepository) : ViewModel() {

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state

    private val _selectedModel = MutableStateFlow(availableModels.first { it.id == "gpt-4.1-mini" })
    val selectedModel: StateFlow<AiModel> = _selectedModel

    private val _lastRequestLog = MutableStateFlow<RequestLog?>(null)
    val lastRequestLog: StateFlow<RequestLog?> = _lastRequestLog

    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages

    val systemPromptInput = MutableStateFlow("")
    val temperatureInput = MutableStateFlow("")
    val topPInput = MutableStateFlow("")
    val maxTokensInput = MutableStateFlow("")
    val stopInput = MutableStateFlow("")

    private var activeConversationId: Long? = null

    init {
        viewModelScope.launch {
            val session = repository.getActiveSession() ?: return@launch
            activeConversationId = session.conversation.id
            val sys = session.messages.firstOrNull { it.role == "system" }
            if (sys != null) systemPromptInput.value = sys.content
            _messages.value = session.messages
                .filter { it.role != "system" }
                .map { UiMessage(it.role, it.content) }
        }
    }

    fun selectModel(model: AiModel) {
        _selectedModel.value = model
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
            try {
                val conversationId = activeConversationId
                    ?: repository.getOrCreateSession(model.id, systemPrompt).also {
                        activeConversationId = it
                    }

                repository.appendUserMessage(conversationId, prompt)
                val updatedMessages = _messages.value + UiMessage("user", prompt)
                _messages.value = updatedMessages

                val apiMessages = updatedMessages.map { ConversationMessage(it.role, it.content) }
                val result = api.askWithHistory(
                    messages = apiMessages,
                    model = model.id,
                    systemPrompt = systemPrompt,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    topP = topP,
                )

                val duration = System.currentTimeMillis() - startTime
                Log.d("AgentViewModel", "Response: $result")

                repository.appendAssistantMessage(conversationId, result)
                _messages.value = _messages.value + UiMessage("assistant", result)
                _state.value = UiState.Idle

                _lastRequestLog.value = buildLog(
                    timestamp, model, prompt, systemPrompt,
                    temperature, topP, null, maxTokens, duration, "Success", result,
                )
            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - startTime
                Log.e("AgentViewModel", "Error: ${e.message}", e)
                _state.value = UiState.Error(e.message ?: "Unknown error")
                _lastRequestLog.value = buildLog(
                    timestamp, model, prompt, systemPrompt,
                    temperature, topP, null, maxTokens, duration, "Error", e.message ?: "Unknown error",
                )
            }
        }
    }

    fun startNewChat() {
        viewModelScope.launch {
            repository.startNewChat()
            activeConversationId = null
            _messages.value = emptyList()
            systemPromptInput.value = ""
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
    ) = RequestLog(buildString {
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
        appendLine("--- Messages ---")
        if (systemPrompt != null) appendLine("System: $systemPrompt")
        appendLine("User: $prompt")
        appendLine()
        appendLine("--- Response ($status) ---")
        append(response)
    })
}
