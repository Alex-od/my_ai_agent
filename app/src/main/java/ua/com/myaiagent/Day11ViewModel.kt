package ua.com.myaiagent

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ua.com.myaiagent.data.ConversationMessage
import ua.com.myaiagent.data.OpenAiApi
import ua.com.myaiagent.data.memory.MemoryMessage
import ua.com.myaiagent.data.memory.MemorySnapshot
import ua.com.myaiagent.data.memory.MemoryStore
import ua.com.myaiagent.data.memory.UserProfile
import ua.com.myaiagent.data.memory.UserProfileStore

/**
 * ViewModel для Дня 11 — Модель памяти ассистента.
 *
 * Полный цикл на каждое сообщение:
 * 1. Router классифицирует сообщение → нужный слой памяти
 * 2. Данные сохраняются в Short-Term / Working / Long-Term
 * 3. PromptBuilder собирает системный промпт из всех слоёв
 * 4. API-запрос с историей и обогащённым системным промптом
 * 5. Ответ сохраняется в Short-Term, UI обновляется
 */
class Day11ViewModel(
    private val openAiApi: OpenAiApi,
    context: Context,
    val profileStore: UserProfileStore,
) : ViewModel() {

    val memoryStore = MemoryStore(context)

    val profileFlow: StateFlow<UserProfile> = profileStore.profileFlow

    // Полный список сообщений для отображения в чате (без ограничений sliding window)
    private val _chatMessages = MutableStateFlow<List<MemoryMessage>>(emptyList())
    val chatMessages: StateFlow<List<MemoryMessage>> = _chatMessages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Снимок состояния памяти — обновляется после каждого действия
    val memorySnapshot: StateFlow<MemorySnapshot> = memoryStore.snapshotFlow

    // Лог маршрутизатора — куда попало последнее сообщение
    val routerLog: StateFlow<String> = memoryStore.routerLog

    // Системный промпт последнего запроса — для отображения в UI
    private val _lastSystemPrompt = MutableStateFlow("")
    val lastSystemPrompt: StateFlow<String> = _lastSystemPrompt.asStateFlow()

    fun send(prompt: String, modelId: String = "gpt-4.1-mini") {
        if (prompt.isBlank()) return
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            // Добавляем сообщение пользователя в UI-список немедленно
            _chatMessages.value = _chatMessages.value + MemoryMessage("user", prompt)

            try {
                // Шаг 1: Маршрутизация — Router классифицирует и сохраняет в нужный слой
                memoryStore.processAndRoute(prompt)

                // Шаг 2: Персонализированный системный промпт (профиль → память)
                val systemPrompt = buildPersonalizedSystemPrompt()
                _lastSystemPrompt.value = systemPrompt

                // Шаг 3: Формируем историю сообщений для API
                // Используем скользящее окно из short-term (уже содержит текущее сообщение)
                val apiMessages = memoryStore.shortTerm.messages.map {
                    ConversationMessage(it.role, it.content)
                }

                // Шаг 4: Вызов API с обогащённым системным промптом
                val result = openAiApi.askWithHistory(
                    messages = apiMessages,
                    model = modelId,
                    systemPrompt = systemPrompt,
                )

                // Шаг 5: Сохраняем ответ в Short-Term память
                memoryStore.addAssistantMessage(result.text)

                // Обновляем UI-список
                _chatMessages.value = _chatMessages.value + MemoryMessage("assistant", result.text)

            } catch (e: Exception) {
                _error.value = e.message ?: "Неизвестная ошибка"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Строит персонализированный системный промпт.
     * Порядок приоритетов:
     *   1. Профиль пользователя (стиль, уровень, ограничения)
     *   2. Working Memory (текущая задача)
     *   3. Long-Term Memory (профиль, предпочтения)
     *   4. Summary краткосрочной памяти
     */
    fun buildPersonalizedSystemPrompt(): String = buildString {
        append("You are a personalized AI assistant. Adapt ALL your responses to the user profile below.\n\n")
        append(profileStore.profile.toSystemPromptSection())
        val memorySections = memoryStore.buildSystemPrompt()
        // buildSystemPrompt() returns a default line if nothing stored — skip that in favour of our header
        val memoryBody = memorySections
            .removePrefix("You are a helpful AI assistant.")
            .removePrefix("You are a helpful AI assistant with memory.\nUse the following context to personalize your responses.\n\n")
            .trim()
        if (memoryBody.isNotBlank()) {
            append("\n")
            append(memoryBody)
        }
    }

    /** Переключает активный профиль пользователя. */
    fun setProfile(profile: UserProfile) = profileStore.setProfile(profile)

    /** Явная установка текущей задачи (Working Memory). */
    fun setTask(name: String) {
        memoryStore.setTask(name)
    }

    /** Очистка краткосрочной памяти. Working и Long-Term остаются! */
    fun clearShortTerm() {
        memoryStore.clearShortTerm()
        _chatMessages.value = emptyList()
        _error.value = null
    }

    /** Очистка долговременной памяти (удаляет и из SharedPreferences). */
    fun clearLongTerm() {
        memoryStore.clearLongTerm()
    }

    /** Полный сброс всех слоёв. */
    fun clearAll() {
        memoryStore.clearAll()
        _chatMessages.value = emptyList()
        _error.value = null
        _lastSystemPrompt.value = ""
    }
}
