package com.cabgon.blackhawk.ui.admin.enruta

import com.cabgon.blackhawk.data.remote.enruta.EnRutaRemoteConstants
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await

class AdminEnRutaRepository {

    private val db = FirebaseFirestore.getInstance()
    private var reg: ListenerRegistration? = null

    private val col = db.collection(EnRutaRemoteConstants.COLLECTION_EN_RUTA)

    fun observe(onUpdate: (List<AdminEnRutaItem>) -> Unit) {
        reg?.remove()
        reg = col.addSnapshotListener { snap, _ ->
            val list = snap?.documents?.map { d ->
                AdminEnRutaItem(
                    matAeronave = d.id,
                    categoria = d.getString("categoria").orEmpty(),
                    ubicacion = d.getString("ubicacion").orEmpty(),
                    lastEditTimestamp = d.getLong("lastEditTimestamp") ?: 0L,
                    lastEditorUserId = d.getString("lastEditorUserId")
                )
            } ?: emptyList()

            onUpdate(list.sortedBy { it.matAeronave })
        }
    }

    suspend fun createDefault(mat: String, actorUid: String, now: Long, lastEditDate: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val doc = col.document(mat)

                // Schema compatible con tu mapper toEnRutaStatusEntity()
                val data = mapOf(
                    "matAeronave" to mat,
                    "lastEditDate" to lastEditDate,
                    "lastEditTimestamp" to now,
                    "lastEditorUserId" to actorUid,

                    "categoria" to "A",
                    "ubicacion" to "",
                    "tipoOps" to "",

                    "horasVuelo" to 0.0,
                    "horasTotales" to 0.0,
                    "horasDisponibles" to 0.0,
                    "proxInspeccion" to 40,

                    "motor1Lcf1" to 0,
                    "motor1Lcf2" to 0,
                    "motor1Index" to 0,
                    "motor1Horas" to 0,

                    "motor2Lcf1" to 0,
                    "motor2Lcf2" to 0,
                    "motor2Index" to 0,
                    "motor2Horas" to 0,

                    "apuHoras" to 0,
                    "apuEventos" to 0,

                    "reportes" to "",

                    "lastSyncSource" to "admin_panel"
                )

                // Si ya existe, lo dejamos como está (no sobrescribimos a lo bruto)
                val existing = doc.get().await()
                if (!existing.exists()) {
                    doc.set(data).await()
                }

                true
            }.getOrElse { false }
        }

    suspend fun delete(mat: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val doc = col.document(mat)

            // borrar subcolección recargas (si existe)
            val recSnap = doc.collection(EnRutaRemoteConstants.SUBCOLLECTION_RECARGAS).get().await()
            for (d in recSnap.documents) d.reference.delete().await()

            // borrar documento
            doc.delete().await()

            true
        }.getOrElse { false }
    }

    fun close() {
        reg?.remove()
        reg = null
    }
}
