package com.cabgon.blackhawk.ai

import android.content.Context
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine


class Translator(ctx: Context) {
    private val esEn = Translation.getClient(
        TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.SPANISH)
            .setTargetLanguage(TranslateLanguage.ENGLISH).build()
    )
    private val enEs = Translation.getClient(
        TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.SPANISH).build()
    )
    suspend fun ensureModels() {
        esEn.downloadModelIfNeeded().await()
        enEs.downloadModelIfNeeded().await()
    }
    suspend fun esToEn(text: String) = esEn.translate(text).await()
    suspend fun enToEs(text: String) = enEs.translate(text).await()
}
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
suspend inline fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) {} }
        addOnFailureListener { cont.resumeWith(Result.failure(it)) }
    }