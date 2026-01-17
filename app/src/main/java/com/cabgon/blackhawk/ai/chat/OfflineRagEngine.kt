package com.cabgon.blackhawk.ai.chat

interface OfflineRagEngine {
    suspend fun answer(
        question: String,
        history: List<ChatTurn>,
        packageId: String
    ): RagAnswer

    /**
     * Streaming opcional.
     * - onChunk recibe fragmentos para actualizar UI en vivo.
     * - Por defecto, cae a answer() y emite el answer completo en un solo chunk.
     */
    suspend fun answerStreaming(
        question: String,
        history: List<ChatTurn>,
        packageId: String,
        onChunk: (String) -> Unit
    ): RagAnswer {
        val res = answer(question, history, packageId)
        if (res.answer.isNotBlank()) onChunk(res.answer)
        return res
    }

    /**
     * Hook opcional para precargar recursos (p.ej. inicializar LLM).
     * Implementación por defecto: no-op.
     */
    suspend fun warmUp() { /* no-op */ }
}
