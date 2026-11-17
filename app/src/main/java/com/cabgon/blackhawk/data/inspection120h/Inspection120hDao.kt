package com.cabgon.blackhawk.data.inspection120h

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface Inspection120hDao {

    @Transaction
    @Query("SELECT * FROM inspection_120h ORDER BY id DESC")
    fun observeInspections(): Flow<List<Inspection120hWithItems>>

    @Transaction
    @Query("SELECT * FROM inspection_120h WHERE id = :id")
    fun observeInspection(id: Long): Flow<Inspection120hWithItems?>

    @Transaction
    @Query("SELECT * FROM inspection_120h WHERE id = :id")
    suspend fun getInspection(id: Long): Inspection120hWithItems?

    @Insert
    suspend fun insertHeader(header: Inspection120hHeader): Long

    @Insert
    suspend fun insertItems(items: List<Inspection120hItem>)

    @Update
    suspend fun updateHeader(header: Inspection120hHeader)

    @Update
    suspend fun updateItems(items: List<Inspection120hItem>)

    @Update
    suspend fun updateItem(item: Inspection120hItem)

    @Query("SELECT * FROM inspection_120h_item WHERE id = :itemId LIMIT 1")
    suspend fun getItem(itemId: Long): Inspection120hItem?

    @Query("DELETE FROM inspection_120h WHERE id = :id")
    suspend fun deleteInspection(id: Long)

    @Query("SELECT * FROM inspection_120h WHERE id = :id")
    suspend fun getHeader(id: Long): Inspection120hHeader?

    @Query("SELECT * FROM inspection_120h_item WHERE inspectionId = :inspectionId")
    suspend fun getItemsForInspection(inspectionId: Long): List<Inspection120hItem>
}
