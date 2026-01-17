package com.cabgon.blackhawk.data.enruta

import com.cabgon.blackhawk.data.local.enruta.EnRutaDao
import com.cabgon.blackhawk.data.local.enruta.EnRutaRecargaEntity
import com.cabgon.blackhawk.data.local.enruta.EnRutaStatusEntity
import com.cabgon.blackhawk.data.local.enruta.EnRutaWithRecargas
import com.cabgon.blackhawk.data.remote.enruta.EnRutaRemoteConstants.COLLECTION_EN_RUTA
import com.cabgon.blackhawk.data.remote.enruta.EnRutaRemoteConstants.SUBCOLLECTION_RECARGAS
import com.cabgon.blackhawk.data.remote.enruta.toEnRutaRecargaEntities
import com.cabgon.blackhawk.data.remote.enruta.toEnRutaStatusEntity
import com.cabgon.blackhawk.data.remote.enruta.toFirestoreMap
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import com.google.firebase.firestore.DocumentChange


class EnRutaRepository(
    private val dao: EnRutaDao,
    private val firestore: FirebaseFirestore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private var statusListener: ListenerRegistration? = null

    // --------- Lado Room: flujos y lecturas ---------

    fun observeEnRutaList(): Flow<List<EnRutaStatusEntity>> =
        dao.observeAllStatuses()

    suspend fun getEnRutaWithRecargasByMat(matAeronave: String): EnRutaWithRecargas? =
        withContext(ioDispatcher) {
            dao.getEnRutaWithRecargasByMat(matAeronave)
        }

    // --------- Guardar desde UI + subir a Firestore ---------

    suspend fun saveLocalAndSync(
        status: EnRutaStatusEntity,
        recargas: List<EnRutaRecargaEntity>
    ) = withContext(ioDispatcher) {
        val now = System.currentTimeMillis()

        val updatedStatus = status.copy(
            lastEditTimestamp = now,
            lastEditDate = status.lastEditDate, // asumes que ya viene formateado dd/MM/yyyy desde UI
            isDirty = true
        )

        val recargasDirty = recargas.map { it.copy(isDirty = true) }

        // 1) Guardar en Room (transacción)
        dao.updateEnRutaWithRecargas(updatedStatus, recargasDirty)

        // 2) Empujar a Firestore
        pushToFirestore(updatedStatus, recargasDirty)
    }

    private suspend fun pushToFirestore(
        status: EnRutaStatusEntity,
        recargas: List<EnRutaRecargaEntity>
    ) = withContext(ioDispatcher) {
        val docRef = firestore
            .collection(COLLECTION_EN_RUTA)
            .document(status.matAeronave)

        // Status
        val data = status.toFirestoreMap()

        // Guardar status (merge para no romper si en el futuro hay campos nuevos)
        docRef.set(data, SetOptions.merge()).await()

        // Recargas: borramos las anteriores y subimos las nuevas
        val recargasRef = docRef.collection(SUBCOLLECTION_RECARGAS)

        val existingRecargas = recargasRef.get().await()
        for (doc in existingRecargas.documents) {
            doc.reference.delete().await()
        }

        recargas.forEach { rec ->
            val recData = rec.toFirestoreMap()
            recargasRef.add(recData).await()
        }

        // Marcar como sincronizado en Room
        val local = dao.getStatusByMat(status.matAeronave)
        if (local != null) {
            val syncTime = System.currentTimeMillis()
            dao.updateStatus(
                local.copy(
                    isDirty = false,
                    lastSyncTimestamp = syncTime
                )
            )
            dao.markRecargasSyncedForEnRuta(local.id)
        }
    }

    // --------- QUITAR DE RUTA (local + Firestore) ---------

    suspend fun removeFromRuta(matAeronave: String) = withContext(ioDispatcher) {
        // 1) Borrar en Room
        val status = dao.getStatusByMat(matAeronave)
        if (status != null) {
            dao.deleteRecargasForEnRutaId(status.id)
            dao.deleteStatusByMat(matAeronave)
        }

        // 2) Borrar en Firestore
        val docRef = firestore
            .collection(COLLECTION_EN_RUTA)
            .document(matAeronave)

        try {
            // borrar subcolección "recargas"
            val recargasSnap = docRef.collection(SUBCOLLECTION_RECARGAS).get().await()
            for (doc in recargasSnap.documents) {
                doc.reference.delete().await()
            }

            // borrar el documento principal
            docRef.delete().await()
        } catch (_: Exception) {
            // si falla la parte remota, al menos ya se borró en local;
            // podrías loguear aquí si quieres
        }
    }


    // --------- Sincronizar pendientes (stub que puedes pulir después) ---------

    suspend fun syncAllDirtyToFirestore() = withContext(ioDispatcher) {
        // Pendiente de implementar fino si lo necesitas
    }

    // --------- Listener en tiempo real (Firestore -> Room) ---------

    fun startRealtimeStatusListener(scope: CoroutineScope) {
        if (statusListener != null) return  // ya está iniciado

        statusListener = firestore
            .collection(COLLECTION_EN_RUTA)
            .addSnapshotListener { snapshots, error ->
                if (error != null || snapshots == null) {
                    return@addSnapshotListener
                }

                for (change in snapshots.documentChanges) {
                    val doc = change.document

                    when (change.type) {
                        DocumentChange.Type.REMOVED -> {
                            // Documento borrado en Firestore -> lo borramos en Room
                            val mat = doc.id
                            scope.launch(ioDispatcher) {
                                val local = dao.getStatusByMat(mat)
                                if (local != null) {
                                    dao.deleteRecargasForEnRutaId(local.id)
                                    dao.deleteStatusByMat(mat)
                                }
                            }
                        }

                        DocumentChange.Type.ADDED,
                        DocumentChange.Type.MODIFIED -> {
                            val remote = doc.toEnRutaStatusEntity() ?: continue

                            scope.launch(ioDispatcher) {
                                val local = dao.getStatusByMat(remote.matAeronave)

                                val shouldApplyRemote =
                                    local == null || remote.lastEditTimestamp > (local.lastEditTimestamp)

                                if (shouldApplyRemote) {
                                    val existingId = local?.id ?: 0L
                                    val toSave = remote.copy(
                                        id = existingId,
                                        isDirty = false,
                                        lastSyncTimestamp = System.currentTimeMillis()
                                    )

                                    val enRutaId = dao.upsertStatusByMat(toSave)

                                    try {
                                        val recargasSnap = doc.reference
                                            .collection(SUBCOLLECTION_RECARGAS)
                                            .get()
                                            .await()

                                        val recargasEntities =
                                            recargasSnap.toEnRutaRecargaEntities(enRutaId)

                                        dao.deleteRecargasForEnRutaId(enRutaId)
                                        dao.insertRecargas(recargasEntities)
                                    } catch (_: Throwable) {
                                        // si falla recargas, al menos el status ya se actualizó
                                    }
                                } else {
                                    // local más reciente: se subirá en el siguiente save/sync
                                }
                            }
                        }
                    }
                }
            }
    }


    fun stopRealtimeStatusListener() {
        statusListener?.remove()
        statusListener = null
    }
}
