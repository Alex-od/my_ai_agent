package ua.com.myaiagent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ua.com.myaiagent.data.ChatRepository

class HistoryViewModel(private val repository: ChatRepository) : ViewModel() {

    val conversations = repository.conversations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(conversationId: Long) {
        viewModelScope.launch { repository.delete(conversationId) }
    }
}
