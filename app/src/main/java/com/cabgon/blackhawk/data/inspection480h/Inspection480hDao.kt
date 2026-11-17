package com.cabgon.blackhawk.data.inspection480h

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface Inspection480hDao {

    @Transaction
    @Query("SELECT * FROM inspection_480h ORDER BY id DESC")
    fun observeInspections(): Flow<List<Inspection480hWithItems>>

    @Transaction
    @Query("SELECT * FROM inspection_480h WHERE id = :id")
    fun observeInspection(id: Long): Flow<Inspection480hWithItems?>

    @Transaction
    @Query("SELECT * FROM inspection_480h WHERE id = :id")
    suspend fun getInspection(id: Long): Inspection480hWithItems?

    @Insert
    suspend fun insertHeader(header: Inspection480hHeader): Long

    @Insert
    suspend fun insertItems(items: List<Inspection480hItem>)

    @Update
    suspend fun updateHeader(header: Inspection480hHeader)

    @Update
    suspend fun updateItems(items: List<Inspection480hItem>)

    @Update
    suspend fun updateItem(item: Inspection480hItem)

    @Query("SELECT * FROM inspection_480h_item WHERE id = :itemId LIMIT 1")
    suspend fun getItem(itemId: Long): Inspection480hItem?

    @Query("DELETE FROM inspection_480h WHERE id = :id")
    suspend fun deleteInspection(id: Long)

    @Query("SELECT * FROM inspection_480h WHERE id = :id")
    suspend fun getHeader(id: Long): Inspection480hHeader?

    @Query("SELECT * FROM inspection_480h_item WHERE inspectionId = :inspectionId")
    suspend fun getItemsForInspection(inspectionId: Long): List<Inspection480hItem>
}
