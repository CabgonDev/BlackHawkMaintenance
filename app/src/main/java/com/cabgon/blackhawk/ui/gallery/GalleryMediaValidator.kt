package com.cabgon.blackhawk.ui.gallery

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

object GalleryMediaValidator {

    private const val MAX_IMAGE_BYTES = 12L * 1024L * 1024L
    private const val MAX_VIDEO_BYTES = 40L * 1024L * 1024L
    private const val MAX_VIDEO_SECONDS = 30

    data class PreparedUpload(
        val mediaType: String,
        val mimeType: String,
        val sizeBytes: Long,
        val durationSec: Int,
        val extension: String,
        val uri: Uri? = null,
        val imageBytes: ByteArray? = null,
        val thumbBytes: ByteArray? = null,
        val displayName: String = ""
    )

    fun prepareImageFromUri(context: Context, uri: Uri): PreparedUpload {
        val resolver = context.contentResolver
        val mime = (resolver.getType(uri) ?: "").lowercase(Locale.US)
        require(mime.startsWith("image/")) { "Solo se permiten imágenes." }

        val size = querySize(resolver, uri)
        if (size > 0L) {
            require(size <= MAX_IMAGE_BYTES) { "La imagen excede el límite permitido (12 MB)." }
        }

        val ext = extensionFromMime(mime, defaultExt = "jpg")
        val thumbBytes = GalleryThumbnailGenerator.createImageThumb(context, uri)

        return PreparedUpload(
            mediaType = GalleryItem.TYPE_IMAGE,
            mimeType = mime.ifBlank { "image/jpeg" },
            sizeBytes = size,
            durationSec = 0,
            extension = ext,
            uri = uri,
            imageBytes = null,
            thumbBytes = thumbBytes,
            displayName = queryDisplayName(resolver, uri)
        )
    }

    fun prepareImageFromBitmap(bitmap: Bitmap): PreparedUpload {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        val bytes = out.toByteArray()

        require(bytes.size.toLong() <= MAX_IMAGE_BYTES) {
            "La imagen capturada excede el límite permitido (12 MB)."
        }

        val thumbBytes = GalleryThumbnailGenerator.createImageThumbFromBytes(bytes)

        return PreparedUpload(
            mediaType = GalleryItem.TYPE_IMAGE,
            mimeType = "image/jpeg",
            sizeBytes = bytes.size.toLong(),
            durationSec = 0,
            extension = "jpg",
            uri = null,
            imageBytes = bytes,
            thumbBytes = thumbBytes,
            displayName = "captura_${System.currentTimeMillis()}.jpg"
        )
    }

    suspend fun prepareVideoFromUri(
        context: Context,
        uri: Uri,
        onProgressText: (String) -> Unit = {}
    ): PreparedUpload {
        val resolver = context.contentResolver
        val mime = (resolver.getType(uri) ?: "").lowercase(Locale.US)
        require(mime.startsWith("video/")) { "Solo se permiten videos." }

        val durationSec = readDurationSeconds(context, uri)
        require(durationSec in 1..MAX_VIDEO_SECONDS) {
            "El video debe durar máximo 30 segundos."
        }

        val originalSize = querySize(resolver, uri)
        val displayName = queryDisplayName(resolver, uri)

        val finalUri: Uri
        val finalSize: Long

        if (originalSize in 1..MAX_VIDEO_BYTES) {
            finalUri = uri
            finalSize = originalSize
        } else {
            onProgressText("Optimizando video…")
            val compressed = GalleryVideoCompressor.compressToFit(
                context = context,
                sourceUri = uri,
                displayName = displayName,
                onProgress = onProgressText
            )

            require(compressed.bytes <= MAX_VIDEO_BYTES) {
                "El video excede el límite permitido (40 MB)."
            }

            finalUri = Uri.fromFile(compressed.file)
            finalSize = compressed.bytes
        }

        val thumbBytes = GalleryThumbnailGenerator.createVideoThumb(context, finalUri)

        return PreparedUpload(
            mediaType = GalleryItem.TYPE_VIDEO,
            mimeType = "video/mp4",
            sizeBytes = finalSize,
            durationSec = durationSec,
            extension = "mp4",
            uri = finalUri,
            imageBytes = null,
            thumbBytes = thumbBytes,
            displayName = displayName
        )
    }

    private fun readDurationSeconds(context: Context, uri: Uri): Int {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            val millis = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            ((millis + 999L) / 1000L).toInt()
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String {
        if (uri.scheme == "file") {
            return File(requireNotNull(uri.path)).name
        }

        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                return c.getString(0).orEmpty()
            }
        }

        return uri.lastPathSegment.orEmpty()
    }

    private fun querySize(resolver: ContentResolver, uri: Uri): Long {
        if (uri.scheme == "file") {
            return runCatching { File(requireNotNull(uri.path)).length() }.getOrDefault(0L)
        }

        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                return c.getLong(0)
            }
        }

        return try {
            resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                afd.length.coerceAtLeast(0L)
            } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun extensionFromMime(mime: String, defaultExt: String): String {
        return when {
            mime.endsWith("jpeg") || mime.endsWith("jpg") -> "jpg"
            mime.endsWith("png") -> "png"
            mime.endsWith("webp") -> "webp"
            mime.endsWith("mp4") -> "mp4"
            mime.endsWith("quicktime") -> "mov"
            else -> defaultExt
        }
    }
}