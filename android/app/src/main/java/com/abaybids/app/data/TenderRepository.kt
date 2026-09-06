package com.abaybids.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Thin wrapper around Room DAO + SharedPreferences prefs + (optional)
 * Firebase Firestore write-through.
 *
 * Every local mutation is followed by a call to [FirebaseSync.pushTender] /
 * [FirebaseSync.pushDelete]. When Firebase isn't configured (the user hasn't
 * filled in `assets/firebase-config.json` yet) those calls are silent
 * no-ops, so the repository keeps working as a fully local store.
 */
class TenderRepository(private val context: Context) {

    private val db get() = AppDatabase.get(context)
    private val dao get() = db.tenderDao()
    private val bidDao get() = db.bidHistoryDao()

    fun observeAll(): Flow<List<Tender>> = dao.observeAll()
    fun observeById(id: Long): Flow<Tender?> = dao.observeById(id)

    suspend fun all(): List<Tender> = withContext(Dispatchers.IO) { dao.all() }
    suspend fun byId(id: Long): Tender? = withContext(Dispatchers.IO) { dao.byId(id) }

    /**
     * Upsert a tender. If we're editing an EXISTING tender whose status is
     * actually changing, we also write a row to the `bid_history` audit log
     * so the Bid History screen can show the transition later.
     */
    suspend fun save(tender: Tender): Long = withContext(Dispatchers.IO) {
        // Detect status change on an existing tender (id != 0).
        if (tender.id != 0L) {
            val old = dao.byId(tender.id)
            if (old != null && old.status != tender.status) {
                bidDao.insert(
                    BidHistory(
                        tenderId = tender.id,
                        customer = tender.customer,
                        tenderNo = tender.no,
                        fromStatus = old.status,
                        toStatus = tender.status,
                        value = tender.value,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        }
        val id = dao.upsert(tender)
        val saved = if (tender.id == 0L) tender.copy(id = id) else tender
        FirebaseSync.pushTender(saved)
        id
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        val t = dao.byId(id)
        val fid = t?.firebaseId.orEmpty()
        dao.delete(id)
        if (fid.isNotBlank()) FirebaseSync.pushDelete(fid)
    }

    suspend fun markCpoCollected(id: Long) = withContext(Dispatchers.IO) {
        dao.markCpoCollected(id)
        // Push the update so the CPO-collected flag syncs cross-device.
        dao.byId(id)?.let { FirebaseSync.pushTender(it) }
    }
}
