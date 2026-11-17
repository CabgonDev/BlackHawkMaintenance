package com.cabgon.blackhawk.ai

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class Translator(ctx: Context) {

    // ✅ Evita warning de "ctx never used" y evita leaks: guardamos applicationContext
    private val appContext: Context = ctx.applicationContext

    private val esEn = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.SPANISH)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
    )

    private val enEs = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.SPANISH)
            .build()
    )

    suspend fun ensureModels() {
        esEn.downloadModelIfNeeded().await()
        enEs.downloadModelIfNeeded().await()
    }

    suspend fun esToEn(text: String): String = esEn.translate(text).await()
    suspend fun enToEs(text: String): String = enEs.translate(text).await()

    /**
     * (Opcional) Si algún día quieres cerrar explícitamente:
     * ML Kit Translator implementa Closeable en algunas versiones.
     * Lo dejamos seguro con runCatching.
     */
    fun close() {
        runCatching { esEn.close() }
        runCatching { enEs.close() }
        // appContext se mantiene por si lo quieres usar después
        @Suppress("UNUSED_VARIABLE")
        val _keep = appContext
    }
}

/**
 * Await para Tasks (Firebase/MLKit) sin @OptIn experimental.
 * Esto elimina el warning de "ExperimentalCoroutinesApi".
 */
suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result ->
        if (cont.isActive) cont.resume(result)
    }
    addOnFailureListener { e ->
        if (cont.isActive) cont.resumeWithException(e)
    }
}