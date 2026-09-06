package com.abaybids.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Repository for cross-platform document management.
 *
 * Coordinates:
 *   • Room DAO (local cache + offline support)
 *   • DocumentSync (Firebase Storage + Firestore)
 *   • FileProvider + ACTION_VIEW (native file opening)
 *
 * UI calls this — never calls DocumentSync or DocumentDao directly.
 */
class DocumentRepository(private val context: Context) {

    private val dao = AppDatabase.get(context).documentDao()

    // ─── Observe (for UI) ─────────────────────────────────────────────────────

    fun observeByTender(tenderId: String): Flow<List<Document>> = dao.observeByTender(tenderId)
    fun observeByContract(contractId: String): Flow<List<Document>> = dao.observeByContract(contractId)
    fun observeAll(): Flow<List<Document>> = dao.observeAll()

    // ─── Attach a file (from Android file picker ACTION_OPEN_DOCUMENT) ──────────

    /**
     * Attach a file picked via ACTION_OPEN_DOCUMENT.
     *
     *  1. Copy the file content from the content URI to local cache (so the
     *     file persists even after the URI permission expires).
     *  2. Create a Document record in Room with status=PENDING_UPLOAD.
     *  3. If Firebase is active, upload immediately. Otherwise the
     *     OfflineUploadWorker picks it up when online.
     *
     * @param uri       The content URI from ACTION_OPEN_DOCUMENT
     * @param fileName  The original filename
     * @param mimeType  The MIME type
     * @param size      File size in bytes
     * @param tenderId  The tender this document belongs to (Firestore ID)
     * @param contractId The contract this document belongs to (if applicable)
     * @param uploadedBy Who uploaded it (e.g. "admin@bidmanagment")
     * @return the local Document ID
     */
    suspend fun attachFile(
        uri: Uri,
        fileName: String,
        mimeType: String,
        size: Long,
        tenderId: String,
        contractId: String,
        uploadedBy: String
    ): Long {
        // 1. Copy to local cache
        val cacheDir = File(context.cacheDir, "documents").apply { mkdirs() }
        val localFile = File(cacheDir, "pending_${System.currentTimeMillis()}_$fileName")
        context.contentResolver.openInputStream(uri)?.use { input ->
            localFile.outputStream().use { output -> input.copyTo(output) }
        } ?: run {
            Log.e("DocRepo", "Could not open input stream for $uri")
            return -1L
        }

        // 2. Create Room record
        val doc = Document(
            fileName = fileName,
            mimeType = mimeType,
            size = if (size > 0) size else localFile.length(),
            uploadDate = System.currentTimeMillis(),
            uploadedBy = uploadedBy,
            tenderId = tenderId,
            contractId = contractId,
            localPath = localFile.absolutePath,
            status = Document.STATUS_PENDING_UPLOAD,
            origin = "android",
        )
        val localId = dao.insert(doc)

        // 3. Upload immediately if Firebase is active
        if (FirebaseSync.isActive) {
            val saved = dao.get(localId) ?: return localId
            try {
                kotlinx.coroutines.runBlocking {
                    DocumentSync.uploadDocument(context, saved)
                }
            } catch (e: Exception) {
                Log.w("DocRepo", "Immediate upload failed, will retry via worker", e)
                OfflineUploadWorker.runNow(context)
            }
        }

        return localId
    }

    // ─── Open a file (native Android app via FileProvider + ACTION_VIEW) ────────

    /**
     * Open a document in the system's default application.
     *
     *  1. If the file is cached locally, use it directly.
     *  2. If not, download from Firebase Storage.
     *  3. Open with ACTION_VIEW + FileProvider (system picks MS Word, PDF
     *     reader, image viewer, etc. based on MIME type).
     *
     * @return true if the file was opened successfully
     */
    suspend fun openFile(doc: Document): Boolean {
        // 1. Ensure we have the file locally
        var localPath = doc.localPath
        if (localPath.isBlank() || !File(localPath).exists()) {
            // Download from Firebase Storage
            localPath = DocumentSync.downloadDocument(context, doc) ?: return false
        }

        val file = File(localPath)
        if (!file.exists()) {
            Log.e("DocRepo", "File not found: $localPath")
            return false
        }

        // 2. Get a content URI via FileProvider
        val authority = "${context.packageName}.fileprovider"
        val contentUri = try {
            FileProvider.getUriForFile(context, authority, file)
        } catch (e: Exception) {
            Log.e("DocRepo", "FileProvider failed", e)
            return false
        }

        // 3. Launch ACTION_VIEW with the correct MIME type
        val mime = doc.mimeType.ifBlank { guessMimeFromExt(doc.fileName) }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            val chooser = Intent.createChooser(intent, "Open ${doc.fileName}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        } catch (e: Exception) {
            Log.e("DocRepo", "No app to open ${doc.fileName} ($mime)", e)
            false
        }
    }

    // ─── Delete ────────────────────────────────────────────────────────────────

    suspend fun deleteDocument(doc: Document) {
        DocumentSync.deleteDocument(context, doc)
    }

    // ─── Retry failed upload ───────────────────────────────────────────────────

    suspend fun retryUpload(doc: Document): Boolean {
        val updated = doc.copy(status = Document.STATUS_PENDING_UPLOAD)
        dao.update(updated)
        return DocumentSync.uploadDocument(context, updated)
    }

    // ─── Upload status for UI ─────────────────────────────────────────────────

    fun statusLabel(doc: Document): String = when (doc.status) {
        Document.STATUS_PENDING_UPLOAD -> "Pending Upload"
        Document.STATUS_UPLOADING -> "Uploading"
        Document.STATUS_UPLOADED  -> "Uploaded"
        Document.STATUS_FAILED    -> "Upload Failed"
        else -> doc.status
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    private fun guessMimeFromExt(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "txt" -> "text/plain"
            else -> "*/*"
        }
    }
}
