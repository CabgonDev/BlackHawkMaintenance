package com.cabgon.blackhawk.data.local.enruta

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EnRutaDao {

    // --------- Consultas básicas ---------

    @Query("SELECT * FROM en_ruta_status ORDER BY lastEditTimestamp DESC")
    fun observeAllStatuses(): Flow<List<EnRutaStatusEntity>>

    // Siempre tomar el más reciente si existieran duplicados
    @Query("SELECT * FROM en_ruta_status WHERE matAeronave = :mat ORDER BY lastEditTimestamp DESC LIMIT 1")
    suspend fun getStatusByMat(mat: String): EnRutaStatusEntity?

    @Transaction
    @Query("SELECT * FROM en_ruta_status WHERE matAeronave = :mat ORDER BY lastEditTimestamp DESC LIMIT 1")
    suspend fun getEnRutaWithRecargasByMat(mat: String): EnRutaWithRecargas?

    // (opcional pero recomendado) para detalle reactivo
    @Transaction
    @Query("SELECT * FROM en_ruta_status WHERE matAeronave = :mat ORDER BY lastEditTimestamp DESC LIMIT 1")
    fun observeEnRutaWithRecargasByMat(mat: String): Flow<EnRutaWithRecargas?>

    @Transaction
    @Query("SELECT * FROM en_ruta_status WHERE id = :id LIMIT 1")
    suspend fun getEnRutaWithRecargasById(id: Long): EnRutaWithRecargas?

    // Para reconciliación/refresh
    @Query("SELECT * FROM en_ruta_status")
    suspend fun getAllStatusesOnce(): List<EnRutaStatusEntity>

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

    // Upsert por matrícula (útil para sincronización)
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

    // Actualizar status + recargas en una sola transacción
    @Transaction
    suspend fun updateEnRutaWithRecargas(
        status: EnRutaStatusEntity,
        recargas: List<EnRutaRecargaEntity>
    ) {
        val existing = getStatusByMat(status.matAeronave)
        val id = if (existing == null) {
            insertStatus(status)
        } else {
            updateStatus(status.copy(id = existing.id))
            existing.id
        }

        deleteRecargasForEnRutaId(id)
        val recargasWithFk = recargas.map { it.copy(enRutaId = id) }
        insertRecargas(recargasWithFk)
    }

    // --------- Sync flags ---------

    @Query(
        """
        UPDATE en_ruta_status
        SET isDirty = 0, lastSyncTimestamp = :syncTime
        WHERE matAeronave = :mat
        """
    )
    suspend fun markStatusSynced(mat: String, syncTime: Long)

    @Query(
        """
        UPDATE en_ruta_recarga
        SET isDirty = 0
        WHERE enRutaId = :enRutaId
        """
    )
    suspend fun markRecargasSyncedForEnRuta(enRutaId: Long)
}
