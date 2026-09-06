package com.abaybids.app.ui

import android.content.Intent
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
import com.abaybids.app.data.Tender
import com.abaybids.app.data.TenderRepository
import com.abaybids.app.databinding.ActivityClientsBinding
import com.abaybids.app.databinding.ItemClientBinding
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * ClientsActivity — REAL client/organization aggregation.
 *
 *  • Reads every tender from `repo.all()`, groups by `customer`, computes
 *    per-client:
 *      - total tenders (count)
 *      - won count
 *      - success rate (won / total %)
 *      - total value (sum of all tenders' value)
 *  • Summary header shows the overall totals.
 *  • Tapping a client opens TenderListActivity filtered to that customer
 *    via EXTRA_CUSTOMER.
 *
 *  All numbers come from the Room DB. No hard-coded values.
 */
class ClientsActivity : ThemeAwareActivity() {

    private lateinit var b: ActivityClientsBinding
    private val repo by lazy { TenderRepository(this) }
    private val adapter = ClientAdapter { client ->
        startActivity(Intent(this, TenderListActivity::class.java).apply {
            putExtra(TenderListActivity.EXTRA_CUSTOMER, client.customer)
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityClientsBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        b.clientsRv.layoutManager = LinearLayoutManager(this)
        b.clientsRv.adapter = adapter

        lifecycleScope.launch {
            repo.observeAll().collect { tenders ->
                render(tenders)
            }
        }
    }

    /** Aggregate tenders by customer and render the summary + the list. */
    private fun render(tenders: List<Tender>) {
        val clients = tenders.groupBy { it.customer.ifBlank { "Unknown" } }
            .map { (customer, ts) ->
                ClientAggregate(
                    customer = customer,
                    total = ts.size,
                    won = ts.count { it.status == "Won" },
                    value = ts.sumOf { it.value }
                )
            }
            .sortedByDescending { it.value }

        // Summary header
        b.totalCount.text = clients.size.toString()
        b.totalWon.text = clients.sumOf { it.won }.toString()
        b.totalValue.text = humanReadableV(clients.sumOf { it.value })

        adapter.submitList(clients)

        if (clients.isEmpty()) {
            b.clientsRv.visibility = View.GONE
            b.emptyText.visibility = View.VISIBLE
        } else {
            b.clientsRv.visibility = View.VISIBLE
            b.emptyText.visibility = View.GONE
        }
    }

    private fun humanReadableV(v: Double): String = when {
        v >= 1_000_000_000 -> "%.2fB".format(v / 1_000_000_000)
        v >= 1_000_000 -> "%.2fM".format(v / 1_000_000)
        v >= 1_000 -> "%.1fK".format(v / 1_000)
        else -> v.toInt().toString()
    }

    data class ClientAggregate(
        val customer: String,
        val total: Int,
        val won: Int,
        val value: Double
    ) {
        val successRate: Int get() = if (total > 0) (won * 100 / total) else 0
    }

    // ─── RecyclerView adapter ───────────────────────────────────────────────────

    private inner class ClientAdapter(
        private val onClick: (ClientAggregate) -> Unit
    ) : ListAdapter<ClientAggregate, ClientAdapter.VH>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemClientBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

        inner class VH(val b: ItemClientBinding) : RecyclerView.ViewHolder(b.root) {
            init { b.root.setOnClickListener { if (bindingAdapterPosition >= 0) onClick(getItem(bindingAdapterPosition)) } }
            fun bind(c: ClientAggregate) = with(b) {
                clientName.text = c.customer
                clientMeta.text = buildString {
                    append(c.total).append(" tender").append(if (c.total == 1) "" else "s")
                    append("  ·  ").append(c.won).append(" won")
                    append("  ·  ").append(c.successRate).append("% success")
                }
                clientValue.text = "${humanReadableV(c.value)} ETB"
            }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<ClientAggregate>() {
            override fun areItemsTheSame(a: ClientAggregate, b: ClientAggregate) = a.customer == b.customer
            override fun areContentsTheSame(a: ClientAggregate, b: ClientAggregate) = a == b
        }
    }
}
