package com.cabgon.blackhawk.data.local.enruta

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "en_ruta_recarga",
    foreignKeys = [
        ForeignKey(
            entity = EnRutaStatusEntity::class,
            parentColumns = ["id"],
            childColumns = ["enRutaId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("enRutaId")]
)
data class EnRutaRecargaEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    val enRutaId: Long,             // FK a EnRutaStatusEntity.id

    val folio: Int,                 // 24070
    val recargaLitros: Int,         // 902
    val ubicacion: String,          // texto libre

    val createdAt: Long,            // millis
    val isDirty: Boolean = true     // pendiente de subir a nube
)
