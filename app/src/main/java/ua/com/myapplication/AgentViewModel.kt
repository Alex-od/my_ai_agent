package ua.com.myapplication

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ua.com.myapplication.data.OpenAiApi

sealed class UiState {
    data object Idle : UiState()
    data object Loading : UiState()
    data class Success(val text: String) : UiState()
    data class Error(val message: String) : UiState()
}

data class AiModel(
    val id: String,
    val displayName: String,
)

val availableModels = listOf(
    AiModel("gpt-5.2", "GPT-5.2"),
    AiModel("gpt-5.2-pro", "GPT-5.2 Pro"),
    AiModel("gpt-5.2-codex", "GPT-5.2 Codex"),
    AiModel("gpt-5-mini", "GPT-5 Mini"),
    AiModel("gpt-5-nano", "GPT-5 Nano"),
    AiModel("gpt-4.1-mini", "GPT-4.1 Mini"),
    AiModel("gpt-4.1", "GPT-4.1"),
    AiModel("gpt-4.1-nano", "GPT-4.1 Nano"),
    AiModel("gpt-4o", "GPT-4o"),
    AiModel("o4-mini", "o4-mini"),
    AiModel("o3", "o3"),
    AiModel("o3-mini", "o3-mini"),
)

class AgentViewModel(private val api: OpenAiApi) : ViewModel() {

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state

    private val _selectedModel = MutableStateFlow(availableModels.first())
    val selectedModel: StateFlow<AiModel> = _selectedModel

    fun selectModel(model: AiModel) {
        _selectedModel.value = model
    }

    fun send(
        prompt: String,
        systemPrompt: String? = null,
        stop: List<String>? = null,
        maxTokens: Int? = null,
        temperature: Double? = null,
        topP: Double? = null,
    ) {
        if (prompt.isBlank()) return
        _state.value = UiState.Loading
        viewModelScope.launch {
            try {
                val result = api.ask(
                    prompt = prompt,
                    model = _selectedModel.value.id,
                    systemPrompt = systemPrompt,
                    stop = stop,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    topP = topP,
                )
                Log.d("AgentViewModel", "Response: $result")
                _state.value = UiState.Success(result)
            } catch (e: Exception) {
                Log.e("AgentViewModel", "Error: ${e.message}", e)
                _state.value = UiState.Error(e.message ?: "Unknown error")
            }
        }
    }
}
