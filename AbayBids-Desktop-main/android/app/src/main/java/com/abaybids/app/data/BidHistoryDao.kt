package com.abaybids.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BidHistoryDao {

    @Query("SELECT * FROM bid_history ORDER BY timestamp DESC LIMIT 500")
    fun observeAll(): Flow<List<BidHistory>>

    @Query("SELECT * FROM bid_history WHERE tender_id = :tenderId ORDER BY timestamp DESC")
    fun observeByTender(tenderId: Long): Flow<List<BidHistory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: BidHistory): Long

    @Query("SELECT COUNT(*) FROM bid_history")
    suspend fun count(): Int
}
