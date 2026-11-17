package com.cabgon.blackhawk.data.inspection80h

import androidx.room.*

/**
 * Header de inspección 80 horas UH-60L.
 * Por ahora es minimalista; luego puedes agregar más campos.
 */
@Entity(tableName = "inspection_80h")
data class Inspection80hHeader(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val fechaEpochMillis: Long,
    val matricula: String,
    val tecnicoNombre: String,
    val completed: Boolean = false
)

/**
 * Ítem de checklist de inspección 80 horas.
 */
@Entity(
    tableName = "inspection_80h_item",
    foreignKeys = [
        ForeignKey(
            entity = Inspection80hHeader::class,
            parentColumns = ["id"],
            childColumns = ["inspectionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("inspectionId")]
)
data class Inspection80hItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val inspectionId: Long,
    val title: String,
    val orderIndex: Int,
    val checked: Boolean = false,

    // Campos para sincronización futura
    val syncId: String? = null,
    val lastModified: Long = 0L,
    val dirty: Boolean = true,
    val originDeviceId: String? = null
)

/**
 * Relación 1:N (HEADER + ITEMS) para usar desde un futuro repositorio.
 */
data class Inspection80hWithItems(
    @Embedded val header: Inspection80hHeader,
    @Relation(
        parentColumn = "id",
        entityColumn = "inspectionId",
        entity = Inspection80hItem::class
    )
    val items: List<Inspection80hItem>
)
