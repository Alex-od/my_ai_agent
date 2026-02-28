package ua.com.myaiagent.data.context

import ua.com.myaiagent.UiMessage
import ua.com.myaiagent.data.ConversationMessage
import ua.com.myaiagent.data.local.BranchEntity
import ua.com.myaiagent.data.local.ChatDao

/**
 * Branching strategy: the user can "fork" the conversation at any point.
 * Each branch stores a snapshot of the context at the fork point.
 * The active branch's snapshot is prepended to the recent messages.
 */
class BranchingStrategy(
    private val dao: ChatDao,
    private val recentKeep: Int = 10,
) : ContextStrategy {

    override val type = StrategyType.BRANCHING
    override val description = "Ветки, окно $recentKeep сообщений"

    private var activeBranchId: Long? = null

    override suspend fun buildContext(
        allMessages: List<UiMessage>,
        ctx: StrategyContext,
    ): ContextResult {
        val branch = activeBranchId?.let { dao.getBranch(it) }

        val messages = mutableListOf<ConversationMessage>()
        var info: String

        if (branch != null) {
            // Include the branch snapshot as context preamble
            messages.add(
                ConversationMessage("system", "Context from branch \"${branch.name}\":\n${branch.snapshot}")
            )
            // Only show messages after the fork point
            val afterFork = allMessages.drop(branch.forkAtMessage)
            val recent = afterFork.takeLast(recentKeep)
            messages.addAll(recent.map { ConversationMessage(it.role, it.content) })
            info = "Ветка: ${branch.name}, от сообщ. #${branch.forkAtMessage}"
        } else {
            // No active branch — behave like sliding window
            val recent = allMessages.takeLast(recentKeep)
            messages.addAll(recent.map { ConversationMessage(it.role, it.content) })
            info = "Основная ветка, окно: $recentKeep"
        }

        return ContextResult(
            messages = messages,
            compressedCount = allMessages.size - messages.size,
            info = info,
        )
    }

    /** Create a new branch at the current message index. */
    suspend fun createBranch(
        conversationId: Long,
        name: String,
        allMessages: List<UiMessage>,
    ): BranchEntity {
        // Build a snapshot of the conversation up to this point
        val snapshot = allMessages.joinToString("\n") { msg ->
            "${if (msg.role == "user") "User" else "Assistant"}: ${msg.content}"
        }
        val entity = BranchEntity(
            conversationId = conversationId,
            name = name,
            forkAtMessage = allMessages.size,
            snapshot = snapshot,
            createdAt = System.currentTimeMillis(),
        )
        val id = dao.insertBranch(entity)
        activeBranchId = id
        return entity.copy(id = id)
    }

    /** Switch to an existing branch (or null to return to main). */
    fun switchBranch(branchId: Long?) {
        activeBranchId = branchId
    }

    fun getActiveBranchId(): Long? = activeBranchId

    /** Get all branches for a conversation. */
    suspend fun getBranches(conversationId: Long): List<BranchEntity> {
        return dao.getBranchesForConversation(conversationId)
    }

    fun resetBranch() {
        activeBranchId = null
    }
}
