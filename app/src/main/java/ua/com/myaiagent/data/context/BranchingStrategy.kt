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
) : ContextStrategy {

    override val type = StrategyType.BRANCHING
    override val description = "Независимые ветки диалога"

    private var activeBranchId: Long? = null

    override suspend fun buildContext(
        allMessages: List<UiMessage>,
        ctx: StrategyContext,
    ): ContextResult {
        val branch = activeBranchId?.let { dao.getBranch(it) }

        val messages = mutableListOf<ConversationMessage>()
        val info: String

        if (branch != null) {
            messages.add(
                ConversationMessage("system", "Context from branch \"${branch.name}\":\n${branch.snapshot}")
            )
            val afterFork = allMessages.drop(branch.forkAtMessage)
            messages.addAll(afterFork.map { ConversationMessage(it.role, it.content) })
            info = "Ветка: ${branch.name}, сообщ. после форка: ${afterFork.size}"
        } else {
            messages.addAll(allMessages.map { ConversationMessage(it.role, it.content) })
            info = "Основная ветка, всего сообщ.: ${allMessages.size}"
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
