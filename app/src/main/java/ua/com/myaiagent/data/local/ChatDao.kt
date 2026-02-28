package ua.com.myaiagent.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Insert
    suspend fun insertConversation(conversation: ConversationEntity): Long

    @Insert
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Insert
    suspend fun insertMessage(message: MessageEntity)

    @Transaction
    @Query("SELECT * FROM conversations ORDER BY createdAt DESC")
    fun getAllConversations(): Flow<List<ConversationWithMessages>>

    @Transaction
    @Query("SELECT * FROM conversations WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveConversation(): ConversationWithMessages?

    @Query("UPDATE conversations SET isActive = 0")
    suspend fun deactivateAllConversations()

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: Long)

    @Query("UPDATE conversations SET summary = :summary WHERE id = :id")
    suspend fun updateSummary(id: Long, summary: String)

    // ── Facts ────────────────────────────────────────────────────────────────

    @Insert
    suspend fun insertFact(fact: FactEntity): Long

    @Query("""
        UPDATE facts SET value = :value, createdAt = :createdAt
        WHERE conversationId = :conversationId AND key = :key
    """)
    suspend fun updateFact(conversationId: Long, key: String, value: String, createdAt: Long): Int

    @Query("SELECT * FROM facts WHERE conversationId = :conversationId ORDER BY key ASC")
    suspend fun getFactsForConversation(conversationId: Long): List<FactEntity>

    @Query("DELETE FROM facts WHERE conversationId = :conversationId")
    suspend fun deleteFactsForConversation(conversationId: Long)

    // ── Branches ─────────────────────────────────────────────────────────────

    @Insert
    suspend fun insertBranch(branch: BranchEntity): Long

    @Query("SELECT * FROM branches WHERE id = :id")
    suspend fun getBranch(id: Long): BranchEntity?

    @Query("SELECT * FROM branches WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun getBranchesForConversation(conversationId: Long): List<BranchEntity>

    @Query("DELETE FROM branches WHERE id = :id")
    suspend fun deleteBranch(id: Long)
}
