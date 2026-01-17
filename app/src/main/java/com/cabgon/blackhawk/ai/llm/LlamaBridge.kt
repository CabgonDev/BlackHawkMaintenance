package com.cabgon.blackhawk.ai.llm

object LlamaBridge {
    init { System.loadLibrary("llama_bridge") }

    /**
     * Callback para streaming de salida.
     *
     * Importante:
     * - Se invoca desde un hilo nativo/background.
     * - Si vas a tocar UI, cambia al Main dispatcher.
     */
    interface TokenCallback {
        fun onToken(textChunk: String)
        fun onDone()
        fun onError(message: String)
    }

    external fun init(modelPath: String, nCtx: Int, nThreads: Int): Boolean

    /** Generación bloqueante (compatibilidad). */
    external fun generate(prompt: String, maxTokens: Int, temperature: Float, topP: Float): String

    /**
     * Generación con streaming por chunks.
     * El callback recibe fragmentos (no necesariamente token-by-token).
     */
    external fun generateStream(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        callback: TokenCallback
    )

    external fun cancel()
    external fun release()
}
