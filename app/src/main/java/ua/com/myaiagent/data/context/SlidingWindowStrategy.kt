package ua.com.myaiagent.data.context

import ua.com.myaiagent.UiMessage
import ua.com.myaiagent.data.ConversationMessage

/**
 * Simplest strategy: keeps only the last [windowSize] messages.
 * Older messages are silently dropped.
 */
class SlidingWindowStrategy(
    private val windowSize: Int = 5,
) : ContextStrategy {

    override val type = StrategyType.SLIDING_WINDOW
    override val description = "Последние $windowSize сообщений"

    override suspend fun buildContext(
        allMessages: List<UiMessage>,
        ctx: StrategyContext,
    ): ContextResult {
        val window = allMessages.takeLast(windowSize)
        val dropped = allMessages.size - window.size
        return ContextResult(
            messages = window.map { ConversationMessage(it.role, it.content) },
            compressedCount = dropped,
            info = if (dropped > 0) "Окно: $windowSize, отброшено: $dropped"
                   else "Окно: $windowSize, все сообщения",
        )
    }
}
