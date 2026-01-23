package com.cabgon.blackhawk.ui.fragments

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.data.RAGIndex

class LocalPartHitAdapter(
    private val onClick: (RAGIndex.Hit) -> Unit
) : RecyclerView.Adapter<LocalPartHitAdapter.VH>() {

    private var items: List<RAGIndex.Hit> = emptyList()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val txtTitle: TextView = v.findViewById(R.id.txtLocalHitTitle)
        val txtSnippet: TextView = v.findViewById(R.id.txtLocalHitSnippet)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_local_part_hit, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val hit = items[position]
        val manualName = hit.manual.substringAfterLast('/').substringBeforeLast('.')
        holder.txtTitle.text = "$manualName — Página ${hit.page}"
        holder.txtSnippet.text = hit.snippet.ifBlank { "(sin snippet)" }

        holder.itemView.setOnClickListener { onClick(hit) }
    }

    fun submitList(newItems: List<RAGIndex.Hit>) {
        items = newItems
        notifyDataSetChanged()
    }
}
