package com.cabgon.blackhawk.data.inspection40h

import androidx.room.*

/**
 * Header de inspección 40 horas UH-60L.
 */
@Entity(tableName = "inspection_40h")
data class Inspection40hHeader(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,

    // Encabezado
    val fechaEpochMillis: Long,          // Fecha/hora tomada del dispositivo
    val matAeronave: String,             // 1091, 1092, etc.
    val hsTotales: Float,                // > 0

    // Supervisor (sin autollenado)
    val supervisorGrade: String,         // Grado
    val supervisorSpecialty: String,     // F.A.E.E.A. o F.A.E.M.A.
    val supervisorFullName: String,      // Nombre completo
    val supervisorMatricula: String,     // X-1766403...

    // Estado
    val completed: Boolean = false,

    // Campos preparados para sincronización futura
    val syncId: String? = null,          // ID global/servidor (a futuro)
    val lastModified: Long = 0L,         // último cambio (epoch millis)
    val dirty: Boolean = true            // true = pendiente de sync
)

/**
 * Item de la inspección 40 h.
 * Cada registro representa un paso del checklist.
 */
@Entity(
    tableName = "inspection_40h_item",
    indices = [Index("inspectionId")]
)
data class Inspection40hItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val inspectionId: Long,              // FK a Inspection40hHeader.id

    // Definición del ítem (desde el JSON)
    val code: String,                    // ID único del ítem (ej. "40H-001")
    val shortText: String,               // Texto corto (UI)
    val longText: String,                // Texto largo (modal)
    val orderIndex: Int,                 // Orden en el checklist

    // Estado del check
    val checked: Boolean = false,
    val checkedByUid: String? = null,
    val checkedByFirstLastName: String? = null,
    val checkedAt: Long? = null,

    // Sync futura
    val lastModified: Long = 0L,
    val dirty: Boolean = true
)


/**
 * Relación 1:N (HEADER + ITEMS) para usar desde el repositorio.
 */
data class Inspection40hWithItems(
    @Embedded val header: Inspection40hHeader,
    @Relation(
        parentColumn = "id",
        entityColumn = "inspectionId",
        entity = Inspection40hItem::class
    )
    val items: List<Inspection40hItem>
)
