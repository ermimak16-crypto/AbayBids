package com.abaybids.app.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.abaybids.app.R
import com.abaybids.app.data.Tender
import com.abaybids.app.data.TenderRepository
import com.abaybids.app.databinding.ActivityTenderListBinding
import com.abaybids.app.notification.NotificationScheduler
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TenderListActivity : ThemeAwareActivity() {

    private lateinit var b: ActivityTenderListBinding
    private val repo by lazy { TenderRepository(this) }
    private val adapter = TenderAdapter { openDetail(it.id) }

    private var query: String = ""
    private var statusFilter: String? = null
    private var customerFilter: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTenderListBinding.inflate(layoutInflater)
        setContentView(b.root)

        setSupportActionBar(b.toolbar)
        b.toolbar.navigationIcon = ContextCompat.getDrawable(this, R.drawable.ic_arrow_back)
        b.toolbar.setNavigationOnClickListener { finish() }
        supportActionBar?.title = getString(R.string.title_tenders)

        // Optional customer filter (set when ClientsActivity launches this
        // with EXTRA_CUSTOMER). The title reflects the active filter.
        customerFilter = intent.getStringExtra(EXTRA_CUSTOMER)
        if (!customerFilter.isNullOrBlank()) {
            supportActionBar?.title = customerFilter
            supportActionBar?.subtitle = "Tenders for this client"
        }

        b.tendersRv.layoutManager = LinearLayoutManager(this)
        b.tendersRv.adapter = adapter

        b.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim()?.lowercase().orEmpty()
                applyFilter()
            }
        })

        b.filterChips.setOnCheckedStateChangeListener { group, ids ->
            statusFilter = when (group.checkedChipId) {
                R.id.chipParticipated -> "Participated"
                R.id.chipPending -> "Pending"
                R.id.chipWon -> "Won"
                R.id.chipLost -> "Lost"
                else -> null
            }
            applyFilter()
        }

        lifecycleScope.launch {
            repo.observeAll().collectLatest { tenders -> allTenders = tenders; applyFilter() }
        }
    }

    private var allTenders: List<Tender> = emptyList()

    private fun applyFilter() {
        val list = allTenders.filter { t ->
            (statusFilter == null || t.status == statusFilter) &&
            (customerFilter == null || t.customer.equals(customerFilter, ignoreCase = true)) &&
            (query.isBlank() ||
                t.customer.lowercase().contains(query) ||
                t.no.lowercase().contains(query))
        }.sortedBy { it.date }
        adapter.submitList(list)
        if (list.isEmpty()) {
            b.tendersRv.visibility = View.GONE
            b.emptyText.visibility = View.VISIBLE
            b.emptyText.text = if (query.isNotBlank() || statusFilter != null || customerFilter != null)
                getString(R.string.empty_filter) else getString(R.string.empty_tenders)
        } else {
            b.tendersRv.visibility = View.VISIBLE
            b.emptyText.visibility = View.GONE
        }
    }

    private fun openDetail(id: Long) {
        val i = Intent(this, TenderDetailActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = NotificationScheduler.buildDeepLinkUri(id)
            putExtra(TenderDetailActivity.EXTRA_TENDER_ID, id)
        }
        startActivity(i)
    }

    companion object {
        const val EXTRA_CUSTOMER = "customer_filter"
    }
}
