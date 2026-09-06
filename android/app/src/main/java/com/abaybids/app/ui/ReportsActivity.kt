package com.abaybids.app.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.gridlayout.widget.GridLayout
import androidx.lifecycle.lifecycleScope
import com.abaybids.app.R
import com.abaybids.app.data.Tender
import com.abaybids.app.data.TenderRepository
import com.abaybids.app.databinding.ActivityReportsBinding
import com.abaybids.app.databinding.ItemKpiBinding
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ReportsActivity — REAL statistics computed from `repo.all()`.
 *
 *  • KPI grid: total tenders, pending, participated, won, lost, not
 *    participated, total value, won value, success rate, active tenders.
 *  • Status filter dropdown (All / Pending / Participated / Won / Lost)
 *    that re-computes the stats for the matching subset.
 *  • "Export to Excel (CSV)" — writes all tenders to cacheDir and shares
 *    via FileProvider + ACTION_SEND (`text/csv`).
 *  • "Export to PDF" — uses Android's `PdfDocument` API to render a
 *    tabular snapshot, then shares via ACTION_SEND (`application/pdf`).
 *
 * All numbers come from the Room DB. No hard-coded values.
 */
class ReportsActivity : ThemeAwareActivity() {

    private lateinit var b: ActivityReportsBinding
    private val repo by lazy { TenderRepository(this) }

    private val statusFilters = listOf("All", "Pending", "Participated", "Won", "Lost")
    private var allTenders: List<Tender> = emptyList()
    private var currentFilter: String = "All"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityReportsBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        b.statusFilter.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, statusFilters
        )
        b.statusFilter.onItemSelectedListener = object :
            android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                currentFilter = statusFilters[pos]
                renderStats(filtered())
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        b.btnExportCsv.setOnClickListener {
            lifecycleScope.launch { exportCsv() }
        }
        b.btnExportPdf.setOnClickListener {
            lifecycleScope.launch { exportPdf() }
        }

        lifecycleScope.launch {
            repo.observeAll().collect { tenders ->
                allTenders = tenders
                renderStats(filtered())
            }
        }
    }

    private fun filtered(): List<Tender> =
        if (currentFilter == "All") allTenders
        else allTenders.filter { it.status == currentFilter }

    private data class Stat(val label: String, val value: String, val sub: String, val icon: Int, val color: Int)

    /** Renders the KPI grid for the current filtered subset. */
    private fun renderStats(tenders: List<Tender>) {
        val total = tenders.size
        val pending = tenders.count { it.status == "Pending" }
        val participated = tenders.count { it.status == "Participated" }
        val won = tenders.count { it.status == "Won" }
        val lost = tenders.count { it.status == "Lost" }
        val notPart = tenders.count { it.status == "Not Participated" }
        val totalVal = tenders.sumOf { it.value }
        val wonVal = tenders.filter { it.status == "Won" }.sumOf { it.value }
        val active = tenders.count { it.status == "Pending" || it.status == "Participated" }
        val successRate = if (total > 0) (won * 100 / total) else 0

        val stats = listOf(
            Stat("Total Tenders", total.toString(), "in current view", R.drawable.ic_assessment, R.color.status_participated),
            Stat("Active", active.toString(), "pending + participated", R.drawable.ic_check_circle, R.color.status_active),
            Stat("Pending", pending.toString(), "awaiting result", R.drawable.ic_schedule, R.color.status_pending),
            Stat("Participated", participated.toString(), "bids submitted", R.drawable.ic_check_circle, R.color.status_participated),
            Stat("Won", won.toString(), "$successRate% of total", R.drawable.ic_trophy, R.color.status_won),
            Stat("Lost", lost.toString(), "unsuccessful bids", R.drawable.ic_cancel, R.color.status_lost),
            Stat("Not Participated", notPart.toString(), "withdrawn", R.drawable.ic_remove_circle_outline, R.color.status_notpart),
            Stat("Success Rate", "$successRate%", "won of total", R.drawable.ic_trending_up, R.color.brand_primary),
            Stat("Total Value", humanReadableV(totalVal), "ETB", R.drawable.ic_payments, R.color.brand_accent),
            Stat("Won Value", humanReadableV(wonVal), "ETB", R.drawable.ic_trophy, R.color.status_won)
        )

        b.statsGrid.removeAllViews()
        stats.forEach { s ->
            val binding = ItemKpiBinding.inflate(LayoutInflater.from(this), b.statsGrid, true)
            binding.kpiValue.text = s.value
            binding.kpiLabel.text = s.label
            binding.kpiSub.text = s.sub
            binding.kpiIcon.setImageResource(s.icon)
            binding.kpiIcon.setColorFilter(ContextCompat.getColor(this, s.color))
        }
    }

    private fun humanReadableV(v: Double): String = when {
        v >= 1_000_000_000 -> "%.2fB".format(v / 1_000_000_000)
        v >= 1_000_000 -> "%.2fM".format(v / 1_000_000)
        v >= 1_000 -> "%.1fK".format(v / 1_000)
        else -> v.toInt().toString()
    }

    // ─── CSV export ──────────────────────────────────────────────────────────────

    /** Generates a CSV of all tenders and shares it. */
    private suspend fun exportCsv() {
        val tenders = repo.all()
        if (tenders.isEmpty()) {
            Toast.makeText(this, "No tenders to export", Toast.LENGTH_SHORT).show()
            return
        }
        val dateStamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val fileName = "Abay_Report_$dateStamp.csv"
        val out = File(cacheDir, fileName)

        try {
            OutputStreamWriter(FileOutputStream(out), Charsets.UTF_8).use { w ->
                w.write("Customer,Tender No,Product,Date,Value,Status,CPO,CPO Amount,Contact,Phone\n")
                tenders.forEach { t ->
                    fun csv(v: String): String =
                        "\"" + v.replace("\"", "\"\"") + "\""
                    w.write(listOf(
                        csv(t.customer),
                        csv(t.no),
                        csv(t.product),
                        csv(t.date),
                        t.value.toString(),
                        csv(t.status),
                        csv(t.cpo),
                        t.cpoAmount.toString(),
                        csv(t.contact),
                        csv(t.phone)
                    ).joinToString(",") + "\n")
                }
            }
            shareFile(out, "text/csv")
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ─── PDF export ─────────────────────────────────────────────────────────────

    /** Generates a PDF using android.graphics.pdf.PdfDocument and shares it. */
    private suspend fun exportPdf() {
        val tenders = repo.all()
        if (tenders.isEmpty()) {
            Toast.makeText(this, "No tenders to export", Toast.LENGTH_SHORT).show()
            return
        }
        val dateStamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val fileName = "Abay_Report_$dateStamp.pdf"
        val out = File(cacheDir, fileName)

        val pageWidth = 595   // A4 portrait @ 72dpi
        val pageHeight = 842
        val margin = 28
        val doc = PdfDocument()
        val brand = ContextCompat.getColor(this, R.color.brand_primary)
        val textColor = ContextCompat.getColor(this, R.color.text_primary)
        val muted = ContextCompat.getColor(this, R.color.text_muted)
        val divider = ContextCompat.getColor(this, R.color.divider)

        var pageNum = 1
        var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
        var page = doc.startPage(pageInfo)
        var canvas = page.canvas
        var y = margin + 8

        fun paintText(text: String, x: Float, size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) {
            val p = Paint().apply {
                this.color = color
                this.textSize = size.toFloat()
                isAntiAlias = true
                if (bold) typeface = Typeface.DEFAULT_BOLD
                this.textAlign = align
            }
            canvas.drawText(text, x, y.toFloat(), p)
        }

        fun newPage() {
            doc.finishPage(page)
            pageNum++
            pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
            page = doc.startPage(pageInfo)
            canvas = page.canvas
            y = margin + 8
        }

        // Header
        val titlePaint = Paint().apply { color = brand; textSize = 18f; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
        canvas.drawText("Abay Bids — Tender Report", margin.toFloat(), y.toFloat(), titlePaint)
        y += 14
        val mutedPaint = Paint().apply { color = muted; textSize = 9f; isAntiAlias = true }
        canvas.drawText(
            "Generated ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}  ·  ${tenders.size} tender(s)",
            margin.toFloat(), y.toFloat(), mutedPaint
        )
        y += 18

        // Column widths
        val colCustomer = 150
        val colNo = 110
        val colDate = 70
        val colStatus = 80
        val colValue = 90

        fun headerRow() {
            val p = Paint().apply { color = textColor; textSize = 9f; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
            var x = margin
            canvas.drawText("Customer", x.toFloat(), y.toFloat(), p)
            x += colCustomer
            canvas.drawText("Tender No", x.toFloat(), y.toFloat(), p)
            x += colNo
            canvas.drawText("Date", x.toFloat(), y.toFloat(), p)
            x += colDate
            canvas.drawText("Status", x.toFloat(), y.toFloat(), p)
            x += colStatus
            p.textAlign = Paint.Align.RIGHT
            canvas.drawText("Value (ETB)", (pageWidth - margin).toFloat(), y.toFloat(), p)
            y += 4

            // Divider
            val dp = Paint().apply { color = divider; strokeWidth = 1f }
            canvas.drawLine(margin.toFloat(), y.toFloat(), (pageWidth - margin).toFloat(), y.toFloat(), dp)
            y += 12
        }

        headerRow()
        tenders.sortedBy { it.date }.forEach { t ->
            if (y > pageHeight - margin - 24) {
                newPage(); headerRow()
            }
            val p = Paint().apply { color = textColor; textSize = 9f; isAntiAlias = true }
            var x = margin
            // Truncate customer name to fit column width.
            val cust = ellipsize(t.customer, 28)
            canvas.drawText(cust, x.toFloat(), y.toFloat(), p)
            x += colCustomer
            canvas.drawText(ellipsize(t.no, 18), x.toFloat(), y.toFloat(), p)
            x += colNo
            canvas.drawText(t.date, x.toFloat(), y.toFloat(), p)
            x += colDate
            // Tint status color
            p.color = ContextCompat.getColor(this, StatusUi.color(t.status))
            canvas.drawText(t.status, x.toFloat(), y.toFloat(), p)
            p.color = textColor
            x += colStatus
            p.textAlign = Paint.Align.RIGHT
            canvas.drawText(StatusUi.fmtCurrency(t.value), (pageWidth - margin).toFloat(), y.toFloat(), p)
            y += 16
        }

        // Footer with totals
        if (y > pageHeight - margin - 60) newPage()
        y += 6
        val dividerPaint = Paint().apply { color = divider; strokeWidth = 1f }
        canvas.drawLine(margin.toFloat(), y.toFloat(), (pageWidth - margin).toFloat(), y.toFloat(), dividerPaint)
        y += 16
        val totalVal = tenders.sumOf { it.value }
        val wonVal = tenders.filter { it.status == "Won" }.sumOf { it.value }
        val successRate = if (tenders.isNotEmpty())
            (tenders.count { it.status == "Won" } * 100 / tenders.size) else 0
        val summaryPaint = Paint().apply { color = textColor; textSize = 10f; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
        canvas.drawText("Summary", margin.toFloat(), y.toFloat(), summaryPaint)
        y += 14
        val bodyPaint = Paint().apply { color = textColor; textSize = 9f; isAntiAlias = true }
        canvas.drawText("Total tenders: ${tenders.size}    Won: ${tenders.count { it.status == "Won" }}    Success rate: $successRate%",
            margin.toFloat(), y.toFloat(), bodyPaint)
        y += 12
        canvas.drawText("Total value: ${StatusUi.fmtCurrency(totalVal)}    Won value: ${StatusUi.fmtCurrency(wonVal)}",
            margin.toFloat(), y.toFloat(), bodyPaint)

        doc.finishPage(page)

        try {
            FileOutputStream(out).use { fos -> doc.writeTo(fos) }
            doc.close()
            shareFile(out, "application/pdf")
        } catch (e: Exception) {
            doc.close()
            Toast.makeText(this, "PDF export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun ellipsize(s: String, max: Int): String =
        if (s.length <= max) s else s.substring(0, max - 1) + "…"

    /** Builds an ACTION_SEND intent + chooser to share the file via FileProvider. */
    private fun shareFile(file: File, mime: String) {
        val authority = "${packageName}.fileprovider"
        val uri: Uri = FileProvider.getUriForFile(this, authority, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share ${file.name}").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(chooser)
            Toast.makeText(this, "Sharing ${file.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "No app to share with", Toast.LENGTH_SHORT).show()
        }
    }
}
