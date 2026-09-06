package com.abaybids.app.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.abaybids.app.R
import com.abaybids.app.data.Tender
import com.abaybids.app.data.TenderRepository
import com.abaybids.app.databinding.ActivityAddTenderBinding
import com.abaybids.app.notification.NotificationScheduler
import kotlinx.coroutines.launch

class AddTenderActivity : ThemeAwareActivity() {

    private lateinit var b: ActivityAddTenderBinding
    private val repo by lazy { TenderRepository(this) }
    private var editId: Long = 0L

    private val categories = listOf("Construction","Consultancy","Logistics","IT Equipment",
        "Medical Supplies","Facility Maintenance","Vehicles","Fuel Supply","Other")
    private val statuses = listOf("Pending","Participated","Won","Lost","Not Participated")
    private val yesNo = listOf("Yes","No")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAddTenderBinding.inflate(layoutInflater)
        setContentView(b.root)

        setSupportActionBar(b.toolbar)
        b.toolbar.navigationIcon = ContextCompat.getDrawable(this, R.drawable.ic_arrow_back)
        b.toolbar.setNavigationOnClickListener { finish() }
        supportActionBar?.title = getString(R.string.title_add_tender)

        // Dropdowns
        b.inpProduct.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, categories))
        b.inpStatus.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, statuses))
        b.inpCpo.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, yesNo))

        editId = intent.getLongExtra(EXTRA_TENDER_ID, 0L)
        if (editId != 0L) {
            supportActionBar?.title = getString(R.string.title_edit_tender)
            lifecycleScope.launch {
                val t = repo.byId(editId) ?: return@launch
                b.inpCustomer.setText(t.customer)
                b.inpNo.setText(t.no)
                b.inpProduct.setText(t.product, false)
                b.inpDate.setText(t.date)
                b.inpValue.setText(t.value.toInt().toString())
                b.inpStatus.setText(t.status, false)
                b.inpCpo.setText(t.cpo, false)
                b.inpCpoAmount.setText(t.cpoAmount.toInt().toString())
                b.inpReminderDays.setText(t.reminderDays.toString())
                b.inpContact.setText(t.contact)
                b.inpPhone.setText(t.phone)
                b.inpRemark.setText(t.remark)
            }
        } else {
            b.inpProduct.setText("Construction", false)
            b.inpStatus.setText("Pending", false)
            b.inpCpo.setText("No", false)
        }

        b.btnSave.setOnClickListener { save() }
    }

    private fun save() {
        val customer = b.inpCustomer.text?.toString().orEmpty().trim()
        if (customer.isBlank()) {
            b.inpCustomer.error = getString(R.string.err_required)
            b.inpCustomer.requestFocus()
            return
        }
        val date = b.inpDate.text?.toString().orEmpty().trim()
        if (!date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
            b.inpDate.error = getString(R.string.err_date_format)
            b.inpDate.requestFocus()
            return
        }
        val status = b.inpStatus.text?.toString() ?: "Pending"
        val product = b.inpProduct.text?.toString()?.ifBlank { "Other" } ?: "Other"
        val cpo = b.inpCpo.text?.toString() ?: "No"
        val value = b.inpValue.text?.toString()?.toDoubleOrNull() ?: 0.0
        val cpoAmount = b.inpCpoAmount.text?.toString()?.toDoubleOrNull() ?: 0.0
        val reminderDays = b.inpReminderDays.text?.toString()?.toIntOrNull() ?: 3

        val tender = Tender(
            id = if (editId != 0L) editId else 0L,
            customer = customer,
            no = b.inpNo.text?.toString().orEmpty().trim(),
            product = product,
            date = date,
            value = value,
            status = status,
            cpo = cpo,
            cpoAmount = cpoAmount,
            cpoCollected = false,
            reminderDays = reminderDays,
            contact = b.inpContact.text?.toString().orEmpty().trim(),
            phone = b.inpPhone.text?.toString().orEmpty().trim(),
            remark = b.inpRemark.text?.toString().orEmpty().trim(),
            // Cross-platform reporting fields — sensible defaults; the Windows
            // dashboard reads/writes these via its own form. Kept in sync by
            // FirebaseSync so the two apps share the same schema.
            repStatus = "Pending",
            vType = if (product in listOf("Vehicles", "Vehicle Purchase")) "Bus" else "Bus",
            fType = if (product in listOf("Vehicles", "Vehicle Purchase")) "Diesel" else "Diesel"
        )
        lifecycleScope.launch {
            val newId = repo.save(tender)
            // Schedule alarms for the just-saved tender (and re-arm everything else).
            NotificationScheduler.scheduleTenderAlarms(
                this@AddTenderActivity,
                tender.copy(id = if (newId != 0L) newId else tender.id)
            )
            Toast.makeText(this@AddTenderActivity, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    companion object {
        const val EXTRA_TENDER_ID = "tender_id"
    }
}
