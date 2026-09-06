package com.abaybids.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {

    @Query("SELECT * FROM documents WHERE tender_id = :tenderId ORDER BY upload_date DESC")
    fun observeByTender(tenderId: String): Flow<List<Document>>

    @Query("SELECT * FROM documents WHERE contract_id = :contractId ORDER BY upload_date DESC")
    fun observeByContract(contractId: String): Flow<List<Document>>

    @Query("SELECT * FROM documents ORDER BY upload_date DESC")
    fun observeAll(): Flow<List<Document>>

    @Query("SELECT * FROM documents WHERE status = :status")
    suspend fun getByStatus(status: String): List<Document>

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): Document?

    @Query("SELECT * FROM documents WHERE firebase_id = :fid LIMIT 1")
    suspend fun getByFirebaseId(fid: String): Document?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(doc: Document): Long

    @Update
    suspend fun update(doc: Document)

    @Delete
    suspend fun delete(doc: Document)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM documents WHERE firebase_id = :fid")
    suspend fun deleteByFirebaseId(fid: String)

    @Query("UPDATE documents SET status = :status, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE documents SET firebase_id = :fid, storage_path = :path, status = 'UPLOADED', updated_at = :ts WHERE id = :id")
    suspend fun markUploaded(id: Long, fid: String, path: String, ts: Long = System.currentTimeMillis())

    @Query("UPDATE documents SET local_path = :path, updated_at = :ts WHERE id = :id")
    suspend fun updateLocalPath(id: Long, path: String, ts: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM documents")
    suspend fun count(): Int
}
