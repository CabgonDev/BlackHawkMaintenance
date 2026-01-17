package com.cabgon.blackhawk.data.chat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface ChatDao {

    // Sessions
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSessionEntity): Long

    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestSessionOrNull(): ChatSessionEntity?

    @Query("SELECT * FROM chat_sessions WHERE id = :id LIMIT 1")
    suspend fun getSession(id: Long): ChatSessionEntity?

    @Query("UPDATE chat_sessions SET updatedAt = :ts WHERE id = :id")
    suspend fun touchSession(id: Long, ts: Long = System.currentTimeMillis())

    // Messages
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity): Long

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getMessages(sessionId: Long): List<ChatMessageEntity>

    @Query(
        """
        SELECT * FROM chat_messages
        WHERE sessionId = :sessionId
        ORDER BY createdAt DESC
        LIMIT :limit
        """
    )
    suspend fun getLastMessages(sessionId: Long, limit: Int): List<ChatMessageEntity>

    // Tu método actual (lo dejamos por compatibilidad)
    @Query("UPDATE chat_messages SET content = :content WHERE id = :messageId")
    suspend fun updateMessageContent(messageId: Long, content: String)

    /**
     * NUEVO: versión NO suspend para usarla dentro del callback/flush streaming
     * (debe ejecutarse en background; aquí ya estás en Dispatchers.IO).
     */
    @Query("UPDATE chat_messages SET content = :content WHERE id = :messageId")
    fun updateMessageContentNow(messageId: Long, content: String)

    // Sources
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSources(sources: List<ChatSourceEntity>)

    @Query("SELECT * FROM chat_sources WHERE messageId = :messageId ORDER BY manual ASC, page ASC")
    suspend fun getSourcesForMessage(messageId: Long): List<ChatSourceEntity>

    @Transaction
    suspend fun insertAssistantMessageWithSources(
        sessionId: Long,
        content: String,
        sources: List<ChatSourceEntity>
    ): Long {
        val messageId = insertMessage(
            ChatMessageEntity(
                sessionId = sessionId,
                role = "assistant",
                content = content
            )
        )
        if (sources.isNotEmpty()) {
            insertSources(sources.map { it.copy(messageId = messageId) })
        }
        return messageId
    }
}
