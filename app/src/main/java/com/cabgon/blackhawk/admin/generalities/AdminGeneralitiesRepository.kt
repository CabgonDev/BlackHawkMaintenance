package com.cabgon.blackhawk.admin.generalities

import android.util.Log
import com.cabgon.blackhawk.ai.await
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AdminGeneralitiesRepository {

    private val db = FirebaseFirestore.getInstance()
    private var reg: ListenerRegistration? = null

    private val draftCol = db.collection("content_admin")
        .document("generalities_draft")
        .collection("sections")

    fun close() {
        reg?.remove()
        reg = null
    }

    fun observeDraft(onUpdate: (List<AdminGeneralitySectionDoc>) -> Unit) {
        reg?.remove()
        reg = draftCol.addSnapshotListener { snap, err ->
            if (err != null) {
                Log.w(TAG, "observeDraft snapshot error: ${err.message}", err)
                onUpdate(emptyList())
                return@addSnapshotListener
            }
            val list = snap?.documents?.mapNotNull { AdminGeneralitySectionDoc.fromSnapshot(it) } ?: emptyList()
            onUpdate(
                list.sortedWith(
                    compareBy<AdminGeneralitySectionDoc>({ it.isDeleted }, { it.order }, { it.title.lowercase() })
                )
            )
        }
    }

    suspend fun fetchDraftOnce(): List<AdminGeneralitySectionDoc> = withContext(Dispatchers.IO) {
        runCatching {
            val snap = draftCol.get().await()
            snap.documents.mapNotNull { AdminGeneralitySectionDoc.fromSnapshot(it) }
                .sortedWith(compareBy({ it.isDeleted }, { it.order }, { it.title.lowercase() }))
        }.getOrElse {
            Log.w(TAG, "fetchDraftOnce failed: ${it.message}", it)
            emptyList()
        }
    }

    suspend fun createSection(doc: AdminGeneralitySectionDoc): Boolean = withContext(Dispatchers.IO) {
        val payload = doc.copy(updatedAt = System.currentTimeMillis(), isDeleted = false).toMap()
        Log.d(TAG, "createSection: title='${doc.title}' order=${doc.order} cols=${doc.columns.size} rows=${doc.rows.size}")

        runCatching {
            val ref = draftCol.add(payload).await()
            Log.d(TAG, "createSection OK id=${ref.id}")
            true
        }.getOrElse {
            Log.w(TAG, "createSection FAILED: ${it.message}", it)
            false
        }
    }

    suspend fun updateSection(doc: AdminGeneralitySectionDoc): Boolean = withContext(Dispatchers.IO) {
        if (doc.id.isBlank()) {
            Log.w(TAG, "updateSection FAILED: blank id")
            return@withContext false
        }
        val payload = doc.copy(updatedAt = System.currentTimeMillis()).toMap()
        Log.d(TAG, "updateSection: id=${doc.id} title='${doc.title}' order=${doc.order} cols=${doc.columns.size} rows=${doc.rows.size}")

        runCatching {
            draftCol.document(doc.id).set(payload).await()
            Log.d(TAG, "updateSection OK id=${doc.id}")
            true
        }.getOrElse {
            Log.w(TAG, "updateSection FAILED id=${doc.id}: ${it.message}", it)
            false
        }
    }

    suspend fun setDeleted(id: String, isDeleted: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (id.isBlank()) {
            Log.w(TAG, "setDeleted FAILED: blank id")
            return@withContext false
        }

        val payload = mapOf(
            "isDeleted" to isDeleted,
            "updatedAt" to System.currentTimeMillis()
        )

        Log.d(TAG, "setDeleted: id=$id isDeleted=$isDeleted")

        runCatching {
            draftCol.document(id).update(payload).await()
            Log.d(TAG, "setDeleted OK id=$id")
            true
        }.getOrElse {
            Log.w(TAG, "setDeleted FAILED id=$id: ${it.message}", it)
            false
        }
    }


    companion object {
        private const val TAG = "AdminGenDraft"
    }
}
