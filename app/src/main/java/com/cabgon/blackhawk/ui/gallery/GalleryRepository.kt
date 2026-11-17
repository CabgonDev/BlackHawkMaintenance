package com.cabgon.blackhawk.ui.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import com.cabgon.blackhawk.ai.await
import com.cabgon.blackhawk.data.user.UserProfile
import com.cabgon.blackhawk.util.Roles
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class GalleryRepository {

    private val db by lazy { FirebaseFirestore.getInstance() }
    private val storage by lazy { FirebaseStorage.getInstance() }

    fun listenApproved(
        limit: Long = 30,
        onResult: (List<GalleryItem>) -> Unit,
        onError: (Throwable) -> Unit
    ): ListenerRegistration {
        return db.collection(COLLECTION)
            .whereEqualTo("status", GalleryItem.STATUS_APPROVED)
            .whereEqualTo("isDeleted", false)
            .orderBy("approvedAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(limit)
            .addSnapshotListener { snap, e ->
                if (e != null) {
                    onError(e)
                    return@addSnapshotListener
                }
                val items = snap?.documents.orEmpty().map(GalleryItem::fromDocument)
                onResult(items)
            }
    }

    fun listenByStatus(
        status: String,
        limit: Long = 50,
        onResult: (List<GalleryItem>) -> Unit,
        onError: (Throwable) -> Unit
    ): ListenerRegistration {
        return db.collection(COLLECTION)
            .whereEqualTo("status", status)
            .whereEqualTo("isDeleted", false)
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(limit)
            .addSnapshotListener { snap, e ->
                if (e != null) {
                    onError(e)
                    return@addSnapshotListener
                }
                val items = snap?.documents.orEmpty().map(GalleryItem::fromDocument)
                onResult(items)
            }
    }

    suspend fun uploadPending(
        context: Context,
        prepared: GalleryMediaValidator.PreparedUpload,
        title: String,
        description: String,
        aircraftMatricula: String,
        uploader: UserProfile
    ): UploadResult = withContext(Dispatchers.IO) {
        val postId = db.collection(COLLECTION).document().id
        val ext = prepared.extension.ifBlank {
            if (prepared.mediaType == GalleryItem.TYPE_VIDEO) "mp4" else "jpg"
        }

        val storagePath = "gallery/originals/$postId.$ext"
        val thumbPath = "gallery/thumbs/$postId.jpg"
        val displayPath = if (prepared.mediaType == GalleryItem.TYPE_IMAGE) {
            "gallery/display/$postId.jpg"
        } else {
            null
        }

        val initialStatus = when {
            Roles.isAtLeast(uploader.role, Roles.ADMIN) -> GalleryItem.STATUS_APPROVED
            Roles.isAtLeast(uploader.role, Roles.MODERATOR) -> GalleryItem.STATUS_PENDING_ADMIN
            else -> GalleryItem.STATUS_PENDING_MODERATOR
        }

        val uploaderDisplayName = listOf(
            uploader.grado,
            uploader.especialidad,
            uploader.nombre
        ).filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()

        val thumbBytes = when (prepared.mediaType) {
            GalleryItem.TYPE_IMAGE -> {
                prepared.imageBytes?.let { GalleryThumbnailGenerator.createImageThumbFromBytes(it) }
                    ?: GalleryThumbnailGenerator.createImageThumb(
                        context = context,
                        uri = requireNotNull(prepared.uri) { "uri requerida para miniatura de imagen" }
                    )
            }

            GalleryItem.TYPE_VIDEO -> GalleryThumbnailGenerator.createVideoThumb(
                context = context,
                uri = requireNotNull(prepared.uri) { "uri requerida para miniatura de video" }
            )

            else -> throw IllegalStateException("Tipo de medio no soportado.")
        }

        val displayBytes = when (prepared.mediaType) {
            GalleryItem.TYPE_IMAGE -> {
                prepared.imageBytes?.let { GalleryThumbnailGenerator.createImageDisplayFromBytes(it) }
                    ?: GalleryThumbnailGenerator.createImageDisplay(
                        context = context,
                        uri = requireNotNull(prepared.uri) { "uri requerida para versión de visualización" }
                    )
            }

            else -> null
        }

        val initial = hashMapOf(
            "status" to initialStatus,
            "mediaType" to prepared.mediaType,
            "storagePath" to storagePath,
            "thumbPath" to thumbPath,
            "displayPath" to displayPath,
            "uploaderUid" to uploader.uid,
            "uploaderName" to uploaderDisplayName,
            "uploaderEmail" to uploader.email,
            "title" to title.trim(),
            "description" to description.trim(),
            "packageScope" to "",
            "aircraftMatricula" to aircraftMatricula.trim(),
            "durationSec" to prepared.durationSec,
            "sizeBytes" to prepared.sizeBytes,
            "mimeType" to prepared.mimeType,
            "isDeleted" to false,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
            "approvedAt" to if (initialStatus == GalleryItem.STATUS_APPROVED) FieldValue.serverTimestamp() else null,
            "approvedByUid" to if (initialStatus == GalleryItem.STATUS_APPROVED) uploader.uid else null,
            "approvedByName" to if (initialStatus == GalleryItem.STATUS_APPROVED) uploaderDisplayName else null,
            "rejectedAt" to null,
            "rejectedByUid" to null,
            "rejectedByName" to null,
            "rejectReason" to null
        )

        db.collection(COLLECTION).document(postId).set(initial).await()

        val originalRef = storage.reference.child(storagePath)
        if (prepared.imageBytes != null) {
            originalRef.putBytes(prepared.imageBytes).await()
        } else {
            val uri = requireNotNull(prepared.uri) { "uri requerida" }
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "No se pudo abrir el archivo seleccionado." }
                originalRef.putStream(input).await()
            }
        }

        val thumbRef = storage.reference.child(thumbPath)
        thumbRef.putBytes(thumbBytes).await()

        if (!displayPath.isNullOrBlank() && displayBytes != null) {
            val displayRef = storage.reference.child(displayPath)
            displayRef.putBytes(displayBytes).await()
        }

        db.collection(COLLECTION).document(postId).update(
            mapOf(
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()

        UploadResult(postId = postId, status = initialStatus)
    }

    suspend fun approve(item: GalleryItem, reviewer: UserProfile) = withContext(Dispatchers.IO) {
        val reviewerName = listOf(
            reviewer.grado,
            reviewer.especialidad,
            reviewer.nombre
        ).filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()

        val nextStatus = when {
            item.status == GalleryItem.STATUS_PENDING_MODERATOR && Roles.isAtLeast(reviewer.role, Roles.ADMIN) -> GalleryItem.STATUS_APPROVED
            item.status == GalleryItem.STATUS_PENDING_MODERATOR && Roles.isAtLeast(reviewer.role, Roles.MODERATOR) -> GalleryItem.STATUS_PENDING_ADMIN
            item.status == GalleryItem.STATUS_PENDING_ADMIN && Roles.isAtLeast(reviewer.role, Roles.ADMIN) -> GalleryItem.STATUS_APPROVED
            else -> throw IllegalStateException("Tu rol no puede aprobar este contenido en este nivel.")
        }

        val update = linkedMapOf<String, Any?>(
            "status" to nextStatus,
            "updatedAt" to FieldValue.serverTimestamp(),
            "rejectedAt" to null,
            "rejectedByUid" to null,
            "rejectedByName" to null,
            "rejectReason" to null
        )

        if (nextStatus == GalleryItem.STATUS_APPROVED) {
            update["approvedAt"] = FieldValue.serverTimestamp()
            update["approvedByUid"] = reviewer.uid
            update["approvedByName"] = reviewerName
        } else {
            update["approvedAt"] = null
            update["approvedByUid"] = null
            update["approvedByName"] = null
        }

        db.collection(COLLECTION).document(item.id).update(update).await()
    }

    suspend fun reject(item: GalleryItem, moderatorUid: String, moderatorName: String, reason: String) =
        withContext(Dispatchers.IO) {
            db.collection(COLLECTION).document(item.id).update(
                mapOf(
                    "status" to GalleryItem.STATUS_REJECTED,
                    "rejectedAt" to FieldValue.serverTimestamp(),
                    "rejectedByUid" to moderatorUid,
                    "rejectedByName" to moderatorName.trim(),
                    "rejectReason" to reason.trim(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }

    suspend fun delete(item: GalleryItem) = withContext(Dispatchers.IO) {
        runCatching {
            if (item.storagePath.isNotBlank()) {
                storage.reference.child(item.storagePath).delete().await()
            }
        }
        runCatching {
            if (!item.thumbPath.isNullOrBlank()) {
                storage.reference.child(item.thumbPath).delete().await()
            }
        }
        runCatching {
            if (!item.displayPath.isNullOrBlank()) {
                storage.reference.child(item.displayPath).delete().await()
            }
        }
        db.collection(COLLECTION).document(item.id).delete().await()
    }

    suspend fun resolveDownloadUrl(path: String): String = withContext(Dispatchers.IO) {
        storage.reference.child(path).downloadUrl.await().toString()
    }

    suspend fun loadPreviewInto(imageView: ImageView, item: GalleryItem) = withContext(Dispatchers.IO) {
        val previewPath = item.thumbPath?.takeIf { it.isNotBlank() } ?: item.storagePath
        val maxBytes = if (item.thumbPath.isNullOrBlank() && item.mediaType == GalleryItem.TYPE_IMAGE) {
            512L * 1024L
        } else {
            256L * 1024L
        }

        val bytes = storage.reference.child(previewPath).getBytes(maxBytes).await()
        val reqWidth = imageView.width.takeIf { it > 0 } ?: 320
        val reqHeight = imageView.height.takeIf { it > 0 } ?: 320
        val bitmap = decodeSampledByteArray(bytes, reqWidth, reqHeight) ?: return@withContext

        withContext(Dispatchers.Main) {
            if (imageView.tag == item.id) {
                imageView.setImageBitmap(bitmap)
            }
        }
    }

    suspend fun loadViewerImageInto(context: Context, imageView: ImageView, item: GalleryItem) = withContext(Dispatchers.IO) {
        val reqWidth = imageView.width.takeIf { it > 0 }
            ?: imageView.resources.displayMetrics.widthPixels
        val reqHeight = imageView.height.takeIf { it > 0 }
            ?: imageView.resources.displayMetrics.heightPixels

        val bitmap = if (!item.displayPath.isNullOrBlank()) {
            val bytes = storage.reference.child(item.displayPath).getBytes(4L * 1024L * 1024L).await()
            decodeSampledByteArray(bytes, reqWidth, reqHeight)
        } else {
            val tmp = File.createTempFile("gallery_view_", ".img", context.cacheDir)
            storage.reference.child(item.storagePath).getFile(tmp).await()
            try {
                decodeSampledFile(tmp.absolutePath, reqWidth, reqHeight)
            } finally {
                runCatching { tmp.delete() }
            }
        } ?: throw IllegalStateException("No se pudo decodificar la imagen.")

        withContext(Dispatchers.Main) {
            if (imageView.tag == item.id) {
                imageView.setImageBitmap(bitmap)
            }
        }
    }

    private fun decodeSampledByteArray(bytes: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds, reqWidth, reqHeight)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun decodeSampledFile(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds, reqWidth, reqHeight)
        }
        return BitmapFactory.decodeFile(path, options)
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
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

    data class UploadResult(
        val postId: String,
        val status: String
    )

    companion object {
        const val COLLECTION = "gallery_posts"
    }
}
