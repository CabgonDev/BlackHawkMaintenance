package com.cabgon.blackhawk.data.local.enruta

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface EnRutaDao {

    // --------- Consultas básicas ---------

    @Query("SELECT * FROM en_ruta_status ORDER BY lastEditTimestamp DESC")
    fun observeAllStatuses(): Flow<List<EnRutaStatusEntity>>

    @Query("SELECT * FROM en_ruta_status WHERE matAeronave = :mat LIMIT 1")
    suspend fun getStatusByMat(mat: String): EnRutaStatusEntity?

    @Transaction
    @Query("SELECT * FROM en_ruta_status WHERE matAeronave = :mat LIMIT 1")
    suspend fun getEnRutaWithRecargasByMat(mat: String): EnRutaWithRecargas?

    @Transaction
    @Query("SELECT * FROM en_ruta_status WHERE id = :id LIMIT 1")
    suspend fun getEnRutaWithRecargasById(id: Long): EnRutaWithRecargas?

    // --------- Insert / Update ---------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStatus(entity: EnRutaStatusEntity): Long

    @Update
    suspend fun updateStatus(entity: EnRutaStatusEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecarga(recarga: EnRutaRecargaEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecargas(recargas: List<EnRutaRecargaEntity>): List<Long>

    @Query("DELETE FROM en_ruta_recarga WHERE enRutaId = :enRutaId")
    suspend fun deleteRecargasForEnRutaId(enRutaId: Long)

    @Query("DELETE FROM en_ruta_status WHERE matAeronave = :mat")
    suspend fun deleteStatusByMat(mat: String)

    // Upsert simple por matrícula (útil para sincronización)
    @Transaction
    suspend fun upsertStatusByMat(status: EnRutaStatusEntity): Long {
        val existing = getStatusByMat(status.matAeronave)
        return if (existing == null) {
            insertStatus(status)
        } else {
            updateStatus(status.copy(id = existing.id))
            existing.id
        }
    }

    // Actualizar status + recargas en una sola transacción (para "Guardar cambios")
    @Transaction
    suspend fun updateEnRutaWithRecargas(
        status: EnRutaStatusEntity,
        recargas: List<EnRutaRecargaEntity>
    ) {
        val existing = getStatusByMat(status.matAeronave)
        val id = if (existing == null) {
            insertStatus(status)
        } else {
            val toUpdate = status.copy(id = existing.id)
            updateStatus(toUpdate)
            existing.id
        }

        // Recargas: todas ligadas al ID correcto
        deleteRecargasForEnRutaId(id)
        val recargasWithFk = recargas.map { it.copy(enRutaId = id) }
        insertRecargas(recargasWithFk)
    }

    // --------- Soporte sync: marcar como sincronizado ---------

    @Query("""
        UPDATE en_ruta_status
        SET isDirty = 0, lastSyncTimestamp = :syncTime
        WHERE matAeronave = :mat
    """)
    suspend fun markStatusSynced(mat: String, syncTime: Long)

    @Query("""
        UPDATE en_ruta_recarga
        SET isDirty = 0
        WHERE enRutaId = :enRutaId
    """)
    suspend fun markRecargasSyncedForEnRuta(enRutaId: Long)
}
