package com.cabgon.blackhawk.data.preflight

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PreflightDao {

    // Listado/observación principal
    @Transaction
    @Query("SELECT * FROM preflight_inspection ORDER BY id DESC")
    fun observeInspections(): Flow<List<InspectionWithItems>>

    // Listado de inspecciones "sucias" (dirty = 1) con sus ítems
    @Transaction
    @Query("SELECT * FROM preflight_inspection WHERE dirty = 1 ORDER BY id DESC")
    suspend fun getDirtyInspections(): List<InspectionWithItems>

    // Una inspección puntual (con ítems) por ID local
    @Transaction
    @Query("SELECT * FROM preflight_inspection WHERE id = :id")
    suspend fun getInspection(id: Long): InspectionWithItems?

    // Una inspección puntual (con ítems) por syncId (ID remoto en Firestore)
    @Transaction
    @Query("SELECT * FROM preflight_inspection WHERE syncId = :syncId LIMIT 1")
    suspend fun getInspectionBySyncId(syncId: String): InspectionWithItems?

    @Insert
    suspend fun insertInspection(inspection: PreflightInspection): Long

    @Insert
    suspend fun insertItems(items: List<PreflightItem>)

    @Update
    suspend fun updateInspection(inspection: PreflightInspection)

    @Update
    suspend fun updateItems(items: List<PreflightItem>)

    @Query("DELETE FROM preflight_item WHERE inspectionId = :inspectionId")
    suspend fun deleteItemsForInspection(inspectionId: Long)

    @Query("DELETE FROM preflight_inspection WHERE id = :id")
    suspend fun deleteInspection(id: Long)
}
