package com.cabgon.blackhawk.data.inspection120h

import androidx.room.*

@Entity(tableName = "inspection_120h")
data class Inspection120hHeader(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val fechaEpochMillis: Long,
    val matricula: String,
    val tecnicoNombre: String,
    val completed: Boolean = false
)

@Entity(
    tableName = "inspection_120h_item",
    foreignKeys = [
        ForeignKey(
            entity = Inspection120hHeader::class,
            parentColumns = ["id"],
            childColumns = ["inspectionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("inspectionId")]
)
data class Inspection120hItem(
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

data class Inspection120hWithItems(
    @Embedded val header: Inspection120hHeader,
    @Relation(
        parentColumn = "id",
        entityColumn = "inspectionId",
        entity = Inspection120hItem::class
    )
    val items: List<Inspection120hItem>
)
