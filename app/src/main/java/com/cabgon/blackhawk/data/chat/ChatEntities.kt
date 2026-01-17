package com.cabgon.blackhawk.data.chat

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chat_sessions",
    indices = [Index("createdAt")]
)
data class ChatSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val packageId: String, // "iads" o "sikorsky"
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId"), Index("createdAt")]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val role: String, // "user" | "assistant"
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "chat_sources",
    foreignKeys = [
        ForeignKey(
            entity = ChatMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("messageId"), Index("manual"), Index("page")]
)
data class ChatSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long,      // mensaje del asistente
    val manual: String,       // e.g. "UH-60L_IADS_XYZ.pdf"
    val page: Int,            // página citada
    val snippet: String       // texto breve mostrado en UI (opcional)
)
