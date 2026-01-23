package com.cabgon.blackhawk.data.preflight

import android.content.Context
import com.cabgon.blackhawk.data.DbProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.io.File

/**
 * Sincronización remota de inspecciones Preflight contra Firestore.
 *
 * - Sube inspecciones marcadas como dirty en la DB local.
 * - Descarga inspecciones remotas del usuario y las inserta/actualiza localmente.
 * - Permite borrar en remoto una inspección ligada por syncId.
 *
 * NO reemplaza la lógica local (Room + JSON extras), solo la complementa.
 */
class PreflightRemoteSync(
    private val context: Context,
    private val firestore: FirebaseFirestore
) {

    companion object {
        // Colección en Firestore donde se guardan las inspecciones de pre vuelo
        const val COLLECTION_PREFLIGHT = "preflight_inspections"
    }

    private val dao: PreflightDao = DbProvider.preflight(context).dao()

    /**
     * Sube a Firestore todas las inspecciones marcadas como dirty para el usuario indicado.
     */
    suspend fun syncDirtyInspections(userId: String) {
        if (userId.isBlank()) return

        val dirtyList = dao.getDirtyInspections()
        if (dirtyList.isEmpty()) return

        val syncTimestamp = System.currentTimeMillis()

        for (row in dirtyList) {
            val ins = row.inspection
            val items = row.items

            // Extras desde el JSON sidecar (igual que en PreflightRepository)
            val extras = readExtrasJson(ins.id)

            val data = hashMapOf<String, Any?>(
                "userId" to userId,
                "fechaEpochMillis" to ins.fechaEpochMillis,
                "matricula" to ins.matricula,
                "nombre" to ins.nombre,
                "completed" to ins.completed,
                "lastModified" to (if (ins.lastModified != 0L) ins.lastModified else syncTimestamp),
                // Extras de encabezado (opcionales)
                "hora24" to (extras?.optString("hora24") ?: ""),
                "tecnicoGrado" to (extras?.optString("tecnicoGrado") ?: ""),
                "tecnicoEspecialidad" to (extras?.optString("tecnicoEspecialidad") ?: ""),
                "hsTotales" to (extras?.optString("hsTotales") ?: ""),
                "hsDisponibles" to (extras?.optString("hsDisponibles") ?: ""),
                "tecnicoMatricula" to (extras?.optString("tecnicoMatricula") ?: "")
            )

            // Ítems del checklist
            val itemsPayload = items.map { item ->
                mapOf(
                    "title" to item.title,
                    "orderIndex" to item.orderIndex,
                    "checked" to item.checked
                )
            }
            data["items"] = itemsPayload

            try {
                val collection = firestore.collection(COLLECTION_PREFLIGHT)

                val docRef = if (ins.syncId.isNullOrBlank()) {
                    // Nueva inspección en Firestore
                    collection.document()
                } else {
                    // Actualización de inspección ya sincronizada
                    collection.document(ins.syncId!!)
                }

                if (ins.syncId.isNullOrBlank()) {
                    // set "normal" crea el documento
                    docRef.set(data).await()
                } else {
                    // merge para no perder campos si en el futuro se agregan más
                    docRef.set(data, SetOptions.merge()).await()
                }

                // Escritura remota OK → actualizar estado local
                val updated = ins.copy(
                    syncId = ins.syncId ?: docRef.id,
                    dirty = false,
                    lastModified = syncTimestamp
                )
                dao.updateInspection(updated)

            } catch (_: Exception) {
                // Si falla, dejamos dirty=true para reintentar luego
            }
        }
    }

    /**
     * Descarga desde Firestore todas las inspecciones del usuario y las aplica a la DB local.
     *
     * Estrategia:
     *  - Si no existe inspección local con ese syncId -> se inserta nueva (dirty=false).
     *  - Si existe y local.dirty == true -> se respeta la versión local (pendiente de subir).
     *  - Si existe y local.dirty == false:
     *      - Si remote.lastModified > local.lastModified -> se sobrescribe con versión remota.
     *      - En otro caso, se deja tal como está.
     */
    suspend fun pullFromRemote(userId: String) {
        if (userId.isBlank()) return

        val snapshot = firestore.collection(COLLECTION_PREFLIGHT)
            .whereEqualTo("userId", userId)
            .get()
            .await()

        for (doc in snapshot.documents) {
            val syncId = doc.id
            val remoteLastModified = doc.getLong("lastModified") ?: 0L
            val remoteFecha = doc.getLong("fechaEpochMillis") ?: 0L
            val matricula = doc.getString("matricula") ?: ""
            val nombre = doc.getString("nombre") ?: ""
            val completed = doc.getBoolean("completed") ?: false

            @Suppress("UNCHECKED_CAST")
            val itemsRaw = doc.get("items") as? List<Map<String, Any?>> ?: emptyList()

            // ¿Existe ya localmente esta inspección (por syncId)?
            val local = dao.getInspectionBySyncId(syncId)

            if (local == null) {
                // No existe localmente -> insertar nueva inspección + ítems + extras
                val newEntity = PreflightInspection(
                    fechaEpochMillis = remoteFecha,
                    matricula = matricula,
                    nombre = nombre,
                    completed = completed,
                    syncId = syncId,
                    lastModified = remoteLastModified,
                    dirty = false,
                    originDeviceId = null
                )

                val newId = dao.insertInspection(newEntity)

                val entities = itemsRaw.mapIndexed { idx, m ->
                    PreflightItem(
                        inspectionId = newId,
                        title = (m["title"] as? String).orElseEmpty(),
                        orderIndex = (m["orderIndex"] as? Number)?.toInt() ?: idx,
                        checked = m["checked"] as? Boolean ?: false
                    )
                }
                if (entities.isNotEmpty()) {
                    dao.insertItems(entities)
                }

                // Extras
                writeExtrasJson(
                    id = newId,
                    hora24 = doc.getString("hora24") ?: "",
                    tecnicoGrado = doc.getString("tecnicoGrado") ?: "",
                    tecnicoEspecialidad = doc.getString("tecnicoEspecialidad") ?: "",
                    hsTotales = doc.getString("hsTotales") ?: "",
                    hsDisponibles = doc.getString("hsDisponibles") ?: "",
                    tecnicoMatricula = doc.getString("tecnicoMatricula") ?: ""
                )
            } else {
                val localIns = local.inspection

                // Si local está marcado como dirty, respetamos la versión local (se subirá después)
                if (localIns.dirty) {
                    continue
                }

                // Si la versión remota no es más nueva, no tocamos nada
                if (remoteLastModified <= localIns.lastModified) {
                    continue
                }

                // Sobrescribir encabezado local con datos remotos
                val updated = localIns.copy(
                    fechaEpochMillis = remoteFecha,
                    matricula = matricula,
                    nombre = nombre,
                    completed = completed,
                    lastModified = remoteLastModified,
                    dirty = false
                )
                dao.updateInspection(updated)

                // Reemplazar ítems: borramos los actuales y volvemos a insertar
                dao.deleteItemsForInspection(localIns.id)

                val entities = itemsRaw.mapIndexed { idx, m ->
                    PreflightItem(
                        inspectionId = localIns.id,
                        title = (m["title"] as? String).orElseEmpty(),
                        orderIndex = (m["orderIndex"] as? Number)?.toInt() ?: idx,
                        checked = m["checked"] as? Boolean ?: false
                    )
                }
                if (entities.isNotEmpty()) {
                    dao.insertItems(entities)
                }

                // Actualizar extras
                writeExtrasJson(
                    id = localIns.id,
                    hora24 = doc.getString("hora24") ?: "",
                    tecnicoGrado = doc.getString("tecnicoGrado") ?: "",
                    tecnicoEspecialidad = doc.getString("tecnicoEspecialidad") ?: "",
                    hsTotales = doc.getString("hsTotales") ?: "",
                    hsDisponibles = doc.getString("hsDisponibles") ?: "",
                    tecnicoMatricula = doc.getString("tecnicoMatricula") ?: ""
                )
            }
        }
    }

    /**
     * Borrar en Firestore la inspección asociada al id local, si tiene syncId.
     * - Se usa antes de borrar localmente.
     * - Si no existe syncId o falla el delete remoto, no revienta la app.
     */
    suspend fun deleteRemoteByLocalId(localId: Long) {
        try {
            val local = dao.getInspection(localId) ?: return
            val syncId = local.inspection.syncId ?: return
            if (syncId.isBlank()) return

            firestore.collection(COLLECTION_PREFLIGHT)
                .document(syncId)
                .delete()
                .await()
        } catch (_: Exception) {
            // Si falla el borrado remoto (offline, etc.), no tiramos la app.
            // En ese caso el doc remoto podría reaparecer en otra instalación,
            // pero en el dispositivo actual ya no se verá porque se borró localmente.
        }
    }

    // ------------ Helpers para JSON de extras (misma convención que PreflightRepository) ------------

    private fun extrasDir(): File =
        File(context.filesDir, "preflight_extras").apply { if (!exists()) mkdirs() }

    private fun extrasFile(id: Long): File =
        File(extrasDir(), "inspection_$id.json")

    private fun writeExtrasJson(
        id: Long,
        hora24: String,
        tecnicoGrado: String,
        tecnicoEspecialidad: String,
        hsTotales: String,
        hsDisponibles: String,
        tecnicoMatricula: String
    ) {
        val obj = JSONObject().apply {
            put("hora24", hora24)
            put("tecnicoGrado", tecnicoGrado)
            put("tecnicoEspecialidad", tecnicoEspecialidad)
            put("hsTotales", hsTotales)
            put("hsDisponibles", hsDisponibles)
            put("tecnicoMatricula", tecnicoMatricula)
        }
        extrasFile(id).writeText(obj.toString())
    }

    private fun readExtrasJson(id: Long): JSONObject? {
        val f = extrasFile(id)
        if (!f.exists()) return null
        return try { JSONObject(f.readText()) } catch (_: Throwable) { null }
    }

    // Helper para evitar nulls feos al mapear campos String
    private fun Any?.orElseEmpty(): String = this as? String ?: ""
}
