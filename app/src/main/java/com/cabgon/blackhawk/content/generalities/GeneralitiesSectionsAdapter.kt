package com.cabgon.blackhawk.content.generalities

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cabgon.blackhawk.databinding.ItemGeneralitySectionBinding

class GeneralitiesSectionsAdapter(
    private val onClick: (GeneralitiesSection) -> Unit
) : ListAdapter<GeneralitiesSection, GeneralitiesSectionsAdapter.VH>(Diff) {

    object Diff : DiffUtil.ItemCallback<GeneralitiesSection>() {
        override fun areItemsTheSame(oldItem: GeneralitiesSection, newItem: GeneralitiesSection): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: GeneralitiesSection, newItem: GeneralitiesSection): Boolean =
            oldItem == newItem
    }

    inner class VH(private val b: ItemGeneralitySectionBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: GeneralitiesSection) {
            b.txtTitle.text = item.title

            val tables = item.blocks.count { it is GeneralitiesTableBlock }
            val rows = item.blocks.filterIsInstance<GeneralitiesTableBlock>().sumOf { it.rows.size }
            b.txtMeta.text = "${tables} tabla(s) · ${rows} fila(s)"

            b.root.setOnClickListener { onClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemGeneralitySectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }
}
