package com.cabgon.blackhawk.admin.generalities

import android.content.Context
import android.util.Log
import com.cabgon.blackhawk.ai.await
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

class AdminGeneralitiesPublishService {

    data class Result(val ok: Boolean, val message: String)

    private val db = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private val draftCol = db.collection("content_admin")
        .document("generalities_draft")
        .collection("sections")

    private val locksCol = db.collection("content_admin")
        .document("locks_root")
        .collection("locks")

    private val auditCol = db.collection("content_admin")
        .document("audit_root")
        .collection("audit_logs")

    /**
     * Publica a STABLE:
     * - lock
     * - lee draft activos
     * - genera JSON (rows como array-of-arrays)
     * - sha/bytes
     * - sube stable + release versionado
     * - actualiza content_ota/channels stable.generalities
     * - escribe audit
     */
    suspend fun publishStable(ctx: Context): Result = withContext(Dispatchers.IO) {
        val uid = auth.currentUser?.uid ?: return@withContext Result(false, "No autenticado.")
        val lockId = "generalities_publish_stable"

        try {
            // 1) Acquire lock
            val locked = acquireLock(lockId, uid)
            if (!locked) return@withContext Result(false, "Lock activo. Intenta más tarde (otro publish en curso).")

            // 2) Read current version
            val channelsRef = db.collection("content_ota").document("channels")
            val channelsSnap = channelsRef.get().await()
            val stable = channelsSnap.get("stable") as? Map<*, *> ?: emptyMap<String, Any>()
            val gen = stable["generalities"] as? Map<*, *>

            val currentVersion = (gen?.get("version") as? Number)?.toLong() ?: 0L
            val nextVersion = currentVersion + 1L

            val stablePath = "content/stable/generalities/generalities.json"
            val releasePath = "content/stable/generalities/releases/generalities_v$nextVersion.json"

            // 3) Load draft active
            val draftSnap = draftCol.get().await()
            val draftDocs = draftSnap.documents.mapNotNull { AdminGeneralitySectionDoc.fromSnapshot(it) }
                .filter { !it.isDeleted }
                .sortedWith(compareBy({ it.order }, { it.title.lowercase() }))

            if (draftDocs.isEmpty()) {
                return@withContext Result(false, "No hay secciones activas en draft (todas eliminadas o vacío).")
            }

            // 4) Build JSON bytes
            val jsonBytes = buildGeneralitiesJsonBytes(draftDocs)

            val bytesLen = jsonBytes.size.toLong()
            val sha = sha256(jsonBytes)

            // 5) Upload release + stable
            val releaseRef = storage.reference.child(releasePath)
            val stableRef = storage.reference.child(stablePath)

            releaseRef.putBytes(jsonBytes).await()
            stableRef.putBytes(jsonBytes).await()

            // 6) Update channels stable.generalities
            val newSpec = mapOf(
                "version" to nextVersion,
                "storagePath" to stablePath,
                "bytes" to bytesLen,
                "sha256" to sha,
                "minAppVersionCode" to 0
            )

            // usamos merge: set stable.generalities sin reventar stable completo
            val newStableMap = HashMap<String, Any>()
            // copiamos lo que exista en stable, pero reemplazamos generalities
            stable.forEach { (k, v) -> if (k is String) newStableMap[k] = v as Any }
            newStableMap["generalities"] = newSpec

            channelsRef.set(mapOf("stable" to newStableMap), com.google.firebase.firestore.SetOptions.merge()).await()

            // 7) Audit
            val audit = mapOf(
                "module" to "generalities",
                "action" to "publish_stable",
                "byUid" to uid,
                "createdAt" to System.currentTimeMillis(),
                "version" to nextVersion,
                "stablePath" to stablePath,
                "releasePath" to releasePath,
                "bytes" to bytesLen,
                "sha256" to sha,
                "sectionsCount" to draftDocs.size
            )
            auditCol.add(audit).await()

            Result(true, "Publicado STABLE v$nextVersion (secciones: ${draftDocs.size}, bytes: $bytesLen, sha: ${sha.take(8)}…).")
        } catch (e: Exception) {
            Log.w(TAG, "publishStable failed: ${e.message}", e)
            Result(false, "Error publicando STABLE: ${e.message}")
        } finally {
            releaseLock(lockId)
        }
    }

    private suspend fun acquireLock(lockId: String, uid: String): Boolean {
        val ref = locksCol.document(lockId)
        return try {
            db.runTransaction { tx ->
                val snap = tx.get(ref)
                val now = System.currentTimeMillis()

                val existingBy = snap.getString("byUid")
                val expiresAt = (snap.getLong("expiresAt") ?: 0L)

                val isActive = snap.exists() && expiresAt > now && !existingBy.isNullOrBlank()
                if (isActive) return@runTransaction false

                val lockDoc = mapOf(
                    "module" to "generalities",
                    "scope" to "stable",
                    "byUid" to uid,
                    "createdAt" to now,
                    // 2 minutos de lock, suficiente para subir y escribir spec
                    "expiresAt" to (now + 2 * 60 * 1000L)
                )
                tx.set(ref, lockDoc)
                true
            }.await()
        } catch (e: Exception) {
            Log.w(TAG, "acquireLock failed: ${e.message}", e)
            false
        }
    }

    private suspend fun releaseLock(lockId: String) {
        val ref = locksCol.document(lockId)
        runCatching {
            ref.delete().await()
        }
    }

    private fun buildGeneralitiesJsonBytes(draft: List<AdminGeneralitySectionDoc>): ByteArray {
        val root = JSONObject()
        root.put("schema", 1)
        root.put("generatedAt", System.currentTimeMillis())

        val sectionsArr = JSONArray()

        for (d in draft) {
            val sObj = JSONObject()
            // id estable: usa docId si existe; si no, generamos uno “sanitizado”
            val id = d.id.ifBlank { sanitizeId(d.title) }
            sObj.put("id", id)
            sObj.put("title", d.title)
            sObj.put("order", d.order)

            val blocksArr = JSONArray()
            val bObj = JSONObject()
            bObj.put("type", "table")
            bObj.put("title", d.tableTitle)

            val colsArr = JSONArray()
            d.columns.forEach { colsArr.put(it) }
            bObj.put("columns", colsArr)

            // ✅ En OTA JSON sí usamos nested arrays (OK)
            val rowsArr = JSONArray()
            d.rows.forEach { row ->
                val r = JSONArray()
                row.forEach { cell -> r.put(cell) }
                rowsArr.put(r)
            }
            bObj.put("rows", rowsArr)

            blocksArr.put(bObj)
            sObj.put("blocks", blocksArr)

            sectionsArr.put(sObj)
        }

        root.put("sections", sectionsArr)
        return root.toString(2).toByteArray(Charsets.UTF_8)
    }

    private fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun sanitizeId(s: String): String {
        return s.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "section" }
    }

    companion object {
        private const val TAG = "AdminGenPublish"
    }
}
