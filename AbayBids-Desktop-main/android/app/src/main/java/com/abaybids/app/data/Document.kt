package com.abaybids.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A document/file attached to a tender or contract.
 *
 * Cross-platform sync: the metadata is stored in Firestore (`documents`
 * collection) and the file blob is stored in Firebase Storage. Both Android
 * and Windows read/write to the same collection + storage — a document
 * uploaded from Android appears in Windows and vice-versa.
 *
 * Offline support: if the user attaches a file while offline, it's saved
 * locally with status PENDING_UPLOAD. A WorkManager worker uploads it
 * automatically when internet becomes available.
 *
 * Security: files are stored in Firebase Storage with authenticated access
 * rules (not public URLs). The `storagePath` is the Firebase Storage path
 * (e.g. `documents/{fileId}/file.pdf`), not a public URL.
 */
@Entity(tableName = "documents")
data class Document(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    /** Stable cross-platform ID — the Firestore document ID. */
    @ColumnInfo(name = "firebase_id")  val firebaseId: String = "",

    @ColumnInfo(name = "file_name")     val fileName: String,
    @ColumnInfo(name = "mime_type")    val mimeType: String = "",
    @ColumnInfo(name = "size")          val size: Long = 0L,
    @ColumnInfo(name = "upload_date")  val uploadDate: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "uploaded_by")   val uploadedBy: String = "",
    @ColumnInfo(name = "tender_id")     val tenderId: String = "",
    @ColumnInfo(name = "contract_id")   val contractId: String = "",
    @ColumnInfo(name = "storage_path")  val storagePath: String = "",
    @ColumnInfo(name = "local_path")    val localPath: String = "",
    @ColumnInfo(name = "version")       val version: Int = 1,

    /** Upload status: PENDING_UPLOAD | UPLOADING | UPLOADED | UPLOAD_FAILED */
    @ColumnInfo(name = "status")        val status: String = "PENDING_UPLOAD",

    /** Origin: android | windows */
    @ColumnInfo(name = "origin")        val origin: String = "android",

    @ColumnInfo(name = "created_at")    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val STATUS_PENDING_UPLOAD  = "PENDING_UPLOAD"
        const val STATUS_UPLOADING = "UPLOADING"
        const val STATUS_UPLOADED  = "UPLOADED"
        const val STATUS_FAILED    = "UPLOAD_FAILED"
    }
}
