package ua.com.myaiagent.data.context

import android.util.Log
import ua.com.myaiagent.UiMessage
import ua.com.myaiagent.data.ConversationMessage
import ua.com.myaiagent.data.OpenAiApi
import ua.com.myaiagent.data.UsageInfo
import ua.com.myaiagent.data.local.ChatDao
import ua.com.myaiagent.data.local.FactEntity

/**
 * Sticky Facts / Key-Value Memory strategy.
 *
 * After EVERY user message: extracts key-value facts from the last exchange
 * and upserts them into the DB. Facts accumulate and are updated in-place
 * (same key → value overwritten), so only the latest value per key is kept.
 *
 * Context sent to API: facts block + last [recentKeep] messages.
 */
class StickyFactsStrategy(
    private val api: OpenAiApi,
    private val dao: ChatDao,
    private val recentKeep: Int = 5,
) : ContextStrategy {

    override val type = StrategyType.STICKY_FACTS
    override val description = "Факты + последние $recentKeep сообщений"

    override suspend fun buildContext(
        allMessages: List<UiMessage>,
        ctx: StrategyContext,
    ): ContextResult {
        var usage: UsageInfo? = null

        // Extract facts from the last exchange (last user msg + preceding assistant msg if any)
        val lastExchange = buildLastExchange(allMessages)
        if (lastExchange.isNotEmpty()) {
            try {
                val result = extractFacts(lastExchange, ctx)
                usage = result.usage
            } catch (e: Exception) {
                Log.e("StickyFactsStrategy", "Fact extraction failed: ${e.message}", e)
            }
        }

        // Load current facts
        val facts = dao.getFactsForConversation(ctx.conversationId)
        val recent = allMessages.takeLast(recentKeep)
        val dropped = allMessages.size - recent.size

        val messages = mutableListOf<ConversationMessage>()

        // Key-value facts preamble
        if (facts.isNotEmpty()) {
            val factsText = facts.joinToString("\n") { "${it.key}: ${it.value}" }
            messages.add(
                ConversationMessage(
                    "system",
                    "Key facts about this conversation:\n$factsText"
                )
            )
        }

        messages.addAll(recent.map { ConversationMessage(it.role, it.content) })

        return ContextResult(
            messages = messages,
            compressedCount = dropped,
            strategyUsage = usage,
            info = "Фактов: ${facts.size}, последние $recentKeep сообщ.",
        )
    }

    /** Take last user message + the assistant message just before it (if any). */
    private fun buildLastExchange(messages: List<UiMessage>): List<UiMessage> {
        if (messages.isEmpty()) return emptyList()
        val last = messages.last()
        if (last.role != "user") return emptyList()
        val prevAssistant = messages.dropLast(1).lastOrNull { it.role == "assistant" }
        return listOfNotNull(prevAssistant, last)
    }

    private suspend fun extractFacts(
        exchange: List<UiMessage>,
        ctx: StrategyContext,
    ): ua.com.myaiagent.data.ApiResult {
        val prompt = buildString {
            appendLine("Extract key-value facts from this conversation exchange.")
            appendLine("Output ONLY lines in format \"KEY: value\", one per line. No other text.")
            appendLine("Keys must be SHORT (1-3 words, UPPERCASE), values concise.")
            appendLine("Focus on: goal, constraints, preferences, decisions, agreements, names, tech stack.")
            appendLine("Example output:")
            appendLine("GOAL: Build a task manager app")
            appendLine("LANGUAGE: Kotlin")
            appendLine("DEADLINE: 2 weeks")
            appendLine()
            exchange.forEach { msg ->
                appendLine("${if (msg.role == "user") "User" else "Assistant"}: ${msg.content}")
            }
        }

        val result = api.ask(prompt, ctx.modelId, maxTokens = 256)

        // Parse KEY: VALUE lines
        val pairs = result.text.lines()
            .map { it.trim() }
            .filter { it.contains(":") && it.isNotBlank() }
            .mapNotNull { line ->
                val idx = line.indexOf(":")
                if (idx > 0) {
                    val k = line.substring(0, idx).trim().uppercase()
                        .replace(Regex("[^A-ZА-Я0-9_\\s]"), "").trim()
                    val v = line.substring(idx + 1).trim()
                    if (k.isNotBlank() && v.isNotBlank() && k.length <= 30) k to v else null
                } else null
            }

        // Upsert: update existing key or insert new
        val now = System.currentTimeMillis()
        pairs.forEach { (key, value) ->
            val updated = dao.updateFact(ctx.conversationId, key, value, now)
            if (updated == 0) {
                dao.insertFact(
                    FactEntity(
                        conversationId = ctx.conversationId,
                        key = key,
                        value = value,
                        createdAt = now,
                    )
                )
            }
        }

        return result
    }

    fun resetExtraction() {
        // Nothing to reset — extraction is stateless (always uses last exchange)
    }
}
