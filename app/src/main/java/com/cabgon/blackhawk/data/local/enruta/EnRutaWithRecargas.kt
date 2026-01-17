package com.cabgon.blackhawk.data.local.enruta

import androidx.room.Embedded
import androidx.room.Relation

data class EnRutaWithRecargas(
    @Embedded
    val status: EnRutaStatusEntity,

    @Relation(
        parentColumn = "id",
        entityColumn = "enRutaId"
    )
    val recargas: List<EnRutaRecargaEntity>
)
