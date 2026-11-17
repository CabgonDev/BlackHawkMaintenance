package com.cabgon.blackhawk.data.inspection80h

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface Inspection80hDao {

    // Lista de inspecciones 80h
    @Transaction
    @Query("SELECT * FROM inspection_80h ORDER BY id DESC")
    fun observeInspections(): Flow<List<Inspection80hWithItems>>

    // Detalle puntual
    @Transaction
    @Query("SELECT * FROM inspection_80h WHERE id = :id")
    suspend fun getInspection(id: Long): Inspection80hWithItems?

    // Inserts
    @Insert
    suspend fun insertHeader(header: Inspection80hHeader): Long

    @Insert
    suspend fun insertItems(items: List<Inspection80hItem>)

    // Updates
    @Update
    suspend fun updateHeader(header: Inspection80hHeader)

    @Update
    suspend fun updateItems(items: List<Inspection80hItem>)

    // Borrar inspección completa
    @Query("DELETE FROM inspection_80h WHERE id = :id")
    suspend fun deleteInspection(id: Long)
}
