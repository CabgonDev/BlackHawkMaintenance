package com.cabgon.blackhawk.admin.generalities

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cabgon.blackhawk.databinding.ItemAdminGeneralitySectionBinding

class AdminGeneralitiesDraftAdapter(
    private val canEdit: Boolean,
    private val onOpen: (AdminGeneralitySectionDoc) -> Unit,
    private val onToggleDeleted: (AdminGeneralitySectionDoc) -> Unit
) : ListAdapter<AdminGeneralitySectionDoc, AdminGeneralitiesDraftAdapter.VH>(Diff) {

    object Diff : DiffUtil.ItemCallback<AdminGeneralitySectionDoc>() {
        override fun areItemsTheSame(oldItem: AdminGeneralitySectionDoc, newItem: AdminGeneralitySectionDoc): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: AdminGeneralitySectionDoc, newItem: AdminGeneralitySectionDoc): Boolean =
            oldItem == newItem
    }

    inner class VH(private val b: ItemAdminGeneralitySectionBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: AdminGeneralitySectionDoc) {
            b.txtTitle.text = item.title
            b.txtMeta.text = "Order: ${item.order} · Cols: ${item.columns.size} · Filas: ${item.rows.size}"
            b.txtState.text = if (item.isDeleted) "ELIMINADA (tombstone)" else "ACTIVA"

            b.btnDelete.text = if (item.isDeleted) "Restaurar" else "Eliminar"
            b.btnDelete.isEnabled = canEdit
            b.btnDelete.alpha = if (canEdit) 1f else 0.35f

            b.root.setOnClickListener { onOpen(item) }
            b.btnDelete.setOnClickListener { onToggleDeleted(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemAdminGeneralitySectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }
}
