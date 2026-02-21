package ua.com.myaiagent.data

import kotlinx.coroutines.flow.Flow
import ua.com.myaiagent.data.local.ChatDao
import ua.com.myaiagent.data.local.ConversationEntity
import ua.com.myaiagent.data.local.ConversationWithMessages
import ua.com.myaiagent.data.local.MessageEntity

class ChatRepository(private val dao: ChatDao) {

    val conversations: Flow<List<ConversationWithMessages>> = dao.getAllConversations()

    suspend fun save(
        userPrompt: String,
        systemPrompt: String?,
        model: String,
        response: String,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        val title = userPrompt.take(40)
        val conversationId = dao.insertConversation(
            ConversationEntity(title = title, model = model, createdAt = timestamp)
        )
        val messages = buildList {
            if (!systemPrompt.isNullOrBlank()) {
                add(MessageEntity(conversationId = conversationId, role = "system", content = systemPrompt, timestamp = timestamp))
            }
            add(MessageEntity(conversationId = conversationId, role = "user", content = userPrompt, timestamp = timestamp))
            add(MessageEntity(conversationId = conversationId, role = "assistant", content = response, timestamp = timestamp))
        }
        dao.insertMessages(messages)
    }

    suspend fun delete(conversationId: Long) {
        dao.deleteConversation(conversationId)
    }
}
