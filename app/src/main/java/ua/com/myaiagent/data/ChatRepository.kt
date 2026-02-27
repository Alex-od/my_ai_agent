package ua.com.myaiagent.data

import kotlinx.coroutines.flow.Flow
import ua.com.myaiagent.data.local.ChatDao
import ua.com.myaiagent.data.local.ConversationEntity
import ua.com.myaiagent.data.local.ConversationWithMessages
import ua.com.myaiagent.data.local.MessageEntity

class ChatRepository(private val dao: ChatDao) {

    val conversations: Flow<List<ConversationWithMessages>> = dao.getAllConversations()

    suspend fun getActiveSession(): ConversationWithMessages? = dao.getActiveConversation()

    suspend fun getOrCreateSession(model: String, systemPrompt: String?): Long {
        val existing = dao.getActiveConversation()
        if (existing != null) return existing.conversation.id
        val now = System.currentTimeMillis()
        val conversationId = dao.insertConversation(
            ConversationEntity(
                title = "Новый чат",
                model = model,
                createdAt = now,
                isActive = true,
            )
        )
        if (!systemPrompt.isNullOrBlank()) {
            dao.insertMessage(
                MessageEntity(
                    conversationId = conversationId,
                    role = "system",
                    content = systemPrompt,
                    timestamp = now,
                )
            )
        }
        return conversationId
    }

    suspend fun appendUserMessage(conversationId: Long, content: String) {
        dao.insertMessage(
            MessageEntity(
                conversationId = conversationId,
                role = "user",
                content = content,
                timestamp = System.currentTimeMillis(),
            )
        )
    }

    suspend fun appendAssistantMessage(conversationId: Long, content: String) {
        dao.insertMessage(
            MessageEntity(
                conversationId = conversationId,
                role = "assistant",
                content = content,
                timestamp = System.currentTimeMillis(),
            )
        )
    }

    suspend fun startNewChat() {
        dao.deactivateAllConversations()
    }

    suspend fun delete(conversationId: Long) {
        dao.deleteConversation(conversationId)
    }

    suspend fun getSummary(conversationId: Long): String? {
        val session = dao.getActiveConversation() ?: return null
        return if (session.conversation.id == conversationId) session.conversation.summary else null
    }

    suspend fun saveSummary(conversationId: Long, summary: String) {
        dao.updateSummary(conversationId, summary)
    }
}
