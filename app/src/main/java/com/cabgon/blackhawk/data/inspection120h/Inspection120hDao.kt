package com.cabgon.blackhawk.data.inspection120h

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface Inspection120hDao {

    @Transaction
    @Query("SELECT * FROM inspection_120h ORDER BY id DESC")
    fun observeInspections(): Flow<List<Inspection120hWithItems>>

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

    @Query("DELETE FROM inspection_120h WHERE id = :id")
    suspend fun deleteInspection(id: Long)
}
