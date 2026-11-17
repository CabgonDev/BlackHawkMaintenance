package com.cabgon.blackhawk.ui.fragments

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.data.PartOccurrence

class PartOccurrenceAdapter(
    private val onClick: (PartOccurrence) -> Unit
) : RecyclerView.Adapter<PartOccurrenceAdapter.VH>() {

    private var items: List<PartOccurrence> = emptyList()

    fun submitList(newItems: List<PartOccurrence>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_part_occurrence, parent, false)
        return VH(v, onClick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class VH(itemView: View, private val onClick: (PartOccurrence) -> Unit) :
        RecyclerView.ViewHolder(itemView) {

        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvSnippet: TextView = itemView.findViewById(R.id.tvSnippet)

        fun bind(occ: PartOccurrence) {
            tvTitle.text = "${occ.manualLabel} · Página ${occ.page1} · Ocurrencia ${occ.occurrenceOnPage}"
            tvSnippet.text = occ.snippet
            itemView.setOnClickListener { onClick(occ) }
        }
    }
}
