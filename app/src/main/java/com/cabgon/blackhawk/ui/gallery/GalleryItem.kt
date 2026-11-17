package com.cabgon.blackhawk.ui.gallery

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot

data class GalleryItem(
    val id: String,
    val status: String,
    val mediaType: String,
    val storagePath: String,
    val thumbPath: String?,
    val displayPath: String?,
    val uploaderUid: String,
    val uploaderName: String,
    val uploaderEmail: String,
    val title: String,
    val description: String,
    val packageScope: String,
    val aircraftMatricula: String,
    val durationSec: Int,
    val sizeBytes: Long,
    val mimeType: String,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
    val approvedAt: Timestamp? = null,
    val approvedByUid: String? = null,
    val approvedByName: String? = null,
    val rejectedAt: Timestamp? = null,
    val rejectedByUid: String? = null,
    val rejectedByName: String? = null,
    val rejectReason: String? = null,
    val isDeleted: Boolean = false
) {
    companion object {
        const val STATUS_PENDING_MODERATOR = "pending_moderator"
        const val STATUS_PENDING_ADMIN = "pending_admin"
        const val STATUS_APPROVED = "approved"
        const val STATUS_REJECTED = "rejected"

        const val TYPE_IMAGE = "image"
        const val TYPE_VIDEO = "video"

        fun fromDocument(doc: DocumentSnapshot): GalleryItem {
            return GalleryItem(
                id = doc.id,
                status = doc.getString("status").orEmpty(),
                mediaType = doc.getString("mediaType").orEmpty(),
                storagePath = doc.getString("storagePath").orEmpty(),
                thumbPath = doc.getString("thumbPath"),
                displayPath = doc.getString("displayPath"),
                uploaderUid = doc.getString("uploaderUid").orEmpty(),
                uploaderName = doc.getString("uploaderName").orEmpty(),
                uploaderEmail = doc.getString("uploaderEmail").orEmpty(),
                title = doc.getString("title").orEmpty(),
                description = doc.getString("description").orEmpty(),
                packageScope = doc.getString("packageScope").orEmpty(),
                aircraftMatricula = doc.getString("aircraftMatricula").orEmpty(),
                durationSec = (doc.getLong("durationSec") ?: 0L).toInt(),
                sizeBytes = doc.getLong("sizeBytes") ?: 0L,
                mimeType = doc.getString("mimeType").orEmpty(),
                createdAt = doc.getTimestamp("createdAt"),
                updatedAt = doc.getTimestamp("updatedAt"),
                approvedAt = doc.getTimestamp("approvedAt"),
                approvedByUid = doc.getString("approvedByUid"),
                approvedByName = doc.getString("approvedByName"),
                rejectedAt = doc.getTimestamp("rejectedAt"),
                rejectedByUid = doc.getString("rejectedByUid"),
                rejectedByName = doc.getString("rejectedByName"),
                rejectReason = doc.getString("rejectReason"),
                isDeleted = doc.getBoolean("isDeleted") ?: false
            )
        }

        fun isPendingStatus(status: String): Boolean {
            return status == STATUS_PENDING_MODERATOR || status == STATUS_PENDING_ADMIN
        }
    }
}
