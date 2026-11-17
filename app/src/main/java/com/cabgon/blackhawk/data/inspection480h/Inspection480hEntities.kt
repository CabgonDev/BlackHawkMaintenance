package com.cabgon.blackhawk.data.inspection480h

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * Header de inspección 480 horas UH-60L.
 * Se deja con el mismo flujo y contrato de datos que 40H.
 */
@Entity(tableName = "inspection_480h")
data class Inspection480hHeader(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,

    // Encabezado
    val fechaEpochMillis: Long,
    val matAeronave: String,
    val hsTotales: Float,

    // Supervisor
    val supervisorGrade: String,
    val supervisorSpecialty: String,
    val supervisorFullName: String,
    val supervisorMatricula: String,

    // Estado
    val completed: Boolean = false,
    val state: String = "draft",
    val lastSavedAt: Long? = null,
    val lastSavedByUid: String? = null,
    val lastSavedByFirstLastName: String? = null,
    val closedAt: Long? = null,
    val closedByUid: String? = null,
    val closedByFirstLastName: String? = null,

    // Campos preparados para sincronización futura
    val syncId: String? = null,
    val lastModified: Long = 0L,
    val dirty: Boolean = true
)

/**
 * Item de la inspección 480 h.
 * Igual que 40H: cada registro representa un paso real del checklist.
 */
@Entity(
    tableName = "inspection_480h_item",
    indices = [Index("inspectionId")]
)
data class Inspection480hItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val inspectionId: Long,

    // Definición del ítem (desde el JSON)
    val code: String,
    val shortText: String,
    val longText: String,
    val orderIndex: Int,

    // Estado del check
    val checked: Boolean = false,
    val checkedByUid: String? = null,
    val checkedByFirstLastName: String? = null,
    val checkedAt: Long? = null,

    // Sync futura
    val lastModified: Long = 0L,
    val dirty: Boolean = true
)

data class Inspection480hWithItems(
    @Embedded val header: Inspection480hHeader,
    @Relation(
        parentColumn = "id",
        entityColumn = "inspectionId",
        entity = Inspection480hItem::class
    )
    val items: List<Inspection480hItem>
)
