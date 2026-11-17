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
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class EnRutaRepository(
    private val dao: EnRutaDao,
    private val firestore: FirebaseFirestore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private var statusListener: ListenerRegistration? = null

    // --------- Lado Room ---------

    fun observeEnRutaList(): Flow<List<EnRutaStatusEntity>> =
        dao.observeAllStatuses()

    fun observeEnRutaWithRecargasByMat(matAeronave: String): Flow<EnRutaWithRecargas?> =
        dao.observeEnRutaWithRecargasByMat(matAeronave)

    suspend fun getEnRutaWithRecargasByMat(matAeronave: String): EnRutaWithRecargas? =
        withContext(ioDispatcher) { dao.getEnRutaWithRecargasByMat(matAeronave) }

    // --------- Guardar desde UI + subir a Firestore ---------

    suspend fun saveLocalAndSync(
        status: EnRutaStatusEntity,
        recargas: List<EnRutaRecargaEntity>
    ) = withContext(ioDispatcher) {
        val now = System.currentTimeMillis()

        val updatedStatus = status.copy(
            lastEditTimestamp = now,
            // lastEditDate debe venir ya formateado desde UI/ViewModel (como lo haces)
            isDirty = true
        )

        val recargasDirty = recargas.map { it.copy(isDirty = true) }

        dao.updateEnRutaWithRecargas(updatedStatus, recargasDirty)

        pushToFirestore(updatedStatus, recargasDirty)
    }

    private suspend fun pushToFirestore(
        status: EnRutaStatusEntity,
        recargas: List<EnRutaRecargaEntity>
    ) = withContext(ioDispatcher) {
        val docRef = firestore
            .collection(COLLECTION_EN_RUTA)
            .document(status.matAeronave.trim())

        docRef.set(status.toFirestoreMap(), SetOptions.merge()).await()

        val recargasRef = docRef.collection(SUBCOLLECTION_RECARGAS)

        val existingRecargas = recargasRef.get().await()
        for (doc in existingRecargas.documents) {
            doc.reference.delete().await()
        }

        recargas.forEach { rec ->
            recargasRef.add(rec.toFirestoreMap()).await()
        }

        val local = dao.getStatusByMat(status.matAeronave.trim())
        if (local != null) {
            val syncTime = System.currentTimeMillis()
            dao.updateStatus(local.copy(isDirty = false, lastSyncTimestamp = syncTime))
            dao.markRecargasSyncedForEnRuta(local.id)
        }
    }

    // --------- QUITAR DE RUTA (local + Firestore) ---------

    suspend fun removeFromRuta(matAeronave: String) = withContext(ioDispatcher) {
        val mat = matAeronave.trim()

        val status = dao.getStatusByMat(mat)
        if (status != null) {
            dao.deleteRecargasForEnRutaId(status.id)
            dao.deleteStatusByMat(mat)
        }

        val docRef = firestore.collection(COLLECTION_EN_RUTA).document(mat)

        try {
            val recargasSnap = docRef.collection(SUBCOLLECTION_RECARGAS).get().await()
            for (doc in recargasSnap.documents) doc.reference.delete().await()
            docRef.delete().await()
        } catch (_: Exception) {
            // ya se borró local; opcional log
        }
    }

    // --------- REFRESH MANUAL (pull-to-refresh) ---------

    suspend fun refreshFromFirestoreOnce() = withContext(ioDispatcher) {
        val snap = firestore.collection(COLLECTION_EN_RUTA).get().await()

        val remoteDocs = snap.documents
        val remoteIds: Set<String> = remoteDocs.map { it.id.trim() }.toSet()
        val remoteMatsField: Set<String> = remoteDocs.mapNotNull { it.getString("matAeronave")?.trim() }.toSet()

        // 1) RECONCILIAR: borrar huérfanos locales que ya no existen en remoto (sin tocar dirty)
        val locals = dao.getAllStatusesOnce()
        for (local in locals) {
            if (local.isDirty) continue
            val localMat = local.matAeronave.trim()
            val exists = remoteIds.contains(localMat) || remoteMatsField.contains(localMat)
            if (!exists) {
                dao.deleteRecargasForEnRutaId(local.id)
                dao.deleteStatusByMat(localMat)
            }
        }

        // 2) APLICAR REMOTO -> LOCAL (docId manda como llave)
        for (doc in remoteDocs) {
            val docIdMat = doc.id.trim()

            // Buscar local por docId o por campo (por si hay histórico mal guardado)
            val localByDocId = dao.getStatusByMat(docIdMat)
            val fieldMat = doc.getString("matAeronave")?.trim()
            val localByField = if (!fieldMat.isNullOrBlank() && fieldMat != docIdMat) dao.getStatusByMat(fieldMat) else null
            val local = localByDocId ?: localByField

            val remote = doc.toEnRutaStatusEntity(localId = local?.id) ?: continue

            // Normalizar: guardamos SIEMPRE con matAeronave = docId
            val toSave = remote.copy(
                matAeronave = docIdMat,
                isDirty = false,
                lastSyncTimestamp = System.currentTimeMillis()
            )

            val enRutaId = dao.upsertStatusByMat(toSave)

            try {
                val recSnap = doc.reference.collection(SUBCOLLECTION_RECARGAS).get().await()
                val recEntities = recSnap.toEnRutaRecargaEntities(enRutaId)
                dao.deleteRecargasForEnRutaId(enRutaId)
                dao.insertRecargas(recEntities)
            } catch (_: Throwable) {
                // status ya quedó
            }

            // Si existía un registro local viejo con fieldMat diferente, elimínalo (ya quedó normalizado)
            if (localByField != null && fieldMat != null && fieldMat != docIdMat) {
                if (!localByField.isDirty) {
                    dao.deleteRecargasForEnRutaId(localByField.id)
                    dao.deleteStatusByMat(fieldMat)
                }
            }
        }
    }

    // --------- Listener en tiempo real (Firestore -> Room) ---------

    fun startRealtimeStatusListener(scope: CoroutineScope) {
        if (statusListener != null) return

        statusListener = firestore
            .collection(COLLECTION_EN_RUTA)
            .addSnapshotListener { snapshots, error ->
                if (error != null || snapshots == null) return@addSnapshotListener

                val remoteIdsNow = snapshots.documents.map { it.id.trim() }.toSet()
                val remoteMatsNow = snapshots.documents.mapNotNull { it.getString("matAeronave")?.trim() }.toSet()

                for (change in snapshots.documentChanges) {
                    val doc = change.document

                    when (change.type) {
                        DocumentChange.Type.REMOVED -> {
                            // BORRADO ROBUSTO: docId + campo matAeronave
                            val idMat = doc.id.trim()
                            val fieldMat = doc.getString("matAeronave")?.trim()

                            scope.launch(ioDispatcher) {
                                val candidates = listOfNotNull(idMat.takeIf { it.isNotBlank() }, fieldMat?.takeIf { it.isNotBlank() }).distinct()
                                for (mat in candidates) {
                                    val local = dao.getStatusByMat(mat)
                                    if (local != null) {
                                        dao.deleteRecargasForEnRutaId(local.id)
                                        dao.deleteStatusByMat(mat)
                                    }
                                }
                            }
                        }

                        DocumentChange.Type.ADDED,
                        DocumentChange.Type.MODIFIED -> {
                            scope.launch(ioDispatcher) {
                                val docIdMat = doc.id.trim()

                                val local = dao.getStatusByMat(docIdMat)
                                    ?: doc.getString("matAeronave")?.trim()?.let { if (it != docIdMat) dao.getStatusByMat(it) else null }

                                val remote = doc.toEnRutaStatusEntity(localId = local?.id) ?: return@launch

                                val normalizedRemote = remote.copy(matAeronave = docIdMat)

                                val shouldApplyRemote =
                                    local == null || normalizedRemote.lastEditTimestamp > local.lastEditTimestamp

                                if (shouldApplyRemote) {
                                    val toSave = normalizedRemote.copy(
                                        isDirty = false,
                                        lastSyncTimestamp = System.currentTimeMillis()
                                    )

                                    val enRutaId = dao.upsertStatusByMat(toSave)

                                    try {
                                        val recargasSnap = doc.reference.collection(SUBCOLLECTION_RECARGAS).get().await()
                                        val recargasEntities = recargasSnap.toEnRutaRecargaEntities(enRutaId)
                                        dao.deleteRecargasForEnRutaId(enRutaId)
                                        dao.insertRecargas(recargasEntities)
                                    } catch (_: Throwable) { }
                                }
                            }
                        }
                    }
                }

                // RECONCILIACIÓN: borra locales huérfanos que ya no existen en remoto (sin tocar dirty)
                scope.launch(ioDispatcher) {
                    val locals = dao.getAllStatusesOnce()
                    for (local in locals) {
                        if (local.isDirty) continue
                        val localMat = local.matAeronave.trim()
                        val exists = remoteIdsNow.contains(localMat) || remoteMatsNow.contains(localMat)
                        if (!exists) {
                            dao.deleteRecargasForEnRutaId(local.id)
                            dao.deleteStatusByMat(localMat)
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
