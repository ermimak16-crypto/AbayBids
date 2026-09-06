package com.abaybids.app.ui

import android.app.AlertDialog
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.abaybids.app.R
import com.abaybids.app.data.AppDatabase
import com.abaybids.app.data.Contract
import com.abaybids.app.databinding.ActivityContractsBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Native Android Contracts management — no WebView.
 *
 * Feature parity with the Windows dashboard:
 *  - Active & Expiring contracts shown by default (with count badge)
 *  - Expired / Completed / Terminated contracts archived by year (collapsed)
 *  - "+ Add Contract" FAB → native dialog form (manual entry, not tied to a tender)
 *  - Tap a contract → edit dialog
 *  - Delete button per contract (with confirmation)
 *  - Contracts persist in Room DB (native SQLite, works offline)
 */
class ContractsActivity : ThemeAwareActivity() {

    private lateinit var b: ActivityContractsBinding
    private val dao by lazy { AppDatabase.get(this).contractDao() }
    private var showArchived = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityContractsBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        b.showArchivedBtn.setOnClickListener {
            showArchived = !showArchived
            b.archivedContainer.visibility = if (showArchived) View.VISIBLE else View.GONE
            b.showArchivedBtn.text = if (showArchived) "  Hide archived" else "  Show archived"
        }

        b.fabAdd.setOnClickListener { showEditDialog(null) }

        lifecycleScope.launch {
            dao.observeAll().collectLatest { contracts ->
                render(contracts)
            }
        }
    }

    private fun effectiveStatus(c: Contract): String {
        if (c.status == "Completed" || c.status == "Terminated") return c.status
        if (c.endDate.isBlank()) return "Active"
        val days = daysUntil(c.endDate)
        if (days == null) return "Active"
        if (days < 0) return "Expired"
        if (days <= c.reminderDaysBeforeExpiry) return "ExpiringSoon"
        return "Active"
    }

    private fun daysUntil(iso: String): Int? {
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val target = fmt.parse(iso) ?: return null
            val diff = target.time - System.currentTimeMillis()
            TimeUnit.DAYS.convert(diff, TimeUnit.MILLISECONDS).toInt()
        } catch (e: Exception) { null }
    }

    private fun render(contracts: List<Contract>) {
        val active = contracts.filter { effectiveStatus(it).let { s -> s == "Active" || s == "ExpiringSoon" } }
        val archived = contracts.filter { effectiveStatus(it).let { s -> s == "Expired" || s == "Completed" || s == "Terminated" } }

        b.activeCount.text = active.size.toString()
        b.activeContractsList.removeAllViews()

        if (active.isEmpty()) {
            b.activeContractsList.addView(emptyRow("No active contracts. Tap + to add one."))
        } else {
            active.sortedBy { it.endDate }.forEach { c -> b.activeContractsList.addView(contractRow(c)) }
        }

        // Archived grouped by year
        b.archivedContainer.removeAllViews()
        if (archived.isNotEmpty()) {
            val byYear = archived.groupBy { c -> c.endDate.substringBefore('-').ifBlank { "Unknown" } }
            byYear.keys.sortedDescending().forEach { yr ->
                val items = byYear[yr] ?: return@forEach
                val card = archivedYearCard(yr, items)
                b.archivedContainer.addView(card)
            }
        }
    }

    private fun emptyRow(msg: String): View {
        return TextView(this).apply {
            text = msg
            setTextColor(getColor(R.color.text_muted))
            setPadding(0, 24, 0, 24)
            gravity = Gravity.CENTER
        }
    }

    private fun contractRow(c: Contract): View {
        val eff = effectiveStatus(c)
        val days = if (c.endDate.isNotBlank()) daysUntil(c.endDate) else null
        val daysStr = when {
            days == null -> "—"
            days < 0 -> "${-days}d overdue"
            days == 0 -> "Today"
            else -> "${days}d"
        }
        val period = buildString {
            if (c.startDate.isNotBlank()) append(c.startDate)
            if (c.endDate.isNotBlank()) { if (isNotEmpty()) append(" – "); append(c.endDate) }
            if (isEmpty()) append("—")
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 12)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { showEditDialog(c) }
        }

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        info.addView(TextView(this).apply {
            text = c.customer
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        info.addView(TextView(this).apply {
            text = "#${c.tenderNo}  ·  ${c.contractNo.ifBlank { "—" }}  ·  $period"
            setTextColor(getColor(R.color.text_muted))
            textSize = 11f
        })
        row.addView(info)

        // Status chip
        row.addView(TextView(this).apply {
            text = eff
            setTextColor(android.graphics.Color.WHITE)
            textSize = 10f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(20, 8, 20, 8)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 24f
                setColor(statusColor(eff))
            }
        })

        // Days
        row.addView(TextView(this).apply {
            text = daysStr
            setTextColor(if (days != null && days < 0) getColor(R.color.status_lost) else getColor(R.color.text_muted))
            textSize = 11f
            setPadding(12, 0, 0, 0)
        })

        // Delete (vector icon — no emoji)
        row.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_delete)
            setColorFilter(getColor(R.color.status_lost))
            setPadding(16, 0, 0, 0)
            setOnClickListener {
                AlertDialog.Builder(this@ContractsActivity)
                    .setTitle("Delete Contract")
                    .setMessage("Delete contract for \"${c.customer}\" (#${c.tenderNo})?\nThis cannot be undone.")
                    .setPositiveButton("Delete") { _, _ ->
                        lifecycleScope.launch {
                            dao.deleteById(c.id)
                            Toast.makeText(this@ContractsActivity, "Contract deleted", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })

        return row
    }

    private fun archivedYearCard(year: String, items: List<Contract>): View {
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 16
            }
            radius = 16f
            cardElevation = 2f
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "Archived — $year"
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = items.size.toString()
            setTextColor(getColor(R.color.text_muted))
            textSize = 12f
            setPadding(8, 0, 8, 0)
        })
        val toggle = TextView(this).apply {
            text = "Show"
            setTextColor(getColor(R.color.brand_primary))
            textSize = 12f
            setPadding(8, 0, 0, 0)
            isClickable = true
        }
        header.addView(toggle)
        inner.addView(header)

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        items.forEach { c -> list.addView(contractRow(c)) }
        inner.addView(list)

        toggle.setOnClickListener {
            list.visibility = if (list.visibility == View.GONE) View.VISIBLE else View.GONE
            toggle.text = if (list.visibility == View.VISIBLE) "Hide" else "Show"
        }

        card.addView(inner)
        return card
    }

    private fun statusColor(s: String): Int = when (s) {
        "Active" -> getColor(R.color.status_won)
        "ExpiringSoon" -> getColor(R.color.status_pending)
        "Expired" -> getColor(R.color.status_lost)
        "Completed" -> getColor(R.color.status_active)
        "Terminated" -> getColor(R.color.text_muted)
        else -> getColor(R.color.text_muted)
    }

    private fun showEditDialog(existing: Contract?) {
        val isEdit = existing != null
        val ctx = this
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }
        fun et(hint: String, value: String = "", inputType: Int = android.text.InputType.TYPE_CLASS_TEXT): EditText {
            return EditText(ctx).apply {
                this.hint = hint
                setText(value)
                this.inputType = inputType
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = 12
                }
            }
        }
        val etCustomer = et("Customer / Organization *", existing?.customer ?: "")
        val etTenderNo = et("Tender No", existing?.tenderNo ?: "")
        val etContractNo = et("Contract No", existing?.contractNo ?: "")
        val etStart = et("Start date (YYYY-MM-DD)", existing?.startDate ?: "")
        val etEnd = et("End date (YYYY-MM-DD)", existing?.endDate ?: "")
        val etValue = et("Value (ETB)", existing?.value?.toInt()?.toString() ?: "", android.text.InputType.TYPE_CLASS_NUMBER)
        val etContact = et("Contact person", existing?.contactPerson ?: "")
        val etPhone = et("Phone", existing?.contactPhone ?: "", android.text.InputType.TYPE_CLASS_PHONE)
        val etReminder = et("Reminder days before expiry", (existing?.reminderDaysBeforeExpiry ?: 14).toString(), android.text.InputType.TYPE_CLASS_NUMBER)

        listOf(etCustomer, etTenderNo, etContractNo, etStart, etEnd, etValue, etContact, etPhone, etReminder).forEach { container.addView(it) }

        AlertDialog.Builder(ctx)
            .setTitle(if (isEdit) "Edit Contract" else "New Contract")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val customer = etCustomer.text.toString().trim()
                if (customer.isBlank()) { Toast.makeText(ctx, "Customer name is required", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                val c = (existing ?: Contract(customer = customer)).copy(
                    customer = customer,
                    tenderNo = etTenderNo.text.toString().trim(),
                    contractNo = etContractNo.text.toString().trim(),
                    startDate = etStart.text.toString().trim(),
                    endDate = etEnd.text.toString().trim(),
                    value = etValue.text.toString().trim().toDoubleOrNull() ?: 0.0,
                    contactPerson = etContact.text.toString().trim(),
                    contactPhone = etPhone.text.toString().trim(),
                    reminderDaysBeforeExpiry = etReminder.text.toString().trim().toIntOrNull() ?: 14,
                    updatedAt = System.currentTimeMillis()
                )
                lifecycleScope.launch {
                    if (isEdit) dao.update(c) else dao.insert(c)
                    Toast.makeText(ctx, if (isEdit) "Contract updated" else "Contract created", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
