package com.abaybids.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.abaybids.app.R
import com.abaybids.app.data.Tender
import com.abaybids.app.data.TenderRepository
import com.abaybids.app.databinding.ActivityTenderDetailBinding
import com.abaybids.app.databinding.DetailRowBinding
import com.abaybids.app.notification.NotificationScheduler
import kotlinx.coroutines.launch

/**
 * Deep-link target. Opened by:
 *   - tapping a row in the dashboard/list
 *   - TAPPING A STATUS-BAR NOTIFICATION (action = VIEW, data = abaybids://tender/{id})
 *
 * On launch, extract the tender id from either the deep link URI or the
 * EXTRA_TENDER_ID (which carries the same value). Fall back to -1 (no tender).
 */
class TenderDetailActivity : ThemeAwareActivity() {

    private lateinit var b: ActivityTenderDetailBinding
    private val repo by lazy { TenderRepository(this) }
    private var tenderId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTenderDetailBinding.inflate(layoutInflater)
        setContentView(b.root)

        setSupportActionBar(b.toolbar)
        b.toolbar.navigationIcon = ContextCompat.getDrawable(this, R.drawable.ic_arrow_back)
        b.toolbar.setNavigationOnClickListener { finish() }

        // Handle the deep link first; fall back to extra.
        tenderId = parseIdFromIntent(intent) ?: intent.getLongExtra(EXTRA_TENDER_ID, -1L)

        b.btnEdit.setOnClickListener {
            startActivity(Intent(this, AddTenderActivity::class.java).apply {
                putExtra(AddTenderActivity.EXTRA_TENDER_ID, tenderId)
            })
        }
        b.btnDelete.setOnClickListener {
            lifecycleScope.launch {
                NotificationScheduler.cancelTenderAlarms(this@TenderDetailActivity, tenderId)
                repo.delete(tenderId)
                finish()
            }
        }
        b.btnMarkCpo.setOnClickListener {
            lifecycleScope.launch {
                repo.markCpoCollected(tenderId)
                NotificationScheduler.cancelTenderAlarms(this@TenderDetailActivity, tenderId)
                load() // refresh UI
            }
        }

        load()
    }

    /** Allow being re-launched by a new deep link intent while already on top. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tenderId = parseIdFromIntent(intent) ?: intent.getLongExtra(EXTRA_TENDER_ID, -1L)
        load()
    }

    private fun parseIdFromIntent(intent: Intent?): Long? {
        val data: Uri = intent?.data ?: return null
        if (data.scheme == "abaybids" && data.host == "tender") {
            // Two deep-link shapes:
            //   • abaybids://tender/{localLongId}  — local reminder fired by AlarmManager
            //   • abaybids://tender/firebase/{fid} — FCM push arrived before the
            //     Firestore listener had a chance to upsert the row into Room;
            //     resolve it now from the cross-platform firebaseId.
            val segs = data.pathSegments
            if (segs.size >= 2 && segs[0] == "firebase") {
                val fid = segs[1]
                lifecycleScope.launch {
                    val localId = com.abaybids.app.data.AppDatabase
                        .get(this@TenderDetailActivity).tenderDao().findLocalIdByFirebaseId(fid)
                    if (localId != null) {
                        tenderId = localId
                        load()
                    } else {
                        // Not synced yet — bounce to the dashboard; the listener
                        // will pick the row up in a moment.
                        startActivity(android.content.Intent(this@TenderDetailActivity, MainActivity::class.java))
                        finish()
                    }
                }
                return null
            }
            val last = data.lastPathSegment?.toLongOrNull() ?: return null
            return last
        }
        return null
    }

    private fun load() {
        lifecycleScope.launch {
            val t = repo.byId(tenderId) ?: run {
                finish(); return@launch
            }
            render(t)
            // Re-arm alarms in case the deadline moved (reschedule picks up the change).
            NotificationScheduler.rescheduleAll(this@TenderDetailActivity)
        }
    }

    private fun render(t: Tender) = with(b) {
        supportActionBar?.title = t.customer
        detailCustomer.text = t.customer
        detailNo.text = if (t.no.isNotBlank()) "# ${t.no}" else ""
        detailStatusPill.text = t.status
        detailStatusPill.background.setTint(
            ContextCompat.getColor(this@TenderDetailActivity, StatusUi.color(t.status))
        )

        detailDeadline.text = t.date
        val days = StatusUi.daysUntil(t.date)
        if (days == null) {
            detailDaysLeft.text = "—"
            detailDaysLabel.text = getString(R.string.deadline)
        } else if (days < 0) {
            detailDaysLeft.text = (-days).toString()
            detailDaysLabel.text = getString(R.string.days_overdue)
            detailDaysLeft.setTextColor(ContextCompat.getColor(this@TenderDetailActivity, R.color.status_lost))
        } else {
            detailDaysLeft.text = days.toString()
            detailDaysLabel.text = getString(R.string.days_left)
            detailDaysLeft.setTextColor(
                if (days <= 1) ContextCompat.getColor(this@TenderDetailActivity, R.color.status_pending)
                else ContextCompat.getColor(this@TenderDetailActivity, R.color.brand_primary)
            )
        }

        bindRow(b.rowCategory, getString(R.string.category), t.product.ifBlank { "—" })
        bindRow(b.rowValue, getString(R.string.est_value), StatusUi.fmtCurrency(t.value))
        bindRow(b.rowReminder, getString(R.string.reminder_set),
            "${t.reminderDays} days before deadline")
        bindRow(b.rowContact, getString(R.string.contact_person), t.contact.ifBlank { "—" })
        bindRow(b.rowPhone, getString(R.string.phone), t.phone.ifBlank { "—" })
        bindRow(b.rowCpo, getString(R.string.f_cpo),
            if (t.cpo.equals("Yes", true))
                "Deposited · ${StatusUi.fmtCurrency(t.cpoAmount)}" +
                if (t.cpoCollected) " (collected)" else " (pending collection)"
            else "Not deposited"
        )
        bindRow(b.rowRemark, getString(R.string.remark), t.remark.ifBlank { "—" })

        b.btnMarkCpo.visibility = if (t.cpo.equals("Yes", true) && !t.cpoCollected) View.VISIBLE else View.GONE
    }

    private fun bindRow(row: DetailRowBinding, label: String, value: String) {
        row.detailRowLabel.text = label
        row.detailRowValue.text = value
    }

    companion object {
        const val EXTRA_TENDER_ID = "tender_id"
    }
}
