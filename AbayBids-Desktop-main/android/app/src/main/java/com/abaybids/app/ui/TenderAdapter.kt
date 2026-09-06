package com.abaybids.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.abaybids.app.data.Tender
import com.abaybids.app.databinding.ItemTenderBinding

class TenderAdapter(
    private val onClick: (Tender) -> Unit
) : ListAdapter<Tender, TenderAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemTenderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(val b: ItemTenderBinding) : RecyclerView.ViewHolder(b.root) {
        init { b.root.setOnClickListener { if (bindingAdapterPosition >= 0) onClick(getItem(bindingAdapterPosition)) } }
        fun bind(t: Tender) = with(b) {
            tenderCustomer.text = t.customer.ifBlank { "—" }
            tenderMeta.text = buildString {
                if (t.no.isNotBlank()) append("#").append(t.no)
                if (t.product.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(t.product)
                }
            }
            tenderDeadline.text = t.date
            tenderValue.text = StatusUi.fmtCurrency(t.value)
            tenderStatusPill.text = t.status
            tenderStatusPill.background.setTint(
                ContextCompat.getColor(root.context, StatusUi.color(t.status))
            )
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Tender>() {
            override fun areItemsTheSame(a: Tender, b: Tender) = a.id == b.id
            override fun areContentsTheSame(a: Tender, b: Tender) = a == b
        }
    }
}
