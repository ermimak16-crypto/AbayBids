package com.abaybids.app.ui

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.abaybids.app.R
import com.abaybids.app.data.Document
import com.abaybids.app.data.DocumentRepository
import com.abaybids.app.databinding.ActivityDocumentsBinding
import com.abaybids.app.databinding.ItemDocumentBinding
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DocumentsActivity — REAL cross-platform document management.
 *
 *  • Lists every Document row from `DocumentDao.observeAll()`.
 *  • Each row shows filename, size, upload date, uploader, and a
 *    status badge (Pending Upload / Uploading / Uploaded / Failed).
 *  • "Open" button → `DocumentRepository.openFile(doc)` — opens the file
 *    in the system's native app via FileProvider + ACTION_VIEW.
 *  • "Delete" button → confirms, then `DocumentRepository.deleteDocument`
 *    which removes the local cache, Room row, Firestore metadata, and
 *    Storage blob (when Firebase is active).
 *  • "Retry" button — appears for FAILED uploads — calls
 *    `DocumentRepository.retryUpload(doc)`.
 *  • FAB "+" launches ACTION_OPEN_DOCUMENT with EXTRA_MIME_TYPES for
 *    [pdf, doc, docx, xls, xlsx, jpg, png]. On pick, attaches the file
 *    via `DocumentRepository.attachFile(...)` — which copies to cache,
 *    saves to Room as PENDING_UPLOAD, and triggers the upload.
 *
 *  Works fully offline (Firebase inactive). When Firebase IS active the
 *  upload happens immediately and also syncs to the Windows app.
 */
class DocumentsActivity : ThemeAwareActivity() {

    private lateinit var b: ActivityDocumentsBinding
    private val repo by lazy { DocumentRepository(this) }
    private val adapter = DocAdapter(
        onOpen = { doc ->
            lifecycleScope.launch {
                val ok = repo.openFile(doc)
                if (!ok) {
                    Toast.makeText(
                        this@DocumentsActivity,
                        "Could not open ${doc.fileName}. File may not be downloaded yet.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        },
        onDelete = { doc -> confirmDelete(doc) },
        onRetry = { doc ->
            lifecycleScope.launch {
                val ok = repo.retryUpload(doc)
                Toast.makeText(
                    this@DocumentsActivity,
                    if (ok) "Retrying upload" else "Retry failed — will try again later",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    )

    private val picker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) attachFromUri(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDocumentsBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        b.docsRv.layoutManager = LinearLayoutManager(this)
        b.docsRv.adapter = adapter

        b.fabAdd.setOnClickListener {
            // EXTRA_MIME_TYPES restricts the picker to known document formats.
            picker.launch(
                arrayOf(
                    "application/pdf",
                    "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "image/png",
                    "image/jpeg"
                )
            )
        }

        lifecycleScope.launch {
            repo.observeAll().collect { docs ->
                adapter.submitList(docs)
                if (docs.isEmpty()) {
                    b.docsRv.visibility = View.GONE
                    b.emptyText.visibility = View.VISIBLE
                } else {
                    b.docsRv.visibility = View.VISIBLE
                    b.emptyText.visibility = View.GONE
                }
            }
        }
    }

    /** Reads filename + size from the picked URI, then calls attachFile. */
    private fun attachFromUri(uri: Uri) {
        var fileName = "document_${System.currentTimeMillis()}"
        var size = 0L

        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx >= 0) fileName = cursor.getString(nameIdx) ?: fileName
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }

        val mime = contentResolver.getType(uri).orEmpty().ifBlank { guessMimeFromName(fileName) }

        lifecycleScope.launch {
            val id = repo.attachFile(
                uri = uri,
                fileName = fileName,
                mimeType = mime,
                size = size,
                tenderId = "",
                contractId = "",
                uploadedBy = "admin"
            )
            Toast.makeText(
                this@DocumentsActivity,
                if (id >= 0) "Attached $fileName" else "Failed to attach file",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun confirmDelete(doc: Document) {
        AlertDialog.Builder(this)
            .setTitle("Delete document")
            .setMessage("Delete \"${doc.fileName}\"?\nThis removes it from this device, the Firestore metadata, and Firebase Storage (if active).")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    repo.deleteDocument(doc)
                    Toast.makeText(this@DocumentsActivity, "Deleted ${doc.fileName}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun guessMimeFromName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            else -> "*/*"
        }
    }

    // ─── RecyclerView adapter ───────────────────────────────────────────────────

    private inner class DocAdapter(
        private val onOpen: (Document) -> Unit,
        private val onDelete: (Document) -> Unit,
        private val onRetry: (Document) -> Unit
    ) : ListAdapter<Document, DocAdapter.VH>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemDocumentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

        inner class VH(val b: ItemDocumentBinding) : RecyclerView.ViewHolder(b.root) {
            private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            fun bind(d: Document) = with(b) {
                docName.text = d.fileName
                docMeta.text = buildString {
                    append(humanSize(d.size))
                    if (d.uploadDate > 0) {
                        append(" · ").append(dateFmt.format(Date(d.uploadDate)))
                    }
                    if (d.uploadedBy.isNotBlank()) append(" · ").append(d.uploadedBy)
                }
                val statusLabel = repo.statusLabel(d)
                docStatus.text = statusLabel
                val colorRes = when (d.status) {
                    Document.STATUS_UPLOADED  -> R.color.status_won
                    Document.STATUS_UPLOADING -> R.color.status_pending
                    Document.STATUS_FAILED    -> R.color.status_lost
                    Document.STATUS_PENDING_UPLOAD -> R.color.status_active
                    else -> R.color.text_muted
                }
                docStatus.background.setTint(ContextCompat.getColor(root.context, colorRes))

                btnOpen.setOnClickListener { onOpen(d) }
                btnDelete.setOnClickListener { onDelete(d) }
                btnRetry.visibility = if (d.status == Document.STATUS_FAILED) View.VISIBLE else View.GONE
                btnRetry.setOnClickListener { onRetry(d) }
            }

            private fun humanSize(bytes: Long): String = when {
                bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
                bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
                else -> "${bytes} B"
            }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Document>() {
            override fun areItemsTheSame(a: Document, b: Document) = a.id == b.id
            override fun areContentsTheSame(a: Document, b: Document) = a == b
        }
    }
}
