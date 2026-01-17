package com.cabgon.blackhawk.ai.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

class LocalLlmClient(
    private val config: LlmConfig
) {

    @Volatile
    private var initialized = false

    private val exec = Executors.newSingleThreadExecutor()

    @Synchronized
    private fun initBlocking(): Boolean {
        if (initialized) return true
        val ok = LlamaBridge.init(
            modelPath = config.modelPath,
            nCtx = config.nCtx,
            nThreads = config.nThreads
        )
        initialized = ok
        return ok
    }

    suspend fun warmUp(): Boolean = withContext(Dispatchers.IO) { initBlocking() }

    /**
     * Generación bloqueante (compatibilidad con flujo anterior).
     */
    suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        if (!initBlocking()) return@withContext "ERROR: No se pudo inicializar el modelo IA local."

        val future = exec.submit(Callable {
            LlamaBridge.generate(
                prompt = prompt,
                maxTokens = config.maxTokens,
                temperature = config.temperature,
                topP = config.topP
            )
        })

        try {
            future.get(config.timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            runCatching { LlamaBridge.cancel() }
            future.cancel(true)
            ""
        }
    }

    /**
     * Generación con streaming por chunks.
     *
     * - onChunk se invoca desde un hilo background.
     * - Regresa el texto completo acumulado.
     */
    suspend fun generateStream(
        prompt: String,
        onChunk: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        if (!initBlocking()) return@withContext "ERROR: No se pudo inicializar el modelo IA local."

        val lastError = AtomicReference<String?>(null)
        val out = StringBuilder(2048)

        val future = exec.submit(Callable {
            LlamaBridge.generateStream(
                prompt = prompt,
                maxTokens = config.maxTokens,
                temperature = config.temperature,
                topP = config.topP,
                callback = object : LlamaBridge.TokenCallback {
                    override fun onToken(textChunk: String) {
                        if (textChunk.isEmpty()) return
                        out.append(textChunk)
                        runCatching { onChunk(textChunk) }
                    }

                    override fun onDone() {
                        // no-op
                    }

                    override fun onError(message: String) {
                        lastError.set(message)
                    }
                }
            )
            true
        })

        try {
            future.get(config.timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            runCatching { LlamaBridge.cancel() }
            future.cancel(true)
            ""
        }

        val err = lastError.get()
        if (!err.isNullOrBlank()) {
            val prefix = "ERROR: "
            if (out.isEmpty()) return@withContext (prefix + err)
        }

        out.toString()
    }

    fun release() {
        if (initialized) {
            runCatching { LlamaBridge.release() }
            initialized = false
        }
        exec.shutdownNow()
    }
}
