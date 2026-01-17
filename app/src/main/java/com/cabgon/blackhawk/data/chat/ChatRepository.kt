package com.cabgon.blackhawk.data.chat

import com.cabgon.blackhawk.ai.chat.ChatTurn
import com.cabgon.blackhawk.ai.chat.OfflineRagEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ChatRepository(
    private val chatDao: ChatDao,
    private val ragEngine: OfflineRagEngine
) {

    suspend fun warmUpEngine() = withContext(Dispatchers.IO) {
        runCatching { ragEngine.warmUp() }
    }

    suspend fun getOrCreateSession(packageId: String): ChatSessionEntity = withContext(Dispatchers.IO) {
        val pkg = packageId.trim()
        val latest = chatDao.getLatestSessionOrNull()
        if (latest != null && latest.packageId == pkg) return@withContext latest

        val newId = chatDao.insertSession(
            ChatSessionEntity(
                title = "Consulta IA",
                packageId = pkg
            )
        )
        chatDao.getSession(newId)!!
    }

    suspend fun loadMessages(sessionId: Long): List<ChatMessageEntity> =
        withContext(Dispatchers.IO) { chatDao.getMessages(sessionId) }

    suspend fun loadSources(messageId: Long): List<ChatSourceEntity> =
        withContext(Dispatchers.IO) { chatDao.getSourcesForMessage(messageId) }

    suspend fun sendUserMessage(sessionId: Long, text: String): Long = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@withContext -1L

        chatDao.touchSession(sessionId)
        chatDao.insertMessage(
            ChatMessageEntity(
                sessionId = sessionId,
                role = "user",
                content = trimmed
            )
        )
    }

    suspend fun sendAssistantMessage(sessionId: Long, text: String): Long = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@withContext -1L

        chatDao.touchSession(sessionId)
        chatDao.insertMessage(
            ChatMessageEntity(
                sessionId = sessionId,
                role = "assistant",
                content = trimmed
            )
        )
    }

    /**
     * Genera respuesta del asistente con streaming.
     * - Inserta un mensaje placeholder en DB
     * - Va actualizando content con throttling
     * - Al final inserta fuentes reales
     */
    suspend fun generateAssistantAnswerStreaming(
        session: ChatSessionEntity,
        onChunk: (String) -> Unit
    ): Long = withContext(Dispatchers.IO) {

        val last = chatDao.getLastMessages(session.id, 12).reversed()

        val question = last.lastOrNull { it.role == "user" }?.content?.trim().orEmpty()
        if (question.isEmpty()) {
            return@withContext sendAssistantMessage(
                session.id,
                "Dime qué necesitas consultar y lo busco en los manuales locales."
            )
        }

        val history = last.map { ChatTurn(role = it.role, content = it.content) }

        // Placeholder en DB para ir actualizando
        val msgId = chatDao.insertMessage(
            ChatMessageEntity(
                sessionId = session.id,
                role = "assistant",
                content = ""
            )
        )

        val out = StringBuilder(4096)
        var lastFlush = 0L

        fun flushIfNeeded(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (force || now - lastFlush >= 250L) {
                lastFlush = now
                // CAMBIO: usar versión NO suspend para que no truene por coroutine body
                runCatching { chatDao.updateMessageContentNow(msgId, out.toString()) }
            }
        }

        val rag = ragEngine.answerStreaming(
            question = question,
            history = history,
            packageId = session.packageId
        ) { chunk ->
            if (chunk.isEmpty()) return@answerStreaming

            out.append(chunk)
            runCatching { onChunk(chunk) }
            flushIfNeeded(force = false)
        }

        val finalText = rag.answer
        if (finalText.isNotBlank()) {
            // Overwrite para consistencia (incluye refs)
            out.clear()
            out.append(finalText)
        }

        flushIfNeeded(force = true)

        val sources = rag.passages.map { p ->
            ChatSourceEntity(
                messageId = msgId,
                manual = p.manual,
                page = p.page,
                snippet = p.text.take(240)
            )
        }

        if (sources.isNotEmpty()) {
            runCatching { chatDao.insertSources(sources) }
        }

        chatDao.touchSession(session.id)
        msgId
    }

    suspend fun generateAssistantAnswer(session: ChatSessionEntity): Long = withContext(Dispatchers.IO) {
        generateAssistantAnswerStreaming(session) { /* no-op */ }
    }
}
