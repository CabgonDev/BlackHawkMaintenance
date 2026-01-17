package com.cabgon.blackhawk.data.preflight

import androidx.room.*

@Entity(tableName = "preflight_inspection")
data class PreflightInspection(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val fechaEpochMillis: Long,
    val matricula: String,          // Mat. Aeronave
    val nombre: String,             // Técnico (nombre)
    val completed: Boolean = false,

    // Campos preparados para sincronización futura
    val syncId: String? = null,         // ID global/servidor
    val lastModified: Long = 0L,        // último cambio (epoch millis)
    val dirty: Boolean = true,          // true = pendiente de sync
    val originDeviceId: String? = null  // identificador del dispositivo
)

@Entity(
    tableName = "preflight_item",
    foreignKeys = [
        ForeignKey(
            entity = PreflightInspection::class,
            parentColumns = ["id"],
            childColumns = ["inspectionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("inspectionId")]
)
data class PreflightItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val inspectionId: Long,
    val title: String,
    val orderIndex: Int,
    val checked: Boolean = false
)

/** Relación 1:N para Room (para el Repository). */
data class InspectionWithItems(
    @Embedded val inspection: PreflightInspection,
    @Relation(
        parentColumn = "id",
        entityColumn = "inspectionId",
        entity = PreflightItem::class
    )
    val items: List<PreflightItem>
)
