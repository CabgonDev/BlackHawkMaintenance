package com.cabgon.blackhawk.ui.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream

object GalleryThumbnailGenerator {

    private const val THUMB_SIZE = 320
    private const val DISPLAY_MAX_DIMENSION = 1600
    private const val JPEG_QUALITY = 82
    private const val DISPLAY_QUALITY = 88

    fun createImageThumb(
        context: Context,
        uri: Uri,
    ): ByteArray {
        val raw = decodeSampledBitmapFromUri(
            context = context,
            uri = uri,
            reqWidth = THUMB_SIZE,
            reqHeight = THUMB_SIZE
        )
        val scaled = scaleCenterCrop(raw, THUMB_SIZE, THUMB_SIZE)
        return scaled.toJpegBytes(JPEG_QUALITY)
    }

    fun createImageThumbFromBytes(imageBytes: ByteArray): ByteArray {
        val raw = decodeSampledBitmapFromBytes(
            bytes = imageBytes,
            reqWidth = THUMB_SIZE,
            reqHeight = THUMB_SIZE
        )
        val scaled = scaleCenterCrop(raw, THUMB_SIZE, THUMB_SIZE)
        return scaled.toJpegBytes(JPEG_QUALITY)
    }

    fun createImageDisplay(
        context: Context,
        uri: Uri,
    ): ByteArray {
        val raw = decodeSampledBitmapFromUri(
            context = context,
            uri = uri,
            reqWidth = DISPLAY_MAX_DIMENSION,
            reqHeight = DISPLAY_MAX_DIMENSION
        )
        val scaled = scaleToFit(raw, DISPLAY_MAX_DIMENSION, DISPLAY_MAX_DIMENSION)
        return scaled.toJpegBytes(DISPLAY_QUALITY)
    }

    fun createImageDisplayFromBytes(imageBytes: ByteArray): ByteArray {
        val raw = decodeSampledBitmapFromBytes(
            bytes = imageBytes,
            reqWidth = DISPLAY_MAX_DIMENSION,
            reqHeight = DISPLAY_MAX_DIMENSION
        )
        val scaled = scaleToFit(raw, DISPLAY_MAX_DIMENSION, DISPLAY_MAX_DIMENSION)
        return scaled.toJpegBytes(DISPLAY_QUALITY)
    }

    fun createVideoThumb(
        context: Context,
        uri: Uri,
    ): ByteArray {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            val frame = mmr.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: mmr.frameAtTime
                ?: throw IllegalStateException("No se pudo generar miniatura del video.")
            val scaled = scaleCenterCrop(frame, THUMB_SIZE, THUMB_SIZE)
            scaled.toJpegBytes(JPEG_QUALITY)
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun decodeSampledBitmapFromUri(
        context: Context,
        uri: Uri,
        reqWidth: Int,
        reqHeight: Int,
    ): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "No se pudo abrir la imagen." }
            BitmapFactory.decodeStream(input, null, bounds)
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds, reqWidth, reqHeight)
        }

        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "No se pudo abrir la imagen." }
            return BitmapFactory.decodeStream(input, null, options)
                ?: throw IllegalStateException("No se pudo decodificar la imagen.")
        }
    }

    private fun decodeSampledBitmapFromBytes(
        bytes: ByteArray,
        reqWidth: Int,
        reqHeight: Int,
    ): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds, reqWidth, reqHeight)
        }

        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalStateException("No se pudo decodificar la imagen.")
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int,
    ): Int {
        val rawHeight = options.outHeight.coerceAtLeast(1)
        val rawWidth = options.outWidth.coerceAtLeast(1)
        var inSampleSize = 1

        if (rawHeight > reqHeight || rawWidth > reqWidth) {
            var halfHeight = rawHeight / 2
            var halfWidth = rawWidth / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize.coerceAtLeast(1)
    }

    private fun scaleCenterCrop(src: Bitmap, width: Int, height: Int): Bitmap {
        val scale = maxOf(width / src.width.toFloat(), height / src.height.toFloat())
        val scaledWidth = (src.width * scale).toInt()
        val scaledHeight = (src.height * scale).toInt()
        val scaled = src.scale(scaledWidth, scaledHeight)

        val x = ((scaledWidth - width) / 2).coerceAtLeast(0)
        val y = ((scaledHeight - height) / 2).coerceAtLeast(0)

        return Bitmap.createBitmap(
            scaled,
            x,
            y,
            width.coerceAtMost(scaled.width),
            height.coerceAtMost(scaled.height)
        )
    }

    private fun scaleToFit(src: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        if (src.width <= maxWidth && src.height <= maxHeight) {
            return src
        }

        val widthRatio = maxWidth / src.width.toFloat()
        val heightRatio = maxHeight / src.height.toFloat()
        val scale = minOf(widthRatio, heightRatio)

        val outWidth = (src.width * scale).toInt().coerceAtLeast(1)
        val outHeight = (src.height * scale).toInt().coerceAtLeast(1)
        return src.scale(outWidth, outHeight)
    }

    private fun Bitmap.toJpegBytes(quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }
}
