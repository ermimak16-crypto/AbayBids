package com.abaybids.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.abaybids.app.R
import com.abaybids.app.data.AppDatabase
import com.abaybids.app.data.BidHistory
import com.abaybids.app.databinding.ActivityBidHistoryBinding
import com.abaybids.app.databinding.ItemBidHistoryBinding
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BidHistoryActivity — REAL status-change audit log.
 *
 *  • Reads from `bid_history` table (populated by [TenderRepository.save]
 *    every time an existing tender's status changes).
 *  • RecyclerView showing date/time, customer, tender #, value, and a
 *    from → to status pill transition.
 *  • Observes the DAO so new entries appear live as the user edits tenders.
 */
class BidHistoryActivity : ThemeAwareActivity() {

    private lateinit var b: ActivityBidHistoryBinding
    private val dao by lazy { AppDatabase.get(this).bidHistoryDao() }
    private val adapter = HistoryAdapter()

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityBidHistoryBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        b.historyRv.layoutManager = LinearLayoutManager(this)
        b.historyRv.adapter = adapter

        lifecycleScope.launch {
            dao.observeAll().collect { entries ->
                adapter.submitList(entries)
                if (entries.isEmpty()) {
                    b.historyRv.visibility = View.GONE
                    b.emptyText.visibility = View.VISIBLE
                } else {
                    b.historyRv.visibility = View.VISIBLE
                    b.emptyText.visibility = View.GONE
                }
            }
        }
    }

    private inner class HistoryAdapter :
        ListAdapter<BidHistory, HistoryAdapter.VH>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemBidHistoryBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

        inner class VH(val b: ItemBidHistoryBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(e: BidHistory) = with(b) {
                historyTimestamp.text = fmt.format(Date(e.timestamp))
                historyCustomer.text = e.customer.ifBlank { "—" }
                historyTenderNo.text = if (e.tenderNo.isNotBlank()) "#${e.tenderNo}" else ""
                historyValue.text = StatusUi.fmtCurrency(e.value)

                historyFrom.text = e.fromStatus.ifBlank { "—" }
                historyFrom.background.setTint(
                    ContextCompat.getColor(root.context, StatusUi.color(e.fromStatus))
                )
                historyTo.text = e.toStatus.ifBlank { "—" }
                historyTo.background.setTint(
                    ContextCompat.getColor(root.context, StatusUi.color(e.toStatus))
                )
            }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<BidHistory>() {
            override fun areItemsTheSame(a: BidHistory, b: BidHistory) = a.id == b.id
            override fun areContentsTheSame(a: BidHistory, b: BidHistory) = a == b
        }
    }
}
