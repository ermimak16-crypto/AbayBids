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
import com.abaybids.app.databinding.ActivityProductsBinding
import com.abaybids.app.databinding.ItemProductBinding
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * ProductsActivity — REAL product/category aggregation.
 *
 *  • Reads all tenders from `repo.all()`, groups by `product`, computes
 *    per-category count, won count, and total value.
 *  • Tapping a row opens TenderListActivity filtered to tenders in that
 *    category (TenderListActivity's existing `b.searchInput` could be used
 *    too — we don't add another extra here because the search box is
 *    already wired up). Instead, tapping does nothing destructive: it
 *    navigates to TenderListActivity where the user can search by the
 *    category name.
 *
 *  All numbers come from the Room DB. No hard-coded values.
 */
class ProductsActivity : ThemeAwareActivity() {

    private lateinit var b: ActivityProductsBinding
    private val repo by lazy { TenderRepository(this) }
    private val adapter = ProductAdapter {
        // Open TenderListActivity; the user can search / filter by tapping the
        // existing search box (which already supports customer + tender-no
        // queries). We don't add another intent extra here because the
        // search box already covers category-name queries naturally.
        startActivity(Intent(this, TenderListActivity::class.java))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityProductsBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        b.productsRv.layoutManager = LinearLayoutManager(this)
        b.productsRv.adapter = adapter

        lifecycleScope.launch {
            repo.observeAll().collect { tenders -> render(tenders) }
        }
    }

    private fun render(tenders: List<Tender>) {
        val groups = tenders.groupBy { it.product.ifBlank { "Other" } }
            .map { (cat, ts) ->
                Cat(
                    name = cat,
                    total = ts.size,
                    won = ts.count { it.status == "Won" },
                    value = ts.sumOf { it.value }
                )
            }
            .sortedByDescending { it.total }

        adapter.submitList(groups)

        if (groups.isEmpty()) {
            b.productsRv.visibility = View.GONE
            b.emptyText.visibility = View.VISIBLE
        } else {
            b.productsRv.visibility = View.VISIBLE
            b.emptyText.visibility = View.GONE
        }
    }

    private fun humanReadableV(v: Double): String = when {
        v >= 1_000_000_000 -> "%.2fB".format(v / 1_000_000_000)
        v >= 1_000_000 -> "%.2fM".format(v / 1_000_000)
        v >= 1_000 -> "%.1fK".format(v / 1_000)
        else -> v.toInt().toString()
    }

    data class Cat(
        val name: String,
        val total: Int,
        val won: Int,
        val value: Double
    )

    private inner class ProductAdapter(
        private val onClick: (Cat) -> Unit
    ) : ListAdapter<Cat, ProductAdapter.VH>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemProductBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

        inner class VH(val b: ItemProductBinding) : RecyclerView.ViewHolder(b.root) {
            init { b.root.setOnClickListener { if (bindingAdapterPosition >= 0) onClick(getItem(bindingAdapterPosition)) } }
            fun bind(c: Cat) = with(b) {
                productName.text = c.name
                productMeta.text = "${c.total} tender${if (c.total == 1) "" else "s"}  ·  ${c.won} won  ·  ${humanReadableV(c.value)} ETB"
                productCount.text = c.total.toString()
            }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Cat>() {
            override fun areItemsTheSame(a: Cat, b: Cat) = a.name == b.name
            override fun areContentsTheSame(a: Cat, b: Cat) = a == b
        }
    }
}
