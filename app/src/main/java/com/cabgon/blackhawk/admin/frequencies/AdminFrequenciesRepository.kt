package com.cabgon.blackhawk.admin.frequencies

import android.content.Context
import android.net.Uri
import com.cabgon.blackhawk.ai.await
import com.cabgon.blackhawk.content.frequencies.FrequenciesJson
import com.cabgon.blackhawk.content.frequencies.FrequenciesManifest
import com.cabgon.blackhawk.content.frequencies.FrequencyItem
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AdminFrequenciesRepository {

    private val db = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()

    private var reg: ListenerRegistration? = null

    private val draftCol = db.collection("content_admin")
        .document("frequencies_draft")
        .collection("items")

    private val auditCol = db.collection("content_admin")
        .document("audit_root")
        .collection("audit_logs")

    private val lockDoc = db.collection("content_admin")
        .document("locks_root")
        .collection("locks")
        .document("frequencies_publish")

    fun close() {
        reg?.remove()
        reg = null
    }

    // -------------------------
    // DRAFT OBSERVE + CRUD
    // -------------------------

    fun observeDraft(onUpdate: (List<AdminFrequencyDoc>) -> Unit) {
        reg?.remove()
        reg = draftCol.addSnapshotListener { snap, _ ->
            val list = snap?.documents?.mapNotNull { d ->
                AdminFrequencyDoc.fromFirestore(d.id, d.data ?: return@mapNotNull null)
            } ?: emptyList()

            // Tombstones al final para que no estorben, pero siguen visibles
            onUpdate(
                list.sortedWith(
                    compareBy<AdminFrequencyDoc>({ it.isDeleted }, { it.state.lowercase() }, { it.city.lowercase() }, { it.airportName.lowercase() })
                )
            )
        }
    }

    suspend fun fetchDraftOnce(): List<AdminFrequencyDoc> = withContext(Dispatchers.IO) {
        runCatching {
            val snap = draftCol.get().await()
            snap.documents.mapNotNull { d ->
                AdminFrequencyDoc.fromFirestore(d.id, d.data ?: return@mapNotNull null)
            }.sortedWith(compareBy({ it.isDeleted }, { it.state.lowercase() }, { it.city.lowercase() }, { it.airportName.lowercase() }))
        }.getOrElse { emptyList() }
    }

    suspend fun addDraftItem(doc: AdminFrequencyDoc): Boolean = withContext(Dispatchers.IO) {
        runCatching { draftCol.add(doc.copy(isDeleted = false).toMap()).await() }.isSuccess
    }

    suspend fun updateDraftItem(doc: AdminFrequencyDoc): Boolean = withContext(Dispatchers.IO) {
        if (doc.id.isBlank()) return@withContext false
        runCatching { draftCol.document(doc.id).set(doc.toMap()).await() }.isSuccess
    }

    suspend fun setTombstone(id: String, isDeleted: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (id.isBlank()) return@withContext false
        runCatching { draftCol.document(id).set(mapOf("isDeleted" to isDeleted), SetOptions.merge()).await() }.isSuccess
    }

    // -------------------------
    // LOCK STATUS + FORCE UNLOCK (UI)
    // -------------------------

    data class LockStatus(
        val lockedBy: String?,
        val lockedAt: Long,
        val lockedUntil: Long
    ) {
        fun isActive(now: Long = System.currentTimeMillis()): Boolean =
            !lockedBy.isNullOrBlank() && lockedUntil > now
    }

    fun observePublishLock(onUpdate: (LockStatus) -> Unit): ListenerRegistration {
        return lockDoc.addSnapshotListener { snap, _ ->
            val lockedBy = snap?.getString("lockedBy")
            val lockedAt = snap?.getLong("lockedAt") ?: 0L
            val lockedUntil = snap?.getLong("lockedUntil") ?: 0L
            onUpdate(LockStatus(lockedBy = lockedBy, lockedAt = lockedAt, lockedUntil = lockedUntil))
        }
    }

    suspend fun forceUnlockWithAudit(actorUid: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            lockDoc.set(
                mapOf(
                    "lockedBy" to "",
                    "lockedAt" to 0L,
                    "lockedUntil" to 0L
                ),
                SetOptions.merge()
            ).await()

            auditCol.add(
                mapOf(
                    "ts" to System.currentTimeMillis(),
                    "action" to "force_unlock_publish_lock",
                    "channel" to "stable",
                    "actorUid" to actorUid
                )
            ).await()
        }.isSuccess
    }

    // -------------------------
    // IMPORT PUBLISHED -> DRAFT (REPLACE)
    // -------------------------

    data class ImportResult(
        val ok: Boolean,
        val itemsImported: Int = 0,
        val sourcePath: String = "",
        val error: String? = null
    )

    suspend fun importCurrentStableToDraftReplace(ctx: Context): ImportResult = withContext(Dispatchers.IO) {
        try {
            val (path, items) = fetchCurrentStableItems(ctx)
            if (items.isEmpty()) return@withContext ImportResult(ok = false, error = "No se encontraron items en el publicado actual.")

            clearDraftAll()
            writeDraftItemsDeterministic(items) // isDeleted=false

            ImportResult(ok = true, itemsImported = items.size, sourcePath = path)
        } catch (e: Exception) {
            ImportResult(ok = false, error = e.message ?: "Error desconocido")
        }
    }

    private suspend fun fetchCurrentStableItems(ctx: Context): Pair<String, List<FrequencyItem>> = withContext(Dispatchers.IO) {
        val channelsDoc = db.collection("content_ota").document("channels").get().await()
        val stableMap = channelsDoc.get("stable") as? Map<*, *>
        val freqMap = stableMap?.get("frequencies") as? Map<*, *>
        val storagePath = (freqMap?.get("storagePath") as? String)?.trim()
            ?: throw IllegalStateException("No hay storagePath en content_ota/channels → stable.frequencies.storagePath")

        val ref = storage.reference.child(storagePath)
        val tmpDir = File(ctx.cacheDir, "admin_import").apply { mkdirs() }
        val tmpFile = File(tmpDir, "current_stable.json")
        ref.getFile(tmpFile).await()

        val manifest = FrequenciesJson.parse(tmpFile.readText())
        storagePath to manifest.items
    }

    private suspend fun clearDraftAll() = withContext(Dispatchers.IO) {
        val snap = draftCol.get().await()
        if (snap.isEmpty) return@withContext
        snap.documents.chunked(450).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
    }

    private suspend fun writeDraftItemsDeterministic(items: List<FrequencyItem>) = withContext(Dispatchers.IO) {
        items.chunked(450).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { it ->
                val docId = safeDocId(keyOf(it))
                val ref = draftCol.document(docId)
                batch.set(ref, frequencyItemToDraftMap(it, isDeleted = false))
            }
            batch.commit().await()
        }
    }

    private fun frequencyItemToDraftMap(it: FrequencyItem, isDeleted: Boolean): Map<String, Any?> = mapOf(
        "state" to it.state,
        "city" to it.city,
        "airportName" to it.airportName,
        "icao" to it.icao,
        "iata" to it.iata,
        "type" to it.type,
        "callsign" to it.callsign,
        "freqMHz" to it.freqMHz,
        "freqKhz" to it.freqKhz,
        "ident" to it.ident,
        "remarks" to it.remarks,
        "isEmergency" to it.isEmergency,
        "isDeleted" to isDeleted
    )

    private fun safeDocId(raw: String): String = raw.replace("/", "_").take(500)

    // -------------------------
    // DIFF (incluye REMOVED)
    // -------------------------

    data class DiffSummary(
        val added: Int,
        val modified: Int,
        val unchanged: Int,
        val removed: Int,
        val byState: Map<String, StateDiff>
    ) {
        data class StateDiff(val added: Int, val modified: Int, val unchanged: Int, val removed: Int)
    }

    private fun computeDiff(base: List<FrequencyItem>, merged: List<FrequencyItem>, removedKeys: Set<String>): DiffSummary {
        val baseMap = base.associateBy { keyOf(it) }

        var added = 0
        var modified = 0
        var unchanged = 0

        val stateAgg = linkedMapOf<String, Quad>() // a,m,u,r

        fun bump(state: String, a: Int, m: Int, u: Int, r: Int) {
            val k = state.ifBlank { "—" }
            val cur = stateAgg[k] ?: Quad(0, 0, 0, 0)
            stateAgg[k] = Quad(cur.a + a, cur.m + m, cur.u + u, cur.r + r)
        }

        // Removed count comes from tombstones, but we aggregate per-state based on base item state when available.
        removedKeys.forEach { k ->
            val prev = baseMap[k]
            val st = prev?.state ?: "—"
            bump(st, 0, 0, 0, 1)
        }

        merged.forEach { cur ->
            val k = keyOf(cur)
            val prev = baseMap[k]
            val state = cur.state

            if (prev == null) {
                added++
                bump(state, 1, 0, 0, 0)
            } else {
                if (isSameContent(prev, cur)) {
                    unchanged++
                    bump(state, 0, 0, 1, 0)
                } else {
                    modified++
                    bump(state, 0, 1, 0, 0)
                }
            }
        }

        val removed = removedKeys.size
        val byState = stateAgg.mapValues { (_, q) ->
            DiffSummary.StateDiff(q.a, q.m, q.u, q.r)
        }

        return DiffSummary(added = added, modified = modified, unchanged = unchanged, removed = removed, byState = byState)
    }

    private data class Quad(val a: Int, val m: Int, val u: Int, val r: Int)

    private fun isSameContent(a: FrequencyItem, b: FrequencyItem): Boolean {
        fun s(x: String?) = (x ?: "").trim()
        fun u(x: String?) = s(x).uppercase()
        fun d(x: Double?) = x ?: 0.0
        fun bool(x: Boolean?) = x ?: false

        return s(a.state) == s(b.state) &&
                s(a.city) == s(b.city) &&
                s(a.airportName) == s(b.airportName) &&
                u(a.icao) == u(b.icao) &&
                u(a.iata) == u(b.iata) &&
                u(a.type) == u(b.type) &&
                u(a.callsign) == u(b.callsign) &&
                d(a.freqMHz) == d(b.freqMHz) &&
                d(a.freqKhz) == d(b.freqKhz) &&
                u(a.ident) == u(b.ident) &&
                s(a.remarks) == s(b.remarks) &&
                bool(a.isEmergency) == bool(b.isEmergency)
    }

    // -------------------------
    // PUBLISH (LOCK + RELEASE + STABLE + AUDIT + TOMBSTONE)
    // -------------------------

    data class PublishPlan(
        val channel: String,
        val currentVersion: Long,
        val newVersion: Long,
        val stablePath: String,
        val releasePath: String,
        val baseCount: Int,
        val draftCount: Int,
        val tombstoneCount: Int,
        val totalCount: Int,
        val removedKeys: Set<String>,
        val diff: DiffSummary,
        val json: String
    ) {
        fun preview(maxChars: Int = 6000): String =
            if (json.length <= maxChars) json else json.take(maxChars) + "\n…(preview recortado)…"

        fun diffPreviewTopStates(maxStates: Int = 10): String {
            val lines = diff.byState.entries
                .sortedByDescending { (_, v) -> v.added + v.modified + v.removed }
                .take(maxStates)
                .map { (k, v) -> "$k: +${v.added}  ~${v.modified}  -${v.removed}  =${v.unchanged}" }
            return if (lines.isEmpty()) "—" else lines.joinToString("\n")
        }
    }

    /**
     * Lock TTL: 10 minutos.
     */
    private val lockTtlMs: Long = 10L * 60L * 1000L

    private suspend fun acquirePublishLockOrThrow(actorUid: String) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val expiresAt = now + lockTtlMs

        db.runTransaction { tx ->
            val snap = tx.get(lockDoc)
            val lockedBy = snap.getString("lockedBy")
            val lockedUntil = snap.getLong("lockedUntil") ?: 0L

            val isLocked = !lockedBy.isNullOrBlank() && lockedUntil > now && lockedBy != actorUid
            if (isLocked) {
                throw IllegalStateException("Publicación en curso por otro usuario (lock activo hasta ${Date(lockedUntil)}).")
            }

            tx.set(
                lockDoc,
                mapOf(
                    "lockedBy" to actorUid,
                    "lockedAt" to now,
                    "lockedUntil" to expiresAt
                ),
                SetOptions.merge()
            )
            null
        }.await()
    }

    private suspend fun releasePublishLock(actorUid: String) = withContext(Dispatchers.IO) {
        runCatching {
            db.runTransaction { tx ->
                val snap = tx.get(lockDoc)
                val lockedBy = snap.getString("lockedBy")
                if (lockedBy == actorUid) {
                    tx.set(
                        lockDoc,
                        mapOf(
                            "lockedBy" to "",
                            "lockedAt" to 0L,
                            "lockedUntil" to 0L
                        ),
                        SetOptions.merge()
                    )
                }
                null
            }.await()
        }
    }

    suspend fun prepareStablePublishPlan(ctx: Context): PublishPlan = withContext(Dispatchers.IO) {
        val draftSnap = draftCol.get().await()
        val draftDocs = draftSnap.documents.mapNotNull { d ->
            AdminFrequencyDoc.fromFirestore(d.id, d.data ?: return@mapNotNull null)
        }
        if (draftDocs.isEmpty()) throw IllegalStateException("Draft vacío. Agrega items antes de publicar.")

        val channelsDocRef = db.collection("content_ota").document("channels")
        val channelsDoc = channelsDocRef.get().await()

        val stableMap = channelsDoc.get("stable") as? Map<*, *>
        val freqMap = stableMap?.get("frequencies") as? Map<*, *>
        val currentVersion = (freqMap?.get("version") as? Number)?.toLong() ?: 0L
        val storagePathFromSpec = (freqMap?.get("storagePath") as? String)?.trim()

        val newVersion = currentVersion + 1L

        val baseItems: List<FrequencyItem> = if (!storagePathFromSpec.isNullOrBlank()) {
            downloadAndParseCurrent(ctx, storagePathFromSpec)
        } else emptyList()

        val deletions = draftDocs.filter { it.isDeleted }
        val upserts = draftDocs.filter { !it.isDeleted }

        val removedKeys = deletions.map { keyOf(it.toFrequencyItem()) }.toSet()

        val merged = mergeBaseWithDraftAndTombstone(
            base = baseItems,
            upserts = upserts.map { it.toFrequencyItem() },
            removedKeys = removedKeys
        )

        val diff = computeDiff(baseItems, merged, removedKeys)

        val manifest = FrequenciesManifest(
            schema = 1,
            generatedAt = nowIso(),
            items = merged
        )
        val json = manifestToJson(manifest)

        val stablePath = "content/stable/frequencies/frequencies.json"
        val releasePath = "content/stable/frequencies/releases/frequencies_v${newVersion}.json"

        PublishPlan(
            channel = "stable",
            currentVersion = currentVersion,
            newVersion = newVersion,
            stablePath = stablePath,
            releasePath = releasePath,
            baseCount = baseItems.size,
            draftCount = draftDocs.size,
            tombstoneCount = deletions.size,
            totalCount = merged.size,
            removedKeys = removedKeys,
            diff = diff,
            json = json
        )
    }

    data class PublishResult(
        val ok: Boolean,
        val newVersion: Long = 0,
        val shaShort: String = "",
        val bytes: Long = 0,
        val baseCount: Int = 0,
        val draftCount: Int = 0,
        val tombstoneCount: Int = 0,
        val totalCount: Int = 0,
        val clearedDraftCount: Int = 0,
        val stablePath: String = "",
        val releasePath: String = "",
        val warning: String? = null,
        val error: String? = null
    )

    suspend fun publishStable(ctx: Context, actorUid: String): PublishResult = withContext(Dispatchers.IO) {
        var lockAcquired = false
        try {
            acquirePublishLockOrThrow(actorUid)
            lockAcquired = true

            val plan = prepareStablePublishPlan(ctx)

            val outDir = File(ctx.cacheDir, "admin_publish").apply { mkdirs() }
            val outFile = File(outDir, "frequencies.json").apply { writeText(plan.json) }

            val bytes = outFile.length()
            val sha = sha256(outFile)
            val shaShort = sha.take(8) + "…"

            // Release versionada + stable pointer
            storage.reference.child(plan.releasePath).putFile(Uri.fromFile(outFile)).await()
            storage.reference.child(plan.stablePath).putFile(Uri.fromFile(outFile)).await()

            // Spec
            val channelsDocRef = db.collection("content_ota").document("channels")
            val updateMap = mapOf(
                "stable.frequencies.version" to plan.newVersion,
                "stable.frequencies.storagePath" to plan.stablePath,
                "stable.frequencies.bytes" to bytes,
                "stable.frequencies.sha256" to sha
            )
            channelsDocRef.update(updateMap).await()

            // Audit (incluye removed)
            auditCol.add(
                mapOf(
                    "ts" to System.currentTimeMillis(),
                    "action" to "publish_frequencies",
                    "channel" to "stable",
                    "version" to plan.newVersion,
                    "bytes" to bytes,
                    "sha256" to sha,
                    "stablePath" to plan.stablePath,
                    "releasePath" to plan.releasePath,
                    "itemsCount" to plan.totalCount,
                    "baseCount" to plan.baseCount,
                    "draftCount" to plan.draftCount,
                    "tombstoneCount" to plan.tombstoneCount,
                    "diffAdded" to plan.diff.added,
                    "diffModified" to plan.diff.modified,
                    "diffRemoved" to plan.diff.removed,
                    "diffUnchanged" to plan.diff.unchanged
                )
            ).await()

            // Clear drafts (incluye tombstones ya aplicados)
            var cleared = 0
            var warn: String? = null
            try {
                val ds = draftCol.get().await()
                if (!ds.isEmpty) {
                    ds.documents.chunked(450).forEach { chunk ->
                        val batch = db.batch()
                        chunk.forEach { d ->
                            batch.delete(d.reference)
                            cleared++
                        }
                        batch.commit().await()
                    }
                }
            } catch (e: Exception) {
                warn = "Publicado OK, pero no se pudieron limpiar drafts: ${e.message}"
            }

            PublishResult(
                ok = true,
                newVersion = plan.newVersion,
                shaShort = shaShort,
                bytes = bytes,
                baseCount = plan.baseCount,
                draftCount = plan.draftCount,
                tombstoneCount = plan.tombstoneCount,
                totalCount = plan.totalCount,
                clearedDraftCount = cleared,
                stablePath = plan.stablePath,
                releasePath = plan.releasePath,
                warning = warn
            )
        } catch (e: Exception) {
            PublishResult(ok = false, error = e.message ?: "Error desconocido")
        } finally {
            if (lockAcquired) releasePublishLock(actorUid)
        }
    }

    private suspend fun downloadAndParseCurrent(ctx: Context, storagePath: String): List<FrequencyItem> = withContext(Dispatchers.IO) {
        runCatching {
            val ref = storage.reference.child(storagePath)
            val tmpDir = File(ctx.cacheDir, "admin_base").apply { mkdirs() }
            val tmpFile = File(tmpDir, "base_frequencies.json")
            ref.getFile(tmpFile).await()
            FrequenciesJson.parse(tmpFile.readText()).items
        }.getOrElse { emptyList() }
    }

    private fun mergeBaseWithDraftAndTombstone(
        base: List<FrequencyItem>,
        upserts: List<FrequencyItem>,
        removedKeys: Set<String>
    ): List<FrequencyItem> {
        val map = LinkedHashMap<String, FrequencyItem>()

        base.forEach { map[keyOf(it)] = it }

        // ✅ Tombstone: remueve del resultado final
        removedKeys.forEach { map.remove(it) }

        // Upsert
        upserts.forEach { map[keyOf(it)] = it }

        return map.values.sortedWith(
            compareBy(
                { it.state.lowercase() },
                { it.city.lowercase() },
                { it.airportName.lowercase() },
                { it.icao.lowercase() },
                { it.type.lowercase() }
            )
        )
    }

    private fun keyOf(i: FrequencyItem): String {
        val f = when {
            (i.freqMHz ?: 0.0) > 0.0 -> "mhz:${i.freqMHz}"
            (i.freqKhz ?: 0.0) > 0.0 -> "khz:${i.freqKhz}"
            else -> "freq:0"
        }
        return listOf(
            i.icao.trim().uppercase(),
            i.type.trim().uppercase(),
            (i.callsign ?: "").trim().uppercase(),
            (i.ident ?: "").trim().uppercase(),
            f
        ).joinToString("|")
    }

    // -------------------------
    // Audit log + rollback (sin cambios)
    // -------------------------

    data class AuditEntry(
        val id: String,
        val ts: Long,
        val action: String,
        val channel: String,
        val version: Long,
        val bytes: Long,
        val sha256: String,
        val stablePath: String?,
        val releasePath: String?,
        val itemsCount: Long?,
        val baseCount: Long?,
        val draftCount: Long?
    ) {
        val shaShort: String get() = if (sha256.length >= 8) sha256.take(8) + "…" else sha256
    }

    fun observeAuditLog(limit: Long = 50, onUpdate: (List<AuditEntry>) -> Unit): ListenerRegistration {
        return auditCol
            .orderBy("ts", Query.Direction.DESCENDING)
            .limit(limit)
            .addSnapshotListener { snap, _ ->
                val list = snap?.documents?.mapNotNull { d ->
                    val m = d.data ?: return@mapNotNull null
                    val ts = (m["ts"] as? Number)?.toLong() ?: return@mapNotNull null
                    val action = (m["action"] as? String) ?: "unknown"
                    val channel = (m["channel"] as? String) ?: "stable"
                    val version = (m["version"] as? Number)?.toLong() ?: 0L
                    val bytes = (m["bytes"] as? Number)?.toLong() ?: 0L
                    val sha = (m["sha256"] as? String) ?: ""
                    val stablePath = m["stablePath"] as? String
                    val releasePath = m["releasePath"] as? String
                    val itemsCount = (m["itemsCount"] as? Number)?.toLong()
                    val baseCount = (m["baseCount"] as? Number)?.toLong()
                    val draftCount = (m["draftCount"] as? Number)?.toLong()

                    AuditEntry(
                        id = d.id,
                        ts = ts,
                        action = action,
                        channel = channel,
                        version = version,
                        bytes = bytes,
                        sha256 = sha,
                        stablePath = stablePath,
                        releasePath = releasePath,
                        itemsCount = itemsCount,
                        baseCount = baseCount,
                        draftCount = draftCount
                    )
                } ?: emptyList()

                onUpdate(list)
            }
    }

    suspend fun rollbackStableToRelease(
        version: Long,
        releasePath: String,
        bytes: Long,
        sha256: String
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val channelsDocRef = db.collection("content_ota").document("channels")
            val updateMap = mapOf(
                "stable.frequencies.version" to version,
                "stable.frequencies.storagePath" to releasePath,
                "stable.frequencies.bytes" to bytes,
                "stable.frequencies.sha256" to sha256
            )
            channelsDocRef.update(updateMap).await()

            auditCol.add(
                mapOf(
                    "ts" to System.currentTimeMillis(),
                    "action" to "rollback_frequencies",
                    "channel" to "stable",
                    "version" to version,
                    "bytes" to bytes,
                    "sha256" to sha256,
                    "releasePath" to releasePath
                )
            ).await()
        }.isSuccess
    }

    // -------------------------
    // JSON + HASH
    // -------------------------

    private fun nowIso(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        return sdf.format(Date())
    }

    private fun manifestToJson(m: FrequenciesManifest): String {
        val root = JSONObject()
        root.put("schema", m.schema)
        root.put("generatedAt", m.generatedAt)

        val arr = JSONArray()
        m.items.forEach { it ->
            val o = JSONObject()
            o.put("state", it.state)
            o.put("city", it.city)
            o.put("airportName", it.airportName)
            o.put("icao", it.icao)
            if (it.iata != null) o.put("iata", it.iata)
            o.put("type", it.type)
            if (it.callsign != null) o.put("callsign", it.callsign)
            if (it.freqMHz != null) o.put("freqMHz", it.freqMHz)
            if (it.freqKhz != null) o.put("freqKhz", it.freqKhz)
            if (it.ident != null) o.put("ident", it.ident)
            if (it.remarks != null) o.put("remarks", it.remarks)
            o.put("isEmergency", it.isEmergency)
            arr.put(o)
        }
        root.put("items", arr)
        return root.toString(2)
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
