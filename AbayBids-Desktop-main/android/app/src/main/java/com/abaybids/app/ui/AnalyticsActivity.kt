package com.abaybids.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.abaybids.app.data.Tender
import com.abaybids.app.data.TenderRepository
import com.abaybids.app.databinding.ActivityAnalyticsBinding
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * AnalyticsActivity — REAL charts computed from `repo.all()`.
 *
 *  • Win Rate Trend — last 6 months. For each month, win rate =
 *    won / (participated + pending + won + lost) for tenders whose
 *    deadline falls in that month.
 *  • Tender Value Trend — last 6 months. For each month, the sum of
 *    every tender's `value` whose deadline falls in that month.
 *  • Category Split — count of tenders per `product` category, drawn as
 *    a horizontal bar chart.
 *
 *  Charts are drawn with `android.graphics.Canvas` (no third-party libs).
 */
class AnalyticsActivity : ThemeAwareActivity() {

    private lateinit var b: ActivityAnalyticsBinding
    private val repo by lazy { TenderRepository(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAnalyticsBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        lifecycleScope.launch {
            repo.observeAll().collect { tenders ->
                renderWinRate(tenders)
                renderValueTrend(tenders)
                renderCategorySplit(tenders)
            }
        }
    }

    /** Extract (year, month) from a yyyy-MM-dd date string. */
    private fun ymOf(date: String): Pair<Int, Int>? {
        val parts = date.split("-")
        if (parts.size < 2) return null
        val y = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        return y to (m - 1)  // Calendar.MONTH is 0-indexed
    }

    /** Win Rate Trend — last 6 months as % per month. */
    private fun renderWinRate(tenders: List<Tender>) {
        val months = lastNMonths(6)
        val out = ArrayList<Pair<String, Float>>(months.size)
        months.forEach { (label, year, month) ->
            val inMonth = tenders.filter {
                val ym = ymOf(it.date) ?: return@filter false
                ym.first == year && ym.second == month
            }
            val decided = inMonth.count {
                it.status in listOf("Participated", "Pending", "Won", "Lost")
            }
            val won = inMonth.count { it.status == "Won" }
            val rate = if (decided > 0) won.toFloat() * 100f / decided else 0f
            out.add(label to rate)
        }
        b.winRateChart.setData(out)
    }

    /** Tender Value Trend — last 6 months as sum of value per month. */
    private fun renderValueTrend(tenders: List<Tender>) {
        val months = lastNMonths(6)
        val out = ArrayList<Pair<String, Double>>(months.size)
        months.forEach { (label, year, month) ->
            val sum = tenders.filter {
                val ym = ymOf(it.date) ?: return@filter false
                ym.first == year && ym.second == month
            }.sumOf { it.value }
            out.add(label to sum)
        }
        b.valueTrendChart.setData(out)
    }

    /** Category Split — count per product. */
    private fun renderCategorySplit(tenders: List<Tender>) {
        val out = tenders.groupBy { it.product.ifBlank { "Other" } }
            .map { (cat, ts) -> cat to ts.size }
            .sortedByDescending { it.second }
            .take(10)
        b.categoryChart.setData(out)
    }
}
