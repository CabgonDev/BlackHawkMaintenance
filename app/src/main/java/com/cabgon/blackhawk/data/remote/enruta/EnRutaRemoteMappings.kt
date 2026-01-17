package com.cabgon.blackhawk.data.remote.enruta

import com.cabgon.blackhawk.data.local.enruta.EnRutaRecargaEntity
import com.cabgon.blackhawk.data.local.enruta.EnRutaStatusEntity
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.QuerySnapshot

// ----------------------
// ENTITY -> FIRESTORE
// ----------------------

// Status local -> mapa para Firestore
fun EnRutaStatusEntity.toFirestoreMap(): Map<String, Any?> =
    mapOf(
        "matAeronave" to matAeronave,
        "lastEditDate" to lastEditDate,
        "lastEditTimestamp" to lastEditTimestamp,
        "lastEditorUserId" to lastEditorUserId,

        "categoria" to categoria,
        "ubicacion" to ubicacion,
        "tipoOps" to tipoOps,

        "horasVuelo" to horasVuelo,
        "horasTotales" to horasTotales,
        "horasDisponibles" to horasDisponibles,
        "proxInspeccion" to proxInspeccion,

        "motor1Lcf1" to motor1Lcf1,
        "motor1Lcf2" to motor1Lcf2,
        "motor1Index" to motor1Index,
        "motor1Horas" to motor1Horas,

        "motor2Lcf1" to motor2Lcf1,
        "motor2Lcf2" to motor2Lcf2,
        "motor2Index" to motor2Index,
        "motor2Horas" to motor2Horas,

        "apuHoras" to apuHoras,
        "apuEventos" to apuEventos,

        "reportes" to reportes,

        // extras para auditoría / debug
        "updatedAtServer" to Timestamp.now(),
        "lastSyncSource" to "android_tech"
    )

// Recarga local -> mapa para Firestore
fun EnRutaRecargaEntity.toFirestoreMap(): Map<String, Any?> =
    mapOf(
        "folio" to folio,
        "recargaLitros" to recargaLitros,
        "ubicacion" to ubicacion,
        "createdAt" to createdAt
    )

// ----------------------
// FIRESTORE -> ENTITY
// ----------------------

// Documento Firestore -> EnRutaStatusEntity
fun DocumentSnapshot.toEnRutaStatusEntity(
    localId: Long? = null
): EnRutaStatusEntity? {
    if (!exists()) return null

    val mat = getString("matAeronave") ?: id // fallback al docId por si acaso

    return EnRutaStatusEntity(
        id = localId ?: 0L,
        matAeronave = mat,

        lastEditDate = getString("lastEditDate") ?: "",
        lastEditTimestamp = getLong("lastEditTimestamp") ?: 0L,
        lastEditorUserId = getString("lastEditorUserId"),

        categoria = getString("categoria") ?: "",
        ubicacion = getString("ubicacion") ?: "",
        tipoOps = getString("tipoOps") ?: "",

        horasVuelo = getDouble("horasVuelo") ?: 0.0,
        horasTotales = getDouble("horasTotales") ?: 0.0,
        horasDisponibles = getDouble("horasDisponibles") ?: 0.0,
        proxInspeccion = (getLong("proxInspeccion") ?: 0L).toInt(),

        motor1Lcf1 = (getLong("motor1Lcf1") ?: 0L).toInt(),
        motor1Lcf2 = (getLong("motor1Lcf2") ?: 0L).toInt(),
        motor1Index = (getLong("motor1Index") ?: 0L).toInt(),
        motor1Horas = (getLong("motor1Horas") ?: 0L).toInt(),

        motor2Lcf1 = (getLong("motor2Lcf1") ?: 0L).toInt(),
        motor2Lcf2 = (getLong("motor2Lcf2") ?: 0L).toInt(),
        motor2Index = (getLong("motor2Index") ?: 0L).toInt(),
        motor2Horas = (getLong("motor2Horas") ?: 0L).toInt(),

        apuHoras = (getLong("apuHoras") ?: 0L).toInt(),
        apuEventos = (getLong("apuEventos") ?: 0L).toInt(),

        reportes = getString("reportes") ?: "",

        isDirty = false,                          // si viene de Firestore, asumimos sincronizado
        lastSyncTimestamp = System.currentTimeMillis()
    )
}

// Subcolección de recargas -> lista de EnRutaRecargaEntity
fun QuerySnapshot.toEnRutaRecargaEntities(enRutaId: Long): List<EnRutaRecargaEntity> {
    return documents.mapNotNull { doc ->
        val folio = doc.getLong("folio") ?: return@mapNotNull null
        val litros = doc.getLong("recargaLitros") ?: return@mapNotNull null
        val ubicacion = doc.getString("ubicacion") ?: ""

        val createdAt = doc.getLong("createdAt") ?: 0L

        EnRutaRecargaEntity(
            id = 0L,                   // Room lo autogenera
            enRutaId = enRutaId,
            folio = folio.toInt(),
            recargaLitros = litros.toInt(),
            ubicacion = ubicacion,
            createdAt = createdAt,
            isDirty = false
        )
    }
}
