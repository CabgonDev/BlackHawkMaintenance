package com.cabgon.blackhawk.data.preflight

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PreflightDao {

    // Listado/observación
    @Transaction
    @Query("SELECT * FROM preflight_inspection ORDER BY id DESC")
    fun observeInspections(): Flow<List<InspectionWithItems>>

    // 🔽 NUEVO: listado de inspecciones "sucias" (dirty = 1) con sus ítems
    @Transaction
    @Query("SELECT * FROM preflight_inspection WHERE dirty = 1 ORDER BY id DESC")
    suspend fun getDirtyInspections(): List<InspectionWithItems>

    // Lectura puntual
    @Transaction
    @Query("SELECT * FROM preflight_inspection WHERE id = :id")
    suspend fun getInspection(id: Long): InspectionWithItems?

    // Inserts/updates
    @Insert
    suspend fun insertInspection(inspection: PreflightInspection): Long

    @Insert
    suspend fun insertItems(items: List<PreflightItem>)

    @Update
    suspend fun updateInspection(inspection: PreflightInspection)

    @Update
    suspend fun updateItems(items: List<PreflightItem>)

    @Query("DELETE FROM preflight_inspection WHERE id = :id")
    suspend fun deleteInspection(id: Long)
}
