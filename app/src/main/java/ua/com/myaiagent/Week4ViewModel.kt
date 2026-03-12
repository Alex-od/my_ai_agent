package ua.com.myaiagent

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
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
import ua.com.myaiagent.data.pipeline.PipelineToolExecutor
import ua.com.myaiagent.data.pipeline.SearchMode
import ua.com.myaiagent.data.pipeline.pipelineTools

enum class PipelineStep { SEARCH, SUMMARIZE, SAVE_TO_FILE }

enum class StepStatus { PENDING, RUNNING, DONE, ERROR }

data class PipelineState(
    val steps: Map<PipelineStep, StepStatus> = mapOf(
        PipelineStep.SEARCH to StepStatus.PENDING,
        PipelineStep.SUMMARIZE to StepStatus.PENDING,
        PipelineStep.SAVE_TO_FILE to StepStatus.PENDING,
    ),
    val savedFilePath: String? = null,
)

class Week4ViewModel(
    private val openAiApi: OpenAiApi,
    private val httpClient: HttpClient,
    private val apiKey: String,
    context: Context,
) : ViewModel() {

    private val toolExecutor = PipelineToolExecutor(httpClient, context, apiKey)

    private val _messages = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val messages: StateFlow<List<Pair<String, String>>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _pipeline = MutableStateFlow(PipelineState())
    val pipeline: StateFlow<PipelineState> = _pipeline.asStateFlow()

    private val _toolCallLog = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val toolCallLog: StateFlow<List<Pair<String, String>>> = _toolCallLog.asStateFlow()

    val searchMode = MutableStateFlow(SearchMode.WIKIPEDIA)

    private val conversationHistory = mutableListOf<JsonObject>()
    private var currentJob: kotlinx.coroutines.Job? = null

    private fun buildSystemPrompt(mode: SearchMode): String = """
        You are a research assistant. When the user specifies a topic, follow this pipeline EXACTLY:
        1. Call `search` EXACTLY ONCE to ${if (mode == SearchMode.WIKIPEDIA) "find information on Wikipedia about" else "generate detailed information using LLM knowledge about"} the topic.
        2. Call `summarize` EXACTLY ONCE with the full text from the search result as the `text` argument.
        3. Call `saveToFile` EXACTLY ONCE with filename "mcp.md" and the summarized text as content.

        CRITICAL RULES:
        - Call each tool EXACTLY ONCE. Never repeat a tool that already returned a result.
        - Do NOT call `search` more than once.
        - Do NOT ask the user anything between steps.
        - After all three steps complete, write a short confirmation message.
    """.trimIndent()

    fun send(topic: String, modelId: String = "gpt-4.1-mini") {
        if (topic.isBlank()) return
        val currentMode = searchMode.value
        currentJob = viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            conversationHistory.add(buildJsonObject {
                put("role", "user")
                put("content", topic)
            })
            _messages.value = _messages.value + ("user" to topic)

            try {
                var continueLoop = true
                var iterations = 0
                while (continueLoop && iterations < 30) {
                    iterations++
                    val tools = pipelineTools(currentMode)
                    val inputArray = buildJsonArray { conversationHistory.forEach { add(it) } }

                    val result = openAiApi.askWithTools(
                        inputItems = inputArray,
                        model = modelId,
                        systemPrompt = buildSystemPrompt(currentMode),
                        tools = tools,
                    )

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
                            val step = toolNameToStep(toolCall.name)
                            if (step != null) {
                                _pipeline.value = _pipeline.value.copy(
                                    steps = _pipeline.value.steps + (step to StepStatus.RUNNING)
                                )
                            }

                            val outputJson = try {
                                // Guard: reject repeated calls to already-completed steps
                                if (step != null && _pipeline.value.steps[step] == StepStatus.DONE) {
                                    throw Exception("Step ${toolCall.name} already completed. Do not call it again.")
                                }
                                val args = JSONObject(toolCall.arguments.ifBlank { "{}" })
                                val output = when (toolCall.name) {
                                    "search" -> {
                                        val query = args.optString("query", "")
                                        toolExecutor.search(query, currentMode)
                                    }
                                    "summarize" -> {
                                        val text = args.optString("text", "")
                                        val maxSentences = args.optInt("max_sentences", 5)
                                        toolExecutor.summarize(text, maxSentences)
                                    }
                                    "saveToFile" -> {
                                        val filename = args.optString("filename", "output.md")
                                        val content = args.optString("content", "")
                                        val path = toolExecutor.saveToFile(filename, content)
                                        _pipeline.value = _pipeline.value.copy(savedFilePath = path)
                                        "Saved to: $path"
                                    }
                                    "readFile" -> {
                                        val filename = args.optString("filename", "")
                                        toolExecutor.readFile(filename)
                                    }
                                    else -> "Unknown tool: ${toolCall.name}"
                                }
                                if (step != null) {
                                    _pipeline.value = _pipeline.value.copy(
                                        steps = _pipeline.value.steps + (step to StepStatus.DONE)
                                    )
                                }
                                _toolCallLog.value = _toolCallLog.value + (toolCall.name to output)
                                """{"success": true, "result": ${JSONObject.quote(output)}}"""
                            } catch (e: Exception) {
                                if (step != null) {
                                    _pipeline.value = _pipeline.value.copy(
                                        steps = _pipeline.value.steps + (step to StepStatus.ERROR)
                                    )
                                }
                                val errMsg = e.message ?: "Error"
                                _toolCallLog.value = _toolCallLog.value + (toolCall.name to "ERROR: $errMsg")
                                """{"success": false, "error": ${JSONObject.quote(errMsg)}}"""
                            }

                            conversationHistory.add(buildJsonObject {
                                put("type", "function_call_output")
                                put("call_id", toolCall.callId)
                                put("output", outputJson)
                            })
                        }
                    } else {
                        conversationHistory.add(buildJsonObject {
                            put("role", "assistant")
                            put("content", result.text)
                        })
                        _messages.value = _messages.value + ("assistant" to result.text)
                        continueLoop = false
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // stopped by user
            } catch (e: Exception) {
                _error.value = e.message ?: "Неизвестная ошибка"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun stop() {
        currentJob?.cancel()
        _isLoading.value = false
    }

    fun reset() {
        currentJob?.cancel()
        _messages.value = emptyList()
        _isLoading.value = false
        _error.value = null
        _pipeline.value = PipelineState()
        _toolCallLog.value = emptyList()
        conversationHistory.clear()
    }

    private fun toolNameToStep(name: String): PipelineStep? = when (name) {
        "search" -> PipelineStep.SEARCH
        "summarize" -> PipelineStep.SUMMARIZE
        "saveToFile" -> PipelineStep.SAVE_TO_FILE
        else -> null
    }
}
