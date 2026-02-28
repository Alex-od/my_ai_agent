package ua.com.myaiagent.data.context

import ua.com.myaiagent.UiMessage
import ua.com.myaiagent.data.ConversationMessage
import ua.com.myaiagent.data.UsageInfo

/** Result of building context for the API call. */
data class ContextResult(
    val messages: List<ConversationMessage>,
    /** How many older messages were compressed/dropped. */
    val compressedCount: Int = 0,
    /** Token usage if the strategy made an API call (e.g. summary, fact extraction). */
    val strategyUsage: UsageInfo? = null,
    /** Human-readable description of what the strategy did. */
    val info: String = "",
)

/** Metadata passed to every strategy so it can access DB / conversation state. */
data class StrategyContext(
    val conversationId: Long,
    val systemPrompt: String?,
    val modelId: String,
)

enum class StrategyType(val label: String) {
    SLIDING_WINDOW("Скользящее окно"),
    SUMMARY("Суммаризация"),
    STICKY_FACTS("Ключевые факты"),
    BRANCHING("Ветвление"),
}

/** Strategy pattern interface for context management. */
interface ContextStrategy {
    val type: StrategyType
    val description: String

    /**
     * Build the list of messages to send to the API.
     *
     * @param allMessages full conversation so far (user + assistant, no system)
     * @param ctx metadata (conversationId, systemPrompt, modelId)
     * @return [ContextResult] with the messages to send + metadata
     */
    suspend fun buildContext(
        allMessages: List<UiMessage>,
        ctx: StrategyContext,
    ): ContextResult
}
