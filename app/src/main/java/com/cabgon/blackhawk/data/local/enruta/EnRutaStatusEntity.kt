package com.cabgon.blackhawk.data.local.enruta

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "en_ruta_status")
data class EnRutaStatusEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    val matAeronave: String,                 // 1091, 1092, etc.

    // Metadatos
    val lastEditDate: String,                // "09/12/2025" para mostrar
    val lastEditTimestamp: Long,             // System.currentTimeMillis()
    val lastEditorUserId: String?,           // UID del técnico que editó

    // Info general
    val categoria: String,                   // "A" o "B"
    val ubicacion: String,                   // texto libre
    val tipoOps: String,                     // texto libre

    // Horas vuelo/totales
    val horasVuelo: Double,                  // 2.8
    val horasTotales: Double,                // 3848.8
    val horasDisponibles: Double,            // 11.2
    val proxInspeccion: Int,                 // 40 / 80 / 120 / 480

    // Motor 1
    val motor1Lcf1: Int,
    val motor1Lcf2: Int,
    val motor1Index: Int,
    val motor1Horas: Int,

    // Motor 2
    val motor2Lcf1: Int,
    val motor2Lcf2: Int,
    val motor2Index: Int,
    val motor2Horas: Int,

    // APU
    val apuHoras: Int,
    val apuEventos: Int,

    // Reportes (texto libre)
    val reportes: String,

    // Flags para sync
    val isDirty: Boolean = true,             // true = pendiente de subir a nube
    val lastSyncTimestamp: Long? = null      // cuándo se sincronizó con éxito
)
