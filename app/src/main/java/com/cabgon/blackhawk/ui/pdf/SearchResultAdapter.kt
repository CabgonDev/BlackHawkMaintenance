package com.cabgon.blackhawk.ui.pdf

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.cabgon.blackhawk.R

class SearchResultAdapter(
    private var items: List<SearchHit>,
    private val onClick: (SearchHit) -> Unit
) : RecyclerView.Adapter<SearchResultAdapter.VH>() {

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
        val model = items[position]
        holder.page.text = "Página ${model.page1}"
        holder.snippet.text = model.snippet
        holder.snippet.maxLines = 10
        holder.snippet.isSingleLine = false


        holder.itemView.setOnClickListener {
            onClick(model)
        }
    }


    fun submitList(newItems: List<SearchHit>) {
        items = newItems
        notifyDataSetChanged()
    }
}
