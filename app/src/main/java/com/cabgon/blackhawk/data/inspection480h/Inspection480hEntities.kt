package com.cabgon.blackhawk.data.inspection480h

import androidx.room.*

@Entity(tableName = "inspection_480h")
data class Inspection480hHeader(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val fechaEpochMillis: Long,
    val matricula: String,
    val tecnicoNombre: String,
    val completed: Boolean = false
)

@Entity(
    tableName = "inspection_480h_item",
    foreignKeys = [
        ForeignKey(
            entity = Inspection480hHeader::class,
            parentColumns = ["id"],
            childColumns = ["inspectionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("inspectionId")]
)
data class Inspection480hItem(
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

data class Inspection480hWithItems(
    @Embedded val header: Inspection480hHeader,
    @Relation(
        parentColumn = "id",
        entityColumn = "inspectionId",
        entity = Inspection480hItem::class
    )
    val items: List<Inspection480hItem>
)
