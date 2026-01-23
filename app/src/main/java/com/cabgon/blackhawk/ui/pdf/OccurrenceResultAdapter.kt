package com.cabgon.blackhawk.ui.pdf

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.data.OccurrenceHit

class OccurrenceResultAdapter(
    private var items: List<OccurrenceHit>,
    private val onClick: (OccurrenceHit) -> Unit
) : RecyclerView.Adapter<OccurrenceResultAdapter.VH>() {

    class VH(root: android.view.View) : RecyclerView.ViewHolder(root) {
        val page: TextView = root.findViewById(R.id.txtPage)
        val snippet: TextView = root.findViewById(R.id.txtSnippet)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_search_result, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        holder.page.text = "${m.source} — Página ${m.page1}"
        holder.snippet.text = m.snippet
        holder.snippet.maxLines = 10
        holder.snippet.isSingleLine = false
        holder.itemView.setOnClickListener { onClick(m) }
    }

    fun submitList(newItems: List<OccurrenceHit>) {
        items = newItems
        notifyDataSetChanged()
    }
}
