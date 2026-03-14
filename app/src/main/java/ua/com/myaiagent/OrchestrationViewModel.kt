package ua.com.myaiagent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject
import ua.com.myaiagent.data.OpenAiApi
import ua.com.myaiagent.data.mcp.McpOrchestrator
import ua.com.myaiagent.data.mcp.toToolDefinition

const val URL_WEATHER    = "http://192.168.0.102:8080"
const val URL_FILE       = "http://192.168.0.102:8081"
const val URL_CALCULATOR = "http://192.168.0.102:8082"

private val ALL_URLS = listOf(URL_WEATHER, URL_FILE, URL_CALCULATOR)

/** One entry in the tool-call log shown in the UI. */
data class OrchestratorLogEntry(
    val direction: Direction,
    val toolName: String,
    val serverName: String,
    val content: String,
) {
    enum class Direction { REQUEST, RESPONSE }
}

class OrchestrationViewModel(
    private val openAiApi: OpenAiApi,
    private val mcpOrchestrator: McpOrchestrator,
    private val apiKey: String,
) : ViewModel() {

    // url → "CONNECTED" | "CONNECTING" | "ERROR: ..."
    private val _serverStatuses = MutableStateFlow<Map<String, String>>(
        ALL_URLS.associateWith { "DISCONNECTED" }
    )
    val serverStatuses: StateFlow<Map<String, String>> = _serverStatuses.asStateFlow()

    private val _messages = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val messages: StateFlow<List<Pair<String, String>>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _logs = MutableStateFlow<List<OrchestratorLogEntry>>(emptyList())
    val logs: StateFlow<List<OrchestratorLogEntry>> = _logs.asStateFlow()

    private val conversationHistory = mutableListOf<kotlinx.serialization.json.JsonObject>()
    private var currentJob: kotlinx.coroutines.Job? = null

    // ── Connect ───────────────────────────────────────────────────────────────

    fun connectAll() {
        viewModelScope.launch {
            _serverStatuses.value = ALL_URLS.associateWith { "CONNECTING" }
            val results = mcpOrchestrator.connectAll(ALL_URLS)
            _serverStatuses.value = ALL_URLS.associateWith { url ->
                results[url] ?: "ERROR: no response"
            }
        }
    }

    // ── Send user message ─────────────────────────────────────────────────────

    fun send(userMessage: String, modelId: String = "gpt-4.1-mini") {
        if (userMessage.isBlank()) return
        currentJob = viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            conversationHistory.add(buildJsonObject {
                put("role", "user")
                put("content", userMessage)
            })
            _messages.value = _messages.value + ("user" to userMessage)

            try {
                val tools = mcpOrchestrator.listAllTools().map { it.toToolDefinition() }

                var continueLoop = true
                var iterations = 0
                while (continueLoop && iterations < 10) {
                    iterations++

                    val inputArray = buildJsonArray { conversationHistory.forEach { add(it) } }

                    val result = openAiApi.askWithTools(
                        inputItems = inputArray,
                        model = modelId,
                        systemPrompt = SYSTEM_PROMPT,
                        tools = tools,
                    )

                    if (result.toolCalls.isNotEmpty()) {
                        // Record function_call items in history
                        result.toolCalls.forEach { toolCall ->
                            conversationHistory.add(buildJsonObject {
                                put("type", "function_call")
                                put("id", toolCall.id)
                                put("call_id", toolCall.callId)
                                put("name", toolCall.name)
                                put("arguments", toolCall.arguments)
                            })
                        }

                        // Execute each tool call
                        result.toolCalls.forEach { toolCall ->
                            val serverName = mcpOrchestrator.serverNameForTool(toolCall.name)
                            addLog(
                                direction = OrchestratorLogEntry.Direction.REQUEST,
                                toolName = toolCall.name,
                                serverName = serverName,
                                content = toolCall.arguments,
                            )

                            val outputJson = try {
                                val output = mcpOrchestrator.callTool(toolCall.name, toolCall.arguments)
                                addLog(
                                    direction = OrchestratorLogEntry.Direction.RESPONSE,
                                    toolName = toolCall.name,
                                    serverName = serverName,
                                    content = output,
                                )
                                """{"success": true, "result": ${JSONObject.quote(output)}}"""
                            } catch (e: Exception) {
                                val errMsg = e.message ?: "Error"
                                addLog(
                                    direction = OrchestratorLogEntry.Direction.RESPONSE,
                                    toolName = toolCall.name,
                                    serverName = serverName,
                                    content = "ERROR: $errMsg",
                                )
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
        _logs.value = emptyList()
        _isLoading.value = false
        _error.value = null
        conversationHistory.clear()
    }

    private fun addLog(
        direction: OrchestratorLogEntry.Direction,
        toolName: String,
        serverName: String,
        content: String,
    ) {
        _logs.value = _logs.value + OrchestratorLogEntry(direction, toolName, serverName, content)
    }

    companion object {
        private val SYSTEM_PROMPT = """
            You are an orchestration agent with access to tools from multiple servers:
            - WeatherServer: get_current_weather, get_forecast
            - FileServer: saveFile, readFile
            - CalculatorServer: calculate, calculateDifference

            Execute all required tool calls in the correct order.
            Do NOT ask the user for confirmation between steps.
            After all steps are complete, write a concise summary in Russian.
        """.trimIndent()
    }
}
