package ua.com.myaiagent

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ua.com.myaiagent.data.ConversationMessage
import ua.com.myaiagent.data.ResponsesRequestWithHistory
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

    // Лог последнего запроса (аналог RequestLog из AgentViewModel)
    private val _lastRequestLog = MutableStateFlow<RequestLog?>(null)
    val lastRequestLog: StateFlow<RequestLog?> = _lastRequestLog.asStateFlow()

    fun send(prompt: String, modelId: String = "gpt-4.1-mini") {
        if (prompt.isBlank()) return
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            // Добавляем сообщение пользователя в UI-список немедленно
            _chatMessages.value = _chatMessages.value + MemoryMessage("user", prompt)

            val prettyJson = Json { prettyPrint = true }
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            var requestJson = ""
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

                requestJson = try {
                    prettyJson.encodeToString(
                        ResponsesRequestWithHistory(
                            model = modelId,
                            input = apiMessages,
                            instructions = systemPrompt.takeIf { it.isNotBlank() },
                        )
                    )
                } catch (_: Exception) { "" }

                // Шаг 4: Вызов API с обогащённым системным промптом
                val start = System.currentTimeMillis()
                val result = openAiApi.askWithHistory(
                    messages = apiMessages,
                    model = modelId,
                    systemPrompt = systemPrompt,
                )
                val duration = System.currentTimeMillis() - start

                // Шаг 5: Сохраняем ответ в Short-Term память
                memoryStore.addAssistantMessage(result.text)

                // Обновляем UI-список
                _chatMessages.value = _chatMessages.value + MemoryMessage("assistant", result.text)

                // Строим лог запроса
                val profile = profileStore.profile
                val responseJson = try {
                    prettyJson.encodeToString(buildJsonObject {
                        put("text", result.text)
                        put("truncated", result.truncated)
                        result.usage?.let { u ->
                            put("usage", buildJsonObject {
                                put("input_tokens", u.inputTokens)
                                put("output_tokens", u.outputTokens)
                                put("total_tokens", u.totalTokens)
                            })
                        }
                    })
                } catch (_: Exception) { "" }

                _lastRequestLog.value = RequestLog(
                    content = buildString {
                        appendLine("=== Memory Agent Log ===")
                        appendLine("Time:     $timestamp")
                        appendLine("Model:    $modelId")
                        appendLine("Profile:  ${profile.name}")
                        appendLine("Duration: ${duration}ms")
                        result.usage?.let { u ->
                            appendLine("Tokens:   in=${u.inputTokens}  out=${u.outputTokens}  total=${u.totalTokens}")
                        }
                        appendLine()
                        appendLine("--- Messages sent (${apiMessages.size}) ---")
                        apiMessages.forEach { m ->
                            val preview = m.content.take(80).replace('\n', ' ')
                            appendLine("[${m.role}] $preview${if (m.content.length > 80) "…" else ""}")
                        }
                        appendLine()
                        appendLine("--- Router ---")
                        appendLine(memoryStore.routerLog.value.ifBlank { "no classifications" })
                        appendLine()
                        appendLine("--- Response preview ---")
                        appendLine(result.text.take(200) + if (result.text.length > 200) "…" else "")
                    },
                    rawJson = "// Request\n$requestJson\n\n// Response\n$responseJson",
                )

            } catch (e: Exception) {
                _error.value = e.message ?: "Неизвестная ошибка"
                _lastRequestLog.value = RequestLog(
                    content = "[$timestamp] Error: ${e.message}",
                    rawJson = if (requestJson.isNotEmpty()) "// Request\n$requestJson\n\n// Error: ${e.message}" else "// Error: ${e.message}",
                )
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
