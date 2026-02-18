package ua.com.myapplication.data

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

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stop: List<String>? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
)

@Serializable
data class ChatResponse(
    val choices: List<Choice>,
)

@Serializable
data class Choice(
    val message: ChatMessage,
    @SerialName("finish_reason") val finishReason: String? = null,
)

class OpenAiApi(
    private val client: HttpClient,
    private val apiKey: String,
) {
    suspend fun ask(
        prompt: String,
        model: String = "gpt-4o-mini",
        systemPrompt: String? = null,
        stop: List<String>? = null,
        maxTokens: Int? = null,
        temperature: Double? = null,
        topP: Double? = null,
    ): String {
        val messages = buildList {
            if (!systemPrompt.isNullOrBlank()) {
                add(ChatMessage(role = "system", content = systemPrompt))
            }
            add(ChatMessage(role = "user", content = prompt))
        }
        val request = ChatRequest(
            model = model,
            messages = messages,
            stop = stop?.takeIf { it.isNotEmpty() },
            maxTokens = maxTokens,
            temperature = temperature,
            topP = topP,
        )
        val httpResponse = client.post("https://api.openai.com/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiKey")
            setBody(request)
        }
        if (!httpResponse.status.isSuccess()) {
            val errorBody = httpResponse.bodyAsText()
            error("API error ${httpResponse.status.value}: $errorBody")
        }
        val response: ChatResponse = httpResponse.body()
        return response.choices.firstOrNull()?.message?.content ?: "Empty response"
    }
}
