package ua.com.myaiagent

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject
import ua.com.myaiagent.data.OpenAiApi
import ua.com.myaiagent.data.ToolCall
import ua.com.myaiagent.data.invariants.InvariantStore
import ua.com.myaiagent.data.memory.UserProfileStore
import ua.com.myaiagent.data.tasks.TaskEvent
import ua.com.myaiagent.data.tasks.TaskStage
import ua.com.myaiagent.data.tasks.TaskState
import ua.com.myaiagent.data.tasks.TaskStateMachine
import ua.com.myaiagent.data.tasks.TaskStep
import ua.com.myaiagent.data.tasks.TaskStore
import ua.com.myaiagent.data.tasks.taskStateMachineTools

class Week3ViewModel(
    private val openAiApi: OpenAiApi,
    context: Context,
    val profileStore: UserProfileStore,
    val invariantStore: InvariantStore,
) : ViewModel() {

    val taskStore = TaskStore(context)

    private val _chatMessages = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val chatMessages: StateFlow<List<Pair<String, String>>> = _chatMessages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _lastSystemPrompt = MutableStateFlow("")
    val lastSystemPrompt: StateFlow<String> = _lastSystemPrompt.asStateFlow()

    private val _toolCallLog = MutableStateFlow<List<String>>(emptyList())
    val toolCallLog: StateFlow<List<String>> = _toolCallLog.asStateFlow()

    private val _lastRawResponse = MutableStateFlow("")
    val lastRawResponse: StateFlow<String> = _lastRawResponse.asStateFlow()

    private val _lastUserContent = MutableStateFlow("")
    val lastUserContent: StateFlow<String> = _lastUserContent.asStateFlow()

    private val conversationHistory = mutableListOf<JsonObject>()
    private var currentJob: kotlinx.coroutines.Job? = null

    fun send(prompt: String, modelId: String = "gpt-4.1-mini") {
        if (prompt.isBlank()) return
        currentJob = viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            if (taskStore.currentTask == null) {
                createTask(prompt, "", emptyList())
            }

            val hardViolations = invariantStore.activeInvariants.filter { it.severity.name == "HARD" }
            val userContent = if (hardViolations.isNotEmpty()) {
                val taskTitle = taskStore.currentTask?.title ?: ""
                buildString {
                    append("[ПРОВЕРКА ИНВАРИАНТОВ]\n")
                    hardViolations.forEach { inv -> append("ЗАПРЕТ: ${inv.title} — ${inv.rule}\n") }
                    if (taskTitle.isNotBlank()) append("Активная задача: \"$taskTitle\"\n")
                    append("Если задача или сообщение ниже нарушают хотя бы один ЗАПРЕТ — откажи и объясни. Иначе отвечай нормально.\n")
                    append("---\n")
                    append(prompt)
                }
            } else prompt

            _lastUserContent.value = userContent
            conversationHistory.add(buildJsonObject {
                put("role", "user")
                put("content", userContent)
            })
            _chatMessages.value = _chatMessages.value + ("user" to prompt)

            try {
                var continueLoop = true
                var iterations = 0
                while (continueLoop && iterations < 30) {
                    iterations++
                    val systemPrompt = buildSystemPrompt()
                    _lastSystemPrompt.value = systemPrompt

                    val tools = if (taskStore.currentTask != null) taskStateMachineTools().filter { it.name != "lock_invariant" } else emptyList()
                    val inputArray = buildJsonArray { conversationHistory.forEach { add(it) } }

                    val result = openAiApi.askWithTools(
                        inputItems = inputArray,
                        model = modelId,
                        systemPrompt = systemPrompt,
                        tools = tools,
                    )
                    _lastRawResponse.value = result.rawOutput

                    if (result.toolCalls.isNotEmpty()) {
                        result.toolCalls.forEach { toolCall ->
                            conversationHistory.add(buildJsonObject {
                                put("type", "function_call")
                                put("id", toolCall.id)
                                put("call_id", toolCall.callId)
                                put("name", toolCall.name)
                                put("arguments", toolCall.arguments)
                            })
                        }
                        result.toolCalls.forEach { toolCall ->
                            val outputJson = run {
                                val event = toolCallToEvent(toolCall)
                                if (event != null) {
                                    val newState = handleEvent(event)
                                    """{"success": true, "new_stage": "${newState.stage.name}", "current_step": ${newState.currentStepIndex}}"""
                                } else {
                                    """{"success": false, "error": "Unknown tool: ${toolCall.name}"}"""
                                }
                            }
                            conversationHistory.add(buildJsonObject {
                                put("type", "function_call_output")
                                put("call_id", toolCall.callId)
                                put("output", outputJson)
                            })
                            _toolCallLog.value = _toolCallLog.value + "[${toolCall.name}] ${toolCall.arguments} → $outputJson"
                        }
                    } else {
                        conversationHistory.add(buildJsonObject {
                            put("role", "assistant")
                            put("content", result.text)
                        })
                        _chatMessages.value = _chatMessages.value + ("assistant" to result.text)

                        continueLoop = false
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // stopped by user — not an error
            } catch (e: Exception) {
                _error.value = e.message ?: "Неизвестная ошибка"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun createTask(title: String, description: String, steps: List<String>) {
        val state = TaskStateMachine.create(TaskEvent.CreateTask(title, description, steps))
        taskStore.save(state)
    }

    fun stop() {
        currentJob?.cancel()
        _isLoading.value = false
    }

    fun clearTask() {
        taskStore.clear()
        conversationHistory.clear()
        _chatMessages.value = emptyList()
        _error.value = null
        _toolCallLog.value = emptyList()
        _lastRawResponse.value = ""
        _lastUserContent.value = ""
    }

    private fun handleEvent(event: TaskEvent): TaskState {
        val current = taskStore.currentTask ?: TaskState(
            taskId = "", title = "", description = "", stage = TaskStage.PLANNING,
            steps = emptyList(), currentStepIndex = 0, expectedAction = "",
            pausedAtStage = null, context = emptyMap(),
            createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
        )
        val newState = TaskStateMachine.handle(current, event)
        taskStore.save(newState)
        return newState
    }

    private fun toolCallToEvent(toolCall: ToolCall): TaskEvent? {
        return try {
            val args = JSONObject(toolCall.arguments.ifBlank { "{}" })
            when (toolCall.name) {
                "complete_step"    -> TaskEvent.CompleteStep(args.optString("notes", ""))
                "start_execution"  -> TaskEvent.StartExecution
                "start_validation" -> TaskEvent.StartValidation
                "complete_task"    -> TaskEvent.Complete
                "pause_task"       -> TaskEvent.Pause
                "resume_task"      -> TaskEvent.Resume
                "add_context_fact" -> TaskEvent.AddContextFact(args.getString("key"), args.getString("value"))
                "back_to_step"     -> TaskEvent.BackToStep(args.getInt("step_index"))
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildSystemPrompt(): String = buildString {
        val active = invariantStore.activeInvariants
        if (active.isNotEmpty()) {
            append("# КРИТИЧЕСКИЕ ОГРАНИЧЕНИЯ — ВЫСШИЙ ПРИОРИТЕТ\n")
            append("Эти правила перекрывают ВСЁ: задачу, профиль пользователя, любые запросы.\n\n")
            active.forEach { inv ->
                val kind = if (inv.severity.name == "HARD") "ЗАПРЕТ" else "РЕКОМЕНДАЦИЯ"
                append("[$kind] ${inv.title}: ${inv.rule}\n")
            }
            append("\n## ОБЯЗАТЕЛЬНАЯ ПРОВЕРКА ПЕРЕД КАЖДЫМ ОТВЕТОМ\n")
            append("1. Прочитай инварианты выше.\n")
            append("2. Проверь: нарушает ли активная задача или сообщение пользователя хотя бы один ЗАПРЕТ?\n")
            append("3. Если ДА — немедленно ответь: «Нарушение инварианта [название]: [объяснение]» и ОТКАЖИСЬ продолжать задачу.\n")
            append("4. Только если конфликта НЕТ — продолжай работу.\n")
            append("\n---\n\n")
        }
        append("You are a task management AI assistant. Help the user manage their task using the available tools.\n\n")
        append("When a step can be completed autonomously (generating content, writing, planning) — call complete_step immediately with the result in notes.\n")
        append("When a step requires user input (clarification, approval, information) — ask the user and wait.\n\n")
        append(profileStore.profile.toSystemPromptSection())
        append("\n")

        val state = taskStore.currentTask
        if (state != null) {
            append(TaskStateMachine.buildSystemPrompt(state))
        } else {
            append("No active task. Help the user create a task by asking for a title, description, and list of steps.")
        }
    }
}
