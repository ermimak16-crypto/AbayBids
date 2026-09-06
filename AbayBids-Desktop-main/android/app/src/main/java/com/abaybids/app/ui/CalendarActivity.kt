package com.abaybids.app.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.gridlayout.widget.GridLayout
import androidx.lifecycle.lifecycleScope
import com.abaybids.app.R
import com.abaybids.app.data.Tender
import com.abaybids.app.data.TenderRepository
import com.abaybids.app.databinding.ActivityCalendarBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * CalendarActivity — REAL month-grid view of tender deadlines.
 *
 *  • 7-column day grid for the current month with prev / next month buttons.
 *  • Each day cell shows a dot marker if any tender has a deadline on that
 *    date (queried from TenderRepository → Room).
 *  • Marker color reflects urgency: status_pending for ≤ 1 day / overdue,
 *    brand_primary for 2-7 days, status_won for later.
 *  • Tapping a marked day opens a dialog listing every tender due that day
 *    (customer, tender no, status, value). Tapping one of those opens
 *    TenderDetailActivity with EXTRA_TENDER_ID.
 *  • Observes `repo.observeAll()` so the calendar refreshes live.
 */
class CalendarActivity : ThemeAwareActivity() {

    private lateinit var b: ActivityCalendarBinding
    private val repo by lazy { TenderRepository(this) }

    private val cal = Calendar.getInstance()
    private val monthFmt = SimpleDateFormat("MMMM yyyy", Locale.US)
    private val isoFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val weekHeaders = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

    private var allTenders: List<Tender> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCalendarBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        // Build the weekday header row.
        b.weekdayHeader.removeAllViews()
        weekHeaders.forEach { label ->
            val tv = TextView(this).apply {
                text = label
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                setTextColor(ContextCompat.getColor(this@CalendarActivity, R.color.text_muted))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            b.weekdayHeader.addView(tv)
        }

        b.prevMonth.setOnClickListener {
            cal.add(Calendar.MONTH, -1)
            renderGrid()
        }
        b.nextMonth.setOnClickListener {
            cal.add(Calendar.MONTH, 1)
            renderGrid()
        }

        lifecycleScope.launch {
            repo.observeAll().collectLatest { tenders ->
                allTenders = tenders
                renderGrid()
            }
        }
    }

    /** Renders the 6×7 day grid for [cal]'s current month. */
    private fun renderGrid() {
        b.monthLabel.text = monthFmt.format(cal.time)
        b.dayGrid.removeAllViews()

        // First day-of-week-of-month and total days.
        val firstCal = (cal.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, 1)
        }
        val firstDayOfWeek = firstCal.get(Calendar.DAY_OF_WEEK) - 1  // Sunday = 0
        val daysInMonth = firstCal.getActualMaximum(Calendar.DAY_OF_MONTH)

        val todayCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val yearStr = cal.get(Calendar.YEAR).toString()
        val monthStr = String.format(Locale.US, "%02d", cal.get(Calendar.MONTH) + 1)

        // Index tenders by their date string so we can do an O(1) lookup per cell.
        val byDate: Map<String, List<Tender>> = allTenders
            .filter { it.date.startsWith("$yearStr-$monthStr") }
            .groupBy { it.date }

        val totalCells = 42  // 6 rows × 7 cols — fixed grid avoids reshuffle on month change.

        for (i in 0 until totalCells) {
            val dayNum = i - firstDayOfWeek + 1
            val cell = buildDayCell(
                dayNum = if (dayNum in 1..daysInMonth) dayNum else 0,
                isToday = run {
                    if (dayNum !in 1..daysInMonth) return@run false
                    val c = (firstCal.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, dayNum) }
                    c.get(Calendar.YEAR) == todayCal.get(Calendar.YEAR) &&
                        c.get(Calendar.MONTH) == todayCal.get(Calendar.MONTH) &&
                        c.get(Calendar.DAY_OF_MONTH) == todayCal.get(Calendar.DAY_OF_MONTH)
                },
                tendersOnDay = if (dayNum in 1..daysInMonth) {
                    val ds = String.format(Locale.US, "%04d-%02d-%02d",
                        cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, dayNum)
                    byDate[ds].orEmpty()
                } else emptyList()
            )
            b.dayGrid.addView(cell)
        }
    }

    /** Builds one day cell — number + optional deadline dot. */
    private fun buildDayCell(dayNum: Int, isToday: Boolean, tendersOnDay: List<Tender>): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = (52 * resources.displayMetrics.density).toInt()
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                setMargins(2, 2, 2, 2)
            }
            background = ContextCompat.getDrawable(this@CalendarActivity, R.color.bg_card)
            if (dayNum == 0) {
                alpha = 0.25f
                isClickable = false
            } else if (tendersOnDay.isNotEmpty()) {
                isClickable = true
                isFocusable = true
                setOnClickListener { showDayTenders(tendersOnDay) }
            }
        }

        val dayText = TextView(this).apply {
            text = if (dayNum == 0) "" else dayNum.toString()
            setTextColor(
                if (isToday) ContextCompat.getColor(this@CalendarActivity, R.color.brand_primary)
                else ContextCompat.getColor(this@CalendarActivity, R.color.text_primary)
            )
            textSize = if (isToday) 16f else 13f
            typeface = if (isToday) android.graphics.Typeface.DEFAULT_BOLD
                       else android.graphics.Typeface.DEFAULT
        }
        container.addView(dayText)

        // Dot marker — colored by urgency of the most-urgent tender that day.
        if (tendersOnDay.isNotEmpty()) {
            val mostUrgent = tendersOnDay.mapNotNull { t ->
                StatusUi.daysUntil(t.date)?.let { d -> t to d }
            }.minByOrNull { it.second }

            val colorRes = if (mostUrgent == null) R.color.brand_primary
                else when {
                    mostUrgent.second <= 1 -> R.color.status_pending
                    mostUrgent.second <= 7 -> R.color.brand_primary
                    else -> R.color.status_won
                }
            val dot = View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(ContextCompat.getColor(this@CalendarActivity, colorRes))
                }
                layoutParams = LinearLayout.LayoutParams(
                    (10 * resources.displayMetrics.density).toInt(),
                    (10 * resources.displayMetrics.density).toInt()
                ).apply { topMargin = 4 }
            }
            container.addView(dot)
        }
        return container
    }

    /** Bottom-sheet-style dialog listing tenders due on a particular day. */
    private fun showDayTenders(tenders: List<Tender>) {
        val parent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val title = TextView(this).apply {
            text = "${tenders.size} tender(s) due ${tenders.firstOrNull()?.date.orEmpty()}"
            setTextColor(ContextCompat.getColor(this@CalendarActivity, R.color.text_primary))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 12)
        }
        parent.addView(title)

        tenders.sortedBy { it.customer }.forEach { t ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 14, 0, 14)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val ctx = this@CalendarActivity
                    ctx.startActivity(Intent(ctx, TenderDetailActivity::class.java).apply {
                        putExtra(TenderDetailActivity.EXTRA_TENDER_ID, t.id)
                    })
                }
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this).apply {
                text = t.customer.ifBlank { "—" }
                setTextColor(ContextCompat.getColor(this@CalendarActivity, R.color.text_primary))
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            info.addView(TextView(this).apply {
                text = "#${t.no}  ·  ${t.date}  ·  ${StatusUi.fmtCurrency(t.value)}"
                setTextColor(ContextCompat.getColor(this@CalendarActivity, R.color.text_muted))
                textSize = 12f
            })
            row.addView(info)

            val pill = TextView(this).apply {
                text = t.status
                setTextColor(android.graphics.Color.WHITE)
                textSize = 10f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(20, 8, 20, 8)
                background = ContextCompat.getDrawable(this@CalendarActivity, R.drawable.pill_status)
                background.setTint(
                    ContextCompat.getColor(this@CalendarActivity, StatusUi.color(t.status))
                )
            }
            row.addView(pill)

            parent.addView(row)
            parent.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1
                ).apply { setMargins(0, 0, 0, 0) }
                setBackgroundColor(ContextCompat.getColor(this@CalendarActivity, R.color.divider))
            })
        }

        AlertDialog.Builder(this)
            .setView(parent)
            .setPositiveButton("Close", null)
            .show()
    }
}
