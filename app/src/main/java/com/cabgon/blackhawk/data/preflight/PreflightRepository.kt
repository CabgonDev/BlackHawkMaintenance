package com.cabgon.blackhawk.data.preflight

import android.content.Context
import com.cabgon.blackhawk.data.DbProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.io.File

/**
 * Repository para Preflight. Expone modelos simples para la UI/PDF (Header, Item, InspectionWithItems)
 * y mapea internamente a las entidades Room (PreflightInspection, PreflightItem).
 */
class PreflightRepository(private val context: Context) {

    private val dao: PreflightDao = DbProvider.preflight(context).dao()

    /* ---------------------------------------------
     * Modelos públicos (UI / PDF)
     * --------------------------------------------- */
    data class Header(
        val id: Long,
        val fechaEpochMillis: Long,
        val hora24: String,
        val matAeronave: String,
        val tecnicoGrado: String,
        val tecnicoEspecialidad: String,
        val tecnicoNombre: String,
        val hsTotales: String?,
        val hsDisponibles: String?,
        val tecnicoMatricula: String?
    )

    data class Item(
        val id: Long,
        val title: String,
        val checked: Boolean = false
    )

    data class InspectionWithItems(
        val header: Header,
        val items: List<Item>
    )

    /* ---------------------------------------------
     * Observación (mapea desde las entidades Room)
     * --------------------------------------------- */
    fun observe(): Flow<List<InspectionWithItems>> =
        dao.observeInspections().map { rows ->
            rows.map { db ->
                val ins = db.inspection
                val ex = readExtrasJson(ins.id)
                InspectionWithItems(
                    header = Header(
                        id = ins.id,
                        fechaEpochMillis = ins.fechaEpochMillis,
                        hora24 = ex?.optString("hora24") ?: "--:--",
                        matAeronave = ins.matricula,
                        tecnicoGrado = ex?.optString("tecnicoGrado") ?: "",
                        tecnicoEspecialidad = ex?.optString("tecnicoEspecialidad") ?: "",
                        tecnicoNombre = ins.nombre,
                        hsTotales = ex?.optStringOrNull("hsTotales"),
                        hsDisponibles = ex?.optStringOrNull("hsDisponibles"),
                        tecnicoMatricula = ex?.optStringOrNull("tecnicoMatricula")
                    ),
                    items = db.items.map { Item(id = it.id, title = it.title, checked = it.checked) }
                )
            }
        }

    /* ---------------------------------------------
     * CRUD y utilidades
     * --------------------------------------------- */
    suspend fun delete(inspectionId: Long) {
        dao.deleteInspection(inspectionId)
        extrasFile(inspectionId).delete()
    }

    /**
     * Marca / desmarca un ítem.
     * Mantiene la lógica de "completed" en función de si todos los ítems están checked,
     * y marca la inspección como "dirty" con lastModified actualizado.
     */
    suspend fun toggleItem(inspectionId: Long, itemId: Long, checked: Boolean) {
        val current = dao.getInspection(inspectionId) ?: return
        val now = System.currentTimeMillis()

        // 1) Actualizar ítems
        val updatedItems = current.items.map { item ->
            if (item.id == itemId) item.copy(checked = checked) else item
        }
        dao.updateItems(updatedItems)

        // 2) Recalcular si todos están completos
        val allChecked = updatedItems.all { it.checked }

        // 3) Actualizar encabezado (completed + sync)
        val inspection = current.inspection
        val updatedInspection = inspection.copy(
            completed = allChecked,
            lastModified = now,
            dirty = true
        )
        dao.updateInspection(updatedInspection)
    }

    /**
     * Sincroniza el campo "completed" con el estado real de los ítems
     * y marca la inspección como sucia (dirty) si hubo cambio.
     */
    suspend fun saveInspectionCompletion(inspectionId: Long) {
        val current = dao.getInspection(inspectionId) ?: return
        val allChecked = current.items.all { it.checked }
        val inspection = current.inspection

        if (inspection.completed != allChecked) {
            val now = System.currentTimeMillis()
            val updated = inspection.copy(
                completed = allChecked,
                lastModified = now,
                dirty = true
            )
            dao.updateInspection(updated)
        }
    }

    /**
     * Crea inspección:
     * - Inserta la entidad principal (PreflightInspection)
     * - Inserta los ítems del checklist como entidades (PreflightItem)
     * - Escribe extras en JSON sidecar (hora24, grado, etc.)
     *
     * Además deja listos los campos de sincronización (lastModified / dirty).
     */
    suspend fun createInspection(
        fechaMillis: Long,
        hora24: String,
        matAeronave: String,
        tecnicoGrado: String,
        tecnicoEspecialidad: String,
        tecnicoNombre: String,
        hsTotales: String?,
        hsDisponibles: String?,
        tecnicoMatricula: String?,
        templateTitles: List<String>
    ): Long {
        val now = System.currentTimeMillis()

        // 1) principal
        val id = dao.insertInspection(
            PreflightInspection(
                fechaEpochMillis = fechaMillis,
                matricula = matAeronave.trim(),
                nombre = tecnicoNombre.trim(),
                completed = false,

                // 🔽 campos de sincronización
                syncId = null,              // se llenará cuando se sincronice con otro sistema
                lastModified = now,
                dirty = true,
                originDeviceId = null       // opcional: después podemos rellenar con ANDROID_ID
            )
        )

        // 2) ítems (ENTIDADES, no el modelo UI)
        val entities: List<PreflightItem> = templateTitles.mapIndexed { idx, title ->
            PreflightItem(
                inspectionId = id,
                title = title,
                orderIndex = idx,
                checked = false
            )
        }
        dao.insertItems(entities)

        // 3) extras (JSON sidecar)
        writeExtrasJson(
            id = id,
            hora24 = hora24,
            tecnicoGrado = tecnicoGrado,
            tecnicoEspecialidad = tecnicoEspecialidad,
            hsTotales = hsTotales,
            hsDisponibles = hsDisponibles,
            tecnicoMatricula = tecnicoMatricula
        )

        return id
    }

    /** Encabezado listo para UI/PDF */
    suspend fun getHeader(inspectionId: Long): Header? {
        val db = dao.getInspection(inspectionId) ?: return null
        val ins = db.inspection
        val ex = readExtrasJson(inspectionId)
        return Header(
            id = inspectionId,
            fechaEpochMillis = ins.fechaEpochMillis,
            hora24 = ex?.optString("hora24") ?: "--:--",
            matAeronave = ins.matricula,
            tecnicoGrado = ex?.optString("tecnicoGrado") ?: "",
            tecnicoEspecialidad = ex?.optString("tecnicoEspecialidad") ?: "",
            tecnicoNombre = ins.nombre,
            hsTotales = ex?.optStringOrNull("hsTotales"),
            hsDisponibles = ex?.optStringOrNull("hsDisponibles"),
            tecnicoMatricula = ex?.optStringOrNull("tecnicoMatricula")
        )
    }

    /** Ítems listos para UI */
    suspend fun getItems(inspectionId: Long): List<Item> {
        val db = dao.getInspection(inspectionId) ?: return emptyList()
        return db.items.map { Item(id = it.id, title = it.title, checked = it.checked) }
    }

    /** Compatibilidad: acceso crudo a relación Room */
    suspend fun get(inspectionId: Long) = dao.getInspection(inspectionId)

    /* ---------------------------------------------
     * 🔽 NUEVO: utilidades específicas para sincronización
     * --------------------------------------------- */

    /**
     * Devuelve la lista de inspecciones marcadas como "dirty" (pendientes de sincronizar)
     * ya mapeadas a los modelos públicos del repo (Header + Items).
     */
    suspend fun getDirtyInspections(): List<InspectionWithItems> {
        val rows = dao.getDirtyInspections()
        return rows.map { db ->
            val ins = db.inspection
            val ex = readExtrasJson(ins.id)
            InspectionWithItems(
                header = Header(
                    id = ins.id,
                    fechaEpochMillis = ins.fechaEpochMillis,
                    hora24 = ex?.optString("hora24") ?: "--:--",
                    matAeronave = ins.matricula,
                    tecnicoGrado = ex?.optString("tecnicoGrado") ?: "",
                    tecnicoEspecialidad = ex?.optString("tecnicoEspecialidad") ?: "",
                    tecnicoNombre = ins.nombre,
                    hsTotales = ex?.optStringOrNull("hsTotales"),
                    hsDisponibles = ex?.optStringOrNull("hsDisponibles"),
                    tecnicoMatricula = ex?.optStringOrNull("tecnicoMatricula")
                ),
                items = db.items.map { Item(id = it.id, title = it.title, checked = it.checked) }
            )
        }
    }

    /**
     * Marca una inspección como sincronizada:
     *  - dirty = false
     *  - syncId = el ID retornado por el servidor / otro dispositivo
     *  - lastModified se actualiza al momento de la confirmación
     */
    suspend fun markSynced(inspectionId: Long, syncId: String) {
        val current = dao.getInspection(inspectionId) ?: return
        val now = System.currentTimeMillis()

        val updated = current.inspection.copy(
            syncId = syncId,
            dirty = false,
            lastModified = now
        )
        dao.updateInspection(updated)
    }

    /* ---------------------------------------------
     * Extras JSON sidecar (no tocar Room schema)
     * --------------------------------------------- */
    private fun extrasDir(): File =
        File(context.filesDir, "preflight_extras").apply { if (!exists()) mkdirs() }

    private fun extrasFile(id: Long): File =
        File(extrasDir(), "inspection_$id.json")

    private fun writeExtrasJson(
        id: Long,
        hora24: String,
        tecnicoGrado: String,
        tecnicoEspecialidad: String,
        hsTotales: String?,
        hsDisponibles: String?,
        tecnicoMatricula: String?
    ) {
        val obj = JSONObject().apply {
            put("hora24", hora24)
            put("tecnicoGrado", tecnicoGrado)
            put("tecnicoEspecialidad", tecnicoEspecialidad)
            put("hsTotales", hsTotales ?: JSONObject.NULL)
            put("hsDisponibles", hsDisponibles ?: JSONObject.NULL)
            put("tecnicoMatricula", tecnicoMatricula ?: JSONObject.NULL)
        }
        extrasFile(id).writeText(obj.toString())
    }

    private fun readExtrasJson(id: Long): JSONObject? {
        val f = extrasFile(id)
        if (!f.exists()) return null
        return try { JSONObject(f.readText()) } catch (_: Throwable) { null }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null
}
