package ua.com.myaiagent.data.context

import android.util.Log
import ua.com.myaiagent.UiMessage
import ua.com.myaiagent.data.ChatRepository
import ua.com.myaiagent.data.ContextCompressor
import ua.com.myaiagent.data.ConversationMessage

/**
 * Summarises older messages into a short text, keeps recent ones verbatim.
 * Extracted from the original AgentViewModel.buildCompressedContext().
 */
class SummaryStrategy(
    private val compressor: ContextCompressor,
    private val repository: ChatRepository,
    private val recentKeep: Int = 6,
    private val compressEvery: Int = 10,
) : ContextStrategy {

    override val type = StrategyType.SUMMARY
    override val description = "Сжатие каждые $compressEvery, последние $recentKeep как есть"

    private var lastCompressedAt: Int = 0

    override suspend fun buildContext(
        allMessages: List<UiMessage>,
        ctx: StrategyContext,
    ): ContextResult {
        val older = allMessages.dropLast(recentKeep)
        val recent = allMessages.takeLast(recentKeep)
        val olderCount = older.size

        var currentSummary = repository.getSummary(ctx.conversationId)
        var usage: ua.com.myaiagent.data.UsageInfo? = null

        if (olderCount >= lastCompressedAt + compressEvery) {
            try {
                val result = compressor.compress(older, ctx.systemPrompt, ctx.modelId)
                currentSummary = result.text
                lastCompressedAt = olderCount
                repository.saveSummary(ctx.conversationId, currentSummary)
                usage = result.usage
            } catch (e: Exception) {
                Log.e("SummaryStrategy", "Summary generation failed: ${e.message}", e)
            }
        }

        val recentMessages = recent.map { ConversationMessage(it.role, it.content) }
        val messages = if (currentSummary != null) {
            listOf(ConversationMessage("system", "Previous conversation summary:\n$currentSummary")) + recentMessages
        } else {
            recentMessages
        }

        return ContextResult(
            messages = messages,
            compressedCount = olderCount,
            strategyUsage = usage,
            info = if (currentSummary != null) "Суммаризировано $olderCount сообщ., последние $recentKeep как есть"
                   else "Ещё не сжато, последние ${recent.size} сообщ.",
        )
    }

    fun resetCompression() {
        lastCompressedAt = 0
    }
}
