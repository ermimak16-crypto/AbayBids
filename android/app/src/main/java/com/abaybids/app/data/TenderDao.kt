package com.abaybids.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TenderDao {

    @Query("SELECT * FROM tenders ORDER BY date ASC")
    fun observeAll(): Flow<List<Tender>>

    @Query("SELECT * FROM tenders ORDER BY date ASC")
    suspend fun all(): List<Tender>

    @Query("SELECT * FROM tenders WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): Tender?

    @Query("SELECT * FROM tenders WHERE id = :id LIMIT 1")
    fun observeById(id: Long): Flow<Tender?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tender: Tender): Long

    @Update
    suspend fun update(tender: Tender)

    @Query("DELETE FROM tenders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE tenders SET cpo_collected = 1 WHERE id = :id")
    suspend fun markCpoCollected(id: Long)

    @Query("SELECT COUNT(*) FROM tenders")
    suspend fun count(): Int

    // ===== Cross-platform (Firebase) queries =====

    @Query("SELECT * FROM tenders WHERE firebase_id = :fid LIMIT 1")
    suspend fun byFirebaseIdOnce(fid: String): Tender?

    @Query("SELECT * FROM tenders WHERE firebase_id = :fid LIMIT 1")
    fun observeByFirebaseId(fid: String): Flow<Tender?>

    /** Resolve the local Long PK for a Firebase document id (or null if not synced yet). */
    @Query("SELECT id FROM tenders WHERE firebase_id = :fid LIMIT 1")
    suspend fun findLocalIdByFirebaseId(fid: String): Long?

    /**
     * Stamp a locally-created tender with its freshly-generated Firebase
     * document id (called from FirebaseSync.pushTender the first time we push).
     */
    @Query("UPDATE tenders SET firebase_id = :fid WHERE id = :localId")
    suspend fun assignFirebaseId(localId: Long, fid: String)

    @Query("DELETE FROM tenders WHERE firebase_id = :fid")
    suspend fun deleteByFirebaseId(fid: String)
}
