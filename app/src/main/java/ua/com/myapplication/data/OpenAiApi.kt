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
        stop: List<String>? = null,
        maxTokens: Int? = null,
    ): String {
        val request = ChatRequest(
            model = model,
            messages = listOf(ChatMessage(role = "user", content = prompt)),
            stop = stop?.takeIf { it.isNotEmpty() },
            maxTokens = maxTokens,
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
