package ua.com.myaiagent.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── Request ──────────────────────────────────────────────────────────────────

@Serializable
data class ResponsesRequest(
    val model: String,
    val input: String,
    val instructions: String? = null,
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
)

// ── Response ─────────────────────────────────────────────────────────────────

@Serializable
data class UsageInfo(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

data class ApiResult(val text: String, val usage: UsageInfo?)

@Serializable
data class ResponsesResponse(
    val output: List<OutputItem> = emptyList(),
    @SerialName("output_text") val outputText: String? = null,
    val usage: UsageInfo? = null,
)

@Serializable
data class OutputItem(
    val type: String,
    val content: List<ContentItem> = emptyList(),
)

@Serializable
data class ContentItem(
    val type: String,
    val text: String = "",
)

// ── Multi-turn Request ────────────────────────────────────────────────────────

@Serializable
data class ConversationMessage(val role: String, val content: String)

@Serializable
data class ResponsesRequestWithHistory(
    val model: String,
    val input: List<ConversationMessage>,
    val instructions: String? = null,
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
)

// ── API ───────────────────────────────────────────────────────────────────────

class OpenAiApi(
    private val client: HttpClient,
    private val apiKey: String,
) {
    suspend fun ask(
        prompt: String,
        model: String = "gpt-4.1-mini",
        systemPrompt: String? = null,
        stop: List<String>? = null,      // not supported by Responses API, ignored
        maxTokens: Int? = null,
        temperature: Double? = null,
        topP: Double? = null,
    ): ApiResult {
        val request = ResponsesRequest(
            model = model,
            input = prompt,
            instructions = systemPrompt?.takeIf { it.isNotBlank() },
            maxOutputTokens = maxTokens,
            temperature = temperature,
            topP = topP,
        )
        val httpResponse = client.post("https://api.openai.com/v1/responses") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiKey")
            setBody(request)
        }
        if (!httpResponse.status.isSuccess()) {
            val errorBody = httpResponse.bodyAsText()
            error("API error ${httpResponse.status.value}: $errorBody")
        }
        val response: ResponsesResponse = httpResponse.body()
        val text = response.outputText
            ?: response.output
                .firstOrNull { it.type == "message" }
                ?.content
                ?.firstOrNull { it.type == "output_text" }
                ?.text
            ?: "Empty response"
        return ApiResult(text, response.usage)
    }

    suspend fun askWithHistory(
        messages: List<ConversationMessage>,
        model: String,
        systemPrompt: String? = null,
        maxTokens: Int? = null,
        temperature: Double? = null,
        topP: Double? = null,
    ): ApiResult {
        val request = ResponsesRequestWithHistory(
            model = model,
            input = messages,
            instructions = systemPrompt?.takeIf { it.isNotBlank() },
            maxOutputTokens = maxTokens,
            temperature = temperature,
            topP = topP,
        )
        val httpResponse = client.post("https://api.openai.com/v1/responses") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiKey")
            setBody(request)
        }
        if (!httpResponse.status.isSuccess()) {
            val errorBody = httpResponse.bodyAsText()
            error("API error ${httpResponse.status.value}: $errorBody")
        }
        val response: ResponsesResponse = httpResponse.body()
        val text = response.outputText
            ?: response.output
                .firstOrNull { it.type == "message" }
                ?.content
                ?.firstOrNull { it.type == "output_text" }
                ?.text
            ?: "Empty response"
        return ApiResult(text, response.usage)
    }
}
