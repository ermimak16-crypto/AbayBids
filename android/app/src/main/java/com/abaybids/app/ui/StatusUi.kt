package com.abaybids.app.ui

import com.abaybids.app.R
import com.abaybids.app.data.Tender

/** Single source of truth for status colors + status order. */
object StatusUi {
    val order = listOf("Participated", "Pending", "Won", "Lost", "Not Participated")

    fun color(status: String): Int = when (status) {
        "Participated"    -> R.color.status_participated
        "Pending"         -> R.color.status_pending
        "Won"             -> R.color.status_won
        "Lost"             -> R.color.status_lost
        "Not Participated"-> R.color.status_notpart
        else              -> R.color.status_pending
    }

    fun icon(status: String): Int = when (status) {
        "Participated"    -> R.drawable.ic_assignment
        "Pending"         -> R.drawable.ic_schedule
        "Won"             -> R.drawable.ic_trophy
        "Lost"            -> R.drawable.ic_cancel
        "Not Participated"-> R.drawable.ic_remove_circle_outline
        else              -> R.drawable.ic_schedule
    }

    fun daysUntil(iso: String): Int? {
        return try {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val cal = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }
            val now = cal.timeInMillis
            val target = fmt.parse(iso) ?: return null
            val targetCal = java.util.Calendar.getInstance().apply {
                time = target
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }
            ((targetCal.timeInMillis - now) / (24L * 60 * 60 * 1000)).toInt()
        } catch (e: Exception) { null }
    }

    fun fmtCurrency(v: Double): String {
        // ETB with thousands separators, no decimals for large amounts.
        val nf = java.text.NumberFormat.getNumberInstance(java.util.Locale.US)
        nf.maximumFractionDigits = if (v >= 1000) 0 else 2
        return "${nf.format(v)} ETB"
    }
}
