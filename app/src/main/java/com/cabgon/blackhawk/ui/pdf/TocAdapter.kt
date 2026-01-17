package com.cabgon.blackhawk.ui.pdf

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.cabgon.blackhawk.R

class TocAdapter(
    private val items: List<TocItem>,
    private val onClick: (TocItem) -> Unit
) : RecyclerView.Adapter<TocAdapter.VH>() {

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_toc, parent, false) as TextView
        return VH(tv)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val model = items[position]
        val indent = "  ".repeat(model.level.coerceAtMost(6))
        holder.tv.text = "${indent}${model.title}  ·  p.${model.page1}"

        holder.tv.setOnClickListener {
            onClick(model)
        }
    }

}
