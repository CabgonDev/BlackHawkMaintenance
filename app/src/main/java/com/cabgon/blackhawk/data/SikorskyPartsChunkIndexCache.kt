package com.cabgon.blackhawk.data

import android.content.Context

/**
 * Mantiene el índice abierto para evitar:
 * - copy/open del DB en cada búsqueda
 * - latencia de ~0.5–1s
 *
 * Esto es clave para "instantáneo".
 */
object SikorskyPartsChunkIndexCache {

    @Volatile private var instance: SikorskyPartsChunkIndex? = null
    private val lock = Any()

    fun get(context: Context): SikorskyPartsChunkIndex {
        val appCtx = context.applicationContext
        instance?.let { return it }

        synchronized(lock) {
            instance?.let { return it }
            val created = SikorskyPartsChunkIndex.open(appCtx)
            instance = created
            return created
        }
    }

    /**
     * Úsalo solo si tú decides cerrar explícitamente (no es obligatorio).
     */
    fun close() {
        synchronized(lock) {
            runCatching { instance?.close() }
            instance = null
        }
    }
}
