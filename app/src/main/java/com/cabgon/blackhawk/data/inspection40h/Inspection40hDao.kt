package com.cabgon.blackhawk.data.inspection40h

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface Inspection40hDao {

    // Lista de inspecciones 40h (con items) para el listado
    @Transaction
    @Query("SELECT * FROM inspection_40h ORDER BY id DESC")
    fun observeInspections(): Flow<List<Inspection40hWithItems>>

    // Una inspección puntual (FLUJO) para el checklist
    @Transaction
    @Query("SELECT * FROM inspection_40h WHERE id = :id")
    fun observeInspection(id: Long): Flow<Inspection40hWithItems?>

    // Una inspección puntual (suspend) por si la necesitas una sola vez
    @Transaction
    @Query("SELECT * FROM inspection_40h WHERE id = :id")
    suspend fun getInspection(id: Long): Inspection40hWithItems?

    // Inserts
    @Insert
    suspend fun insertHeader(header: Inspection40hHeader): Long

    @Insert
    suspend fun insertItems(items: List<Inspection40hItem>)

    // Updates
    @Update
    suspend fun updateHeader(header: Inspection40hHeader)

    @Update
    suspend fun updateItems(items: List<Inspection40hItem>)

    // Actualizar UN solo ítem del checklist
    @Update
    suspend fun updateItem(item: Inspection40hItem)

    // Obtener UN ítem por id (para marcar/desmarcar)
    @Query("SELECT * FROM inspection_40h_item WHERE id = :itemId LIMIT 1")
    suspend fun getItem(itemId: Long): Inspection40hItem?

    // Borrar inspección completa (header + items si tienes FK ON DELETE CASCADE)
    @Query("DELETE FROM inspection_40h WHERE id = :id")
    suspend fun deleteInspection(id: Long)

    @Query("SELECT * FROM inspection_40h WHERE id = :id")
    suspend fun getHeader(id: Long): Inspection40hHeader?

    @Query("SELECT * FROM inspection_40h_item WHERE inspectionId = :inspectionId")
    suspend fun getItemsForInspection(inspectionId: Long): List<Inspection40hItem>

}
