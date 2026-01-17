package com.cabgon.blackhawk.data.inspection40h

import android.content.Context
import com.cabgon.blackhawk.data.db.AppDbProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * Repository oficial para inspecciones 40H.
 * Controla:
 * - Creación de inspección
 * - Carga del checklist desde JSON
 * - Observación de 40H (solo header o header+items)
 * - Actualización de checks (incluye UID + apellido)
 * - Preparado para sincronización futura
 */
class Inspection40hRepository(private val context: Context) {

    private val dao: Inspection40hDao =
        AppDbProvider.get(context).inspection40hDao()

    /* ╔════════════════════════════════════════════════╗
       ║              MODELOS DE DOMINIO                ║
       ╚════════════════════════════════════════════════╝ */

    data class Header(
        val id: Long,
        val fechaEpochMillis: Long,
        val matAeronave: String,
        val hsTotales: Float,
        val supervisorGrade: String,
        val supervisorSpecialty: String,
        val supervisorFullName: String,
        val supervisorMatricula: String,
        val completed: Boolean
    )

    data class Item(
        val id: Long,
        val code: String,
        val shortText: String,
        val longText: String,
        val checked: Boolean,
        val checkedByFirstLastName: String?,
        val checkedAt: Long?
    )

    data class InspectionWithItems(
        val header: Header,
        val items: List<Item>
    )

    /* ╔════════════════════════════════════════════════╗
       ║           DEFINICIÓN JSON DEL CHECKLIST        ║
       ╚════════════════════════════════════════════════╝ */

    data class ChecklistDefItem(
        val code: String,
        val shortText: String,
        val longText: String
    )

    private fun loadChecklistDefinition(): List<ChecklistDefItem> {
        val am = context.assets
        val json = am.open("checklists/inspection_40h.json")
            .bufferedReader().use { it.readText() }

        val root = JSONObject(json)
        val arr: JSONArray = root.getJSONArray("items")

        val list = mutableListOf<ChecklistDefItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)

            val code = obj.optString("code").ifBlank {
                "40H-%03d".format(i + 1)
            }
            val shortText = obj.optString("short").ifBlank {
                obj.optString("long")
            }
            val longText = obj.optString("long")

            list += ChecklistDefItem(
                code = code,
                shortText = shortText,
                longText = longText
            )
        }
        return list
    }

    /* ╔════════════════════════════════════════════════╗
       ║     OBSERVACIÓN PARA LA LISTA (SOLO HEADERS)   ║
       ╚════════════════════════════════════════════════╝ */

    fun observeInspections(): Flow<List<Header>> {
        return dao.observeInspections().map { rows ->
            rows.map { row ->
                val h = row.header
                Header(
                    id = h.id,
                    fechaEpochMillis = h.fechaEpochMillis,
                    matAeronave = h.matAeronave,
                    hsTotales = h.hsTotales,
                    supervisorGrade = h.supervisorGrade,
                    supervisorSpecialty = h.supervisorSpecialty,
                    supervisorFullName = h.supervisorFullName,
                    supervisorMatricula = h.supervisorMatricula,
                    completed = h.completed
                )
            }
        }
    }

    /* ╔════════════════════════════════════════════════╗
       ║   OBSERVACIÓN PARA LISTA CON PORCENTAJE (%)    ║
       ╚════════════════════════════════════════════════╝ */

    fun observeWithItems(): Flow<List<InspectionWithItems>> {
        return dao.observeInspections().map { rows ->
            rows.map { row ->
                mapRowToDomain(row)
            }
        }
    }

    /* ╔════════════════════════════════════════════════╗
       ║       OBSERVAR UNA INSPECCIÓN INDIVIDUAL       ║
       ╚════════════════════════════════════════════════╝ */

    fun observeInspection(id: Long): Flow<InspectionWithItems?> {
        return dao.observeInspection(id).map { row ->
            row?.let { mapRowToDomain(it) }
        }
    }

    suspend fun getInspection(id: Long): InspectionWithItems? {
        val row = dao.getInspection(id) ?: return null
        return mapRowToDomain(row)
    }

    private fun mapRowToDomain(row: Inspection40hWithItems): InspectionWithItems {
        val h = row.header

        val header = Header(
            id = h.id,
            fechaEpochMillis = h.fechaEpochMillis,
            matAeronave = h.matAeronave,
            hsTotales = h.hsTotales,
            supervisorGrade = h.supervisorGrade,
            supervisorSpecialty = h.supervisorSpecialty,
            supervisorFullName = h.supervisorFullName,
            supervisorMatricula = h.supervisorMatricula,
            completed = h.completed
        )

        val items = row.items
            .sortedBy { it.orderIndex }
            .map { e ->
                Item(
                    id = e.id,
                    code = e.code,
                    shortText = e.shortText,
                    longText = e.longText,
                    checked = e.checked,
                    checkedByFirstLastName = e.checkedByFirstLastName,
                    checkedAt = e.checkedAt
                )
            }


        return InspectionWithItems(header, items)
    }

    /* ╔════════════════════════════════════════════════╗
       ║         CREACIÓN DE NUEVA INSPECCIÓN 40H        ║
       ╚════════════════════════════════════════════════╝ */

    suspend fun createInspection40h(
        fechaEpochMillis: Long,
        matAeronave: String,
        hsTotales: Float,
        supervisorGrade: String,
        supervisorSpecialty: String,
        supervisorFullName: String,
        supervisorMatricula: String
    ): Long {
        val now = System.currentTimeMillis()

        val headerId = dao.insertHeader(
            Inspection40hHeader(
                fechaEpochMillis = fechaEpochMillis,
                matAeronave = matAeronave.trim(),
                hsTotales = hsTotales,
                supervisorGrade = supervisorGrade.trim(),
                supervisorSpecialty = supervisorSpecialty.trim(),
                supervisorFullName = supervisorFullName.trim(),
                supervisorMatricula = supervisorMatricula.trim(),
                completed = false,
                lastModified = now,
                dirty = true
            )
        )

        val template = loadChecklistDefinition()

        val items = template.mapIndexed { index, def ->
            Inspection40hItem(
                inspectionId = headerId,
                code = def.code,
                shortText = def.shortText,
                longText = def.longText,
                orderIndex = index,
                checked = false,
                checkedByUid = null,
                checkedByFirstLastName = null,
                checkedAt = null,
                lastModified = now,
                dirty = true
            )
        }

        dao.insertItems(items)
        return headerId
    }

    /* ╔════════════════════════════════════════════════╗
       ║   MARCAR / DESMARCAR UN ÍTEM DEL CHECKLIST     ║
       ╚════════════════════════════════════════════════╝ */

    suspend fun setItemChecked(
        itemId: Long,
        checked: Boolean,
        uid: String?,
        firstLastName: String?
    ) {
        val now = System.currentTimeMillis()
        val item = dao.getItem(itemId) ?: return

        val newItem =
            if (checked) {
                item.copy(
                    checked = true,
                    checkedByUid = uid,
                    checkedByFirstLastName = firstLastName,
                    checkedAt = now,
                    lastModified = now,
                    dirty = true
                )
            } else {
                item.copy(
                    checked = false,
                    checkedByUid = null,
                    checkedByFirstLastName = null,
                    checkedAt = null,
                    lastModified = now,
                    dirty = true
                )
            }

        dao.updateItem(newItem)

        val items = dao.getItemsForInspection(item.inspectionId)
        val allChecked = items.all { it.checked }

        val header = dao.getHeader(item.inspectionId) ?: return
        val updatedHeader = header.copy(
            completed = allChecked,
            lastModified = now,
            dirty = true
        )
        dao.updateHeader(updatedHeader)
    }

    /* ╔════════════════════════════════════════════════╗
       ║               ELIMINAR INSPECCIÓN              ║
       ╚════════════════════════════════════════════════╝ */

    suspend fun deleteInspection(id: Long) {
        dao.deleteInspection(id)
    }
}
