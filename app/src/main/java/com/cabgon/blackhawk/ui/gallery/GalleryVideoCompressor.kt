package com.cabgon.blackhawk.ui.gallery

import android.content.Context
import android.net.Uri
import com.abedelazizshe.lightcompressorlibrary.CompressionListener
import com.abedelazizshe.lightcompressorlibrary.VideoCompressor
import com.abedelazizshe.lightcompressorlibrary.VideoQuality
import com.abedelazizshe.lightcompressorlibrary.config.AppSpecificStorageConfiguration
import com.abedelazizshe.lightcompressorlibrary.config.Configuration
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object GalleryVideoCompressor {

    private const val TARGET_MAX_BYTES = 40L * 1024L * 1024L

    data class CompressionOutput(
        val file: File,
        val bytes: Long
    )

    suspend fun compressToFit(
        context: Context,
        sourceUri: Uri,
        displayName: String,
        onProgress: (String) -> Unit = {}
    ): CompressionOutput {
        val lowResult = compressOnce(
            context = context,
            sourceUri = sourceUri,
            displayName = displayName,
            suffix = "medium",
            quality = VideoQuality.MEDIUM,
            onProgress = { percent -> onProgress("Optimizando video… ${percent.toInt()}%") }
        )
        if (lowResult.bytes in 1..TARGET_MAX_BYTES) return lowResult

        runCatching { lowResult.file.delete() }

        val veryLowResult = compressOnce(
            context = context,
            sourceUri = sourceUri,
            displayName = displayName,
            suffix = "low",
            quality = VideoQuality.LOW,
            onProgress = { percent -> onProgress("Reduciendo aún más… ${percent.toInt()}%") }
        )
        if (veryLowResult.bytes in 1..TARGET_MAX_BYTES) return veryLowResult

        runCatching { veryLowResult.file.delete() }
        throw IllegalStateException("El video sigue excediendo 40 MB incluso después de optimizarlo.")
    }

    private suspend fun compressOnce(
        context: Context,
        sourceUri: Uri,
        displayName: String,
        suffix: String,
        quality: VideoQuality,
        onProgress: (Float) -> Unit
    ): CompressionOutput = suspendCancellableCoroutine { cont ->

        val baseName = buildOutputName(displayName, suffix)

        VideoCompressor.start(
            context = context.applicationContext,
            uris = listOf(sourceUri),
            isStreamable = false,
            storageConfiguration = AppSpecificStorageConfiguration(
                subFolderName = "gallery_video_compressed"
            ),
            configureWith = Configuration(
                videoNames = listOf(baseName),
                quality = quality,
                isMinBitrateCheckEnabled = false
            ),
            listener = object : CompressionListener {

                override fun onStart(index: Int) = Unit

                override fun onProgress(index: Int, percent: Float) {
                    if (index == 0) onProgress(percent)
                }

                override fun onSuccess(index: Int, size: Long, path: String?) {
                    if (index != 0) return
                    val finalPath = path?.takeIf { it.isNotBlank() }
                        ?: return if (cont.isActive) {
                            cont.resumeWithException(
                                IllegalStateException("La compresión terminó sin devolver ruta de salida.")
                            )
                        } else Unit

                    val outFile = File(finalPath)
                    val finalSize = if (size > 0L) size else outFile.length()

                    if (!outFile.exists() || finalSize <= 0L) {
                        if (cont.isActive) {
                            cont.resumeWithException(
                                IllegalStateException("No se pudo generar el video optimizado.")
                            )
                        }
                        return
                    }

                    if (cont.isActive) {
                        cont.resume(CompressionOutput(outFile, finalSize))
                    }
                }

                override fun onFailure(index: Int, failureMessage: String) {
                    if (index != 0) return
                    if (cont.isActive) {
                        cont.resumeWithException(
                            IllegalStateException(
                                failureMessage.ifBlank { "No se pudo optimizar el video." }
                            )
                        )
                    }
                }

                override fun onCancelled(index: Int) {
                    if (index != 0) return
                    if (cont.isActive) {
                        cont.resumeWithException(
                            IllegalStateException("La optimización del video fue cancelada.")
                        )
                    }
                }
            }
        )

        cont.invokeOnCancellation {
            runCatching { VideoCompressor.cancel() }
        }
    }

    private fun buildOutputName(displayName: String, suffix: String): String {
        val raw = displayName.substringBeforeLast('.', displayName)
            .ifBlank { "gallery_video_${System.currentTimeMillis()}" }

        val sanitized = raw
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_')
            .ifBlank { "gallery_video_${System.currentTimeMillis()}" }

        return "${sanitized}_$suffix.mp4"
    }
}