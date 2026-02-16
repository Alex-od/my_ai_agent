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

class AgentViewModel(private val api: OpenAiApi) : ViewModel() {

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state

    fun send(prompt: String) {
        if (prompt.isBlank()) return
        _state.value = UiState.Loading
        viewModelScope.launch {
            try {
                val result = api.ask(prompt)
                Log.d("AgentViewModel", "Response: $result")
                _state.value = UiState.Success(result)
            } catch (e: Exception) {
                Log.e("AgentViewModel", "Error: ${e.message}", e)
                _state.value = UiState.Error(e.message ?: "Unknown error")
            }
        }
    }
}
