package ua.com.myaiagent

import ua.com.myaiagent.data.ChatMessage
import ua.com.myaiagent.data.OpenAiApi

class Agent(private val api: OpenAiApi) {

    private val history = mutableListOf<ChatMessage>()

    fun setSystemPrompt(text: String) {
        history.removeAll { it.role == "system" }
        if (text.isNotBlank()) {
            history.add(0, ChatMessage(role = "system", content = text))
        }
    }

    suspend fun send(
        userMessage: String,
        model: String = "gpt-4.1-mini",
        maxTokens: Int? = null,
        temperature: Double? = null,
        topP: Double? = null,
    ): String {
        history.add(ChatMessage(role = "user", content = userMessage))
        val reply = api.chat(
            messages = history,
            model = model,
            maxTokens = maxTokens,
            temperature = temperature,
            topP = topP,
        )
        history.add(ChatMessage(role = "assistant", content = reply))
        return reply
    }

    fun reset() {
        history.clear()
    }

    fun getHistory(): List<ChatMessage> = history.toList()
}
