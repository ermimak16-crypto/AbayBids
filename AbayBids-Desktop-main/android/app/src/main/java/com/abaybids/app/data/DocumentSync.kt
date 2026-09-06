package com.abaybids.app.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.abaybids.app.AbayBidsApp
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File

/**
 * Cross-platform document sync via Firebase Storage + Firestore.
 *
 * ARCHITECTURE:
 *   • File BLOBS  → Firebase Storage at path `documents/{firebaseId}/{fileName}`
 *   • Metadata    → Firestore `documents` collection (shared with Windows)
 *   • Local cache → Room `documents` table (offline working copy)
 *
 * SYNC FLOW:
 *   Android uploads a file:
 *     1. Copy file to local cache
 *     2. Save Document record in Room (status=PENDING_UPLOAD)
 *     3. Upload blob to Firebase Storage
 *     4. Write metadata to Firestore `documents` collection
 *     5. Mark as UPLOADED
 *
 *   Windows receives it:
 *     1. Firestore listener fires → saves metadata to IndexedDB
 *     2. User clicks "Open" → downloads blob from Firebase Storage
 *     3. Opens with system app (MS Word, Excel, PDF reader)
 *
 *   Windows uploads a file:
 *     1. Upload blob to Firebase Storage
 *     2. Write metadata to Firestore `documents` collection
 *
 *   Android receives it:
 *     1. Firestore listener fires → saves metadata to Room
 *     2. User clicks "Open" → downloads blob from Firebase Storage to cache
 *     3. Opens with FileProvider + ACTION_VIEW
 *
 * OFFLINE:
 *   If the user attaches a file while offline, it stays in Room with
 *   status=PENDING_UPLOAD. A WorkManager worker uploads it when online.
 *
 * SECURITY:
 *   Firebase Storage rules should require authentication. The storagePath
 *   is NOT a public URL — it's a Firebase Storage reference that requires
 *   an authenticated Firebase user to download.
 */
object DocumentSync {

    private const val TAG = "DocumentSync"
    private const val COLLECTION = "documents"
    private const val STORAGE_PREFIX = "documents"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var enabled = false
    @Volatile private var registration: ListenerRegistration? = null

    val isActive: Boolean get() = enabled

    fun init(context: Context) {
        enabled = FirebaseSync.isActive
        if (!enabled) {
            Log.i(TAG, "Firebase not active — document sync stays local. Files cached in Room.")
            return
        }
        startDocumentsListener(context)
    }

    // ─── Firestore listener: mirror `documents` collection → Room ──────────────

    private fun startDocumentsListener(context: Context) {
        if (registration != null) return
        val db = FirebaseFirestore.getInstance()
        registration = db.collection(COLLECTION)
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .limit(1000)
            .addSnapshotListener { snap, err ->
                if (err != null) { Log.w(TAG, "Documents listener error", err); return@addSnapshotListener }
                if (snap == null) return@addSnapshotListener
                scope.launch {
                    val dao = AppDatabase.get(context).documentDao()
                    for (dc in snap.documentChanges) {
                        val fid = dc.document.id
                        when (dc.type) {
                            DocumentChange.Type.ADDED,
                            DocumentChange.Type.MODIFIED -> {
                                val mapped = mapToDocument(fid, dc.document.data) ?: continue
                                val existing = dao.getByFirebaseId(fid)
                                if (existing != null) {
                                    // Update — preserve local id + localPath (cache)
                                    dao.update(mapped.copy(id = existing.id, localPath = existing.localPath))
                                } else {
                                    // New from the other device
                                    dao.insert(mapped.copy(id = 0L))
                                }
                            }
                            DocumentChange.Type.REMOVED -> {
                                dao.deleteByFirebaseId(fid)
                            }
                            else -> Unit
                        }
                    }
                }
            }
    }

    // ─── Upload: file blob → Firebase Storage + metadata → Firestore ───────────

    /**
     * Upload a document to Firebase Storage + Firestore.
     * Called by OfflineUploadWorker when internet is available.
     */
    suspend fun uploadDocument(context: Context, doc: Document): Boolean {
        if (!enabled) {
            Log.i(TAG, "Firebase not active — file stays local only.")
            return false
        }
        val dao = AppDatabase.get(context).documentDao()
        val fid = doc.firebaseId.ifBlank { generateDocId() }

        try {
            // Mark as uploading
            dao.updateStatus(doc.id, Document.STATUS_UPLOADING)

            val localFile = File(doc.localPath)
            if (!localFile.exists()) {
                Log.e(TAG, "Local file not found: ${doc.localPath}")
                dao.updateStatus(doc.id, Document.STATUS_FAILED)
                return false
            }

            val storagePath = "$STORAGE_PREFIX/$fid/${doc.fileName}"
            val storageRef = FirebaseStorage.getInstance().getReference(storagePath)

            // Upload blob
            val uploadTask = storageRef.putFile(Uri.fromFile(localFile)).await()

            // Write metadata to Firestore
            val data = documentToMap(doc.copy(firebaseId = fid, storagePath = storagePath, status = Document.STATUS_UPLOADED))
            FirebaseFirestore.getInstance().collection(COLLECTION).document(fid)
                .set(data, SetOptions.merge()).await()

            // Mark as uploaded locally
            dao.markUploaded(doc.id, fid, storagePath)
            Log.i(TAG, "Document uploaded: ${doc.fileName} → $storagePath")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed for ${doc.fileName}", e)
            dao.updateStatus(doc.id, Document.STATUS_FAILED)
            return false
        }
    }

    // ─── Download: Firebase Storage → local cache ──────────────────────────────

    /**
     * Download a document's file blob from Firebase Storage to local cache.
     * Returns the local file path, or null on failure.
     */
    suspend fun downloadDocument(context: Context, doc: Document): String? {
        if (doc.storagePath.isBlank()) {
            Log.w(TAG, "No storage path for document ${doc.fileName}")
            return null
        }

        // If local cache exists, use it
        if (doc.localPath.isNotBlank() && File(doc.localPath).exists()) {
            return doc.localPath
        }

        if (!enabled) {
            Log.w(TAG, "Firebase not active — can't download ${doc.fileName}")
            return null
        }

        return try {
            val cacheDir = File(context.cacheDir, "documents").apply { mkdirs() }
            val localFile = File(cacheDir, "${doc.firebaseId}_${doc.fileName}")
            val storageRef = FirebaseStorage.getInstance().getReference(doc.storagePath)
            storageRef.getFile(localFile).await()
            // Save the local path
            AppDatabase.get(context).documentDao().updateLocalPath(doc.id, localFile.absolutePath)
            Log.i(TAG, "Document downloaded: ${doc.fileName} → ${localFile.absolutePath}")
            localFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for ${doc.fileName}", e)
            null
        }
    }

    // ─── Delete: remove from Firebase Storage + Firestore + Room ────────────────

    suspend fun deleteDocument(context: Context, doc: Document) {
        val dao = AppDatabase.get(context).documentDao()
        dao.deleteById(doc.id)

        if (!enabled || doc.firebaseId.isBlank()) return

        // Delete from Firestore
        try {
            FirebaseFirestore.getInstance().collection(COLLECTION).document(doc.firebaseId).delete().await()
        } catch (e: Exception) { Log.w(TAG, "Firestore delete failed", e) }

        // Delete from Firebase Storage
        if (doc.storagePath.isNotBlank()) {
            try {
                FirebaseStorage.getInstance().getReference(doc.storagePath).delete().await()
            } catch (e: Exception) { Log.w(TAG, "Storage delete failed", e) }
        }

        // Delete local cache
        if (doc.localPath.isNotBlank()) {
            try { File(doc.localPath).delete() } catch (_: Exception) {}
        }
    }

    // ─── Push metadata only (for status updates) ────────────────────────────────

    fun pushMetadata(doc: Document) {
        if (!enabled || doc.firebaseId.isBlank()) return
        try {
            val data = documentToMap(doc)
            FirebaseFirestore.getInstance().collection(COLLECTION).document(doc.firebaseId)
                .set(data, SetOptions.merge())
                .addOnFailureListener { Log.w(TAG, "pushMetadata failed", it) }
        } catch (e: Exception) { Log.w(TAG, "pushMetadata", e) }
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    private fun generateDocId(): String {
        val ts = System.currentTimeMillis()
        val rand = (1..6).map { ('a'..'z') + ('0'..'9') }.flatten().shuffled().take(6).joinToString("")
        return "doc_${ts}_$rand"
    }

    private fun mapToDocument(fid: String, data: Map<String, Any>): Document? {
        return try {
            Document(
                id = 0L,
                firebaseId = fid,
                fileName = data["fileName"] as? String ?: "",
                mimeType = data["mimeType"] as? String ?: "",
                size = (data["size"] as? Number)?.toLong() ?: 0L,
                uploadDate = (data["uploadDate"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                uploadedBy = data["uploadedBy"] as? String ?: "",
                tenderId = data["tenderId"] as? String ?: "",
                contractId = data["contractId"] as? String ?: "",
                storagePath = data["storagePath"] as? String ?: "",
                localPath = "", // local cache — not synced
                version = (data["version"] as? Number)?.toInt() ?: 1,
                status = data["status"] as? String ?: Document.STATUS_UPLOADED,
                origin = data["origin"] as? String ?: "windows",
                createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "mapToDocument failed for $fid", e); null
        }
    }

    private fun documentToMap(doc: Document): Map<String, Any> {
        val m = HashMap<String, Any>()
        m["firebaseId"] = doc.firebaseId
        m["fileName"] = doc.fileName
        m["mimeType"] = doc.mimeType
        m["size"] = doc.size
        m["uploadDate"] = doc.uploadDate
        m["uploadedBy"] = doc.uploadedBy
        m["tenderId"] = doc.tenderId
        m["contractId"] = doc.contractId
        m["storagePath"] = doc.storagePath
        m["version"] = doc.version
        m["status"] = Document.STATUS_UPLOADED // cloud status is always "uploaded"
        m["origin"] = doc.origin
        m["createdAt"] = doc.createdAt
        m["updatedAt"] = System.currentTimeMillis()
        return m
    }
}
