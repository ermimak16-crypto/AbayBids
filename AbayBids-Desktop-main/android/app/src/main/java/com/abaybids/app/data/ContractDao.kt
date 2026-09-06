package com.abaybids.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ContractDao {

    @Query("SELECT * FROM contracts ORDER BY end_date ASC")
    fun observeAll(): Flow<List<Contract>>

    @Query("SELECT * FROM contracts ORDER BY end_date ASC")
    suspend fun all(): List<Contract>

    @Query("SELECT * FROM contracts WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): Contract?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(contract: Contract): Long

    @Update
    suspend fun update(contract: Contract)

    @Delete
    suspend fun delete(contract: Contract)

    @Query("DELETE FROM contracts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM contracts")
    suspend fun count(): Int
}
