package ua.com.myaiagent.data

import ua.com.myaiagent.UiMessage

class ContextCompressor(private val api: OpenAiApi) {

    suspend fun compress(
        messages: List<UiMessage>,
        systemPrompt: String?,
        model: String,
    ): ApiResult = api.ask(buildPrompt(messages, systemPrompt), model, maxTokens = 512)

    private fun buildPrompt(messages: List<UiMessage>, systemPrompt: String?): String = buildString {
        appendLine("You are a conversation summarizer. Preserve: key facts, entities, topics, open questions.")
        if (!systemPrompt.isNullOrBlank()) {
            appendLine("The AI in this conversation uses system prompt: $systemPrompt")
        }
        appendLine("Conversation:")
        messages.forEach { msg ->
            appendLine("${if (msg.role == "user") "User" else "Assistant"}: ${msg.content}")
        }
        append("Write a concise summary (3-8 sentences) in the same language. Output only the summary.")
    }
}
