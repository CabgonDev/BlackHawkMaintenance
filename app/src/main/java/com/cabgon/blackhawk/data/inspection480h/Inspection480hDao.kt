package com.cabgon.blackhawk.data.inspection480h

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface Inspection480hDao {

    @Transaction
    @Query("SELECT * FROM inspection_480h ORDER BY id DESC")
    fun observeInspections(): Flow<List<Inspection480hWithItems>>

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

    @Query("DELETE FROM inspection_480h WHERE id = :id")
    suspend fun deleteInspection(id: Long)
}
