package com.cabgon.blackhawk.ui.inspection40h

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cabgon.blackhawk.data.inspection40h.Inspection40hRepository
import com.cabgon.blackhawk.databinding.ItemPreflightChecklistBinding

class Inspection40hChecklistAdapter(
    private val onToggle: (itemId: Long, checked: Boolean) -> Unit
) : ListAdapter<Inspection40hRepository.Item, Inspection40hChecklistAdapter.VH>(Diff) {

    object Diff : DiffUtil.ItemCallback<Inspection40hRepository.Item>() {
        override fun areItemsTheSame(
            oldItem: Inspection40hRepository.Item,
            newItem: Inspection40hRepository.Item
        ): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(
            oldItem: Inspection40hRepository.Item,
            newItem: Inspection40hRepository.Item
        ): Boolean = oldItem == newItem
    }

    inner class VH(val b: ItemPreflightChecklistBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemPreflightChecklistBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val ctx = holder.itemView.context

        // Evitar que el listener viejo se dispare al reciclar
        holder.b.check.setOnCheckedChangeListener(null)

        // Texto corto visible y estado del check
        holder.b.check.text = item.shortText
        holder.b.check.isChecked = item.checked

        // Marcar / desmarcar → callback al fragment (repo actualiza BD)
        holder.b.check.setOnCheckedChangeListener { _, checked ->
            onToggle(item.id, checked)
        }

        // Click en la fila → mostrar texto largo en un diálogo
        holder.b.root.setOnClickListener {
            AlertDialog.Builder(ctx)
                .setTitle(item.code)
                .setMessage(item.longText)
                .setPositiveButton("Cerrar", null)
                .show()
        }

        // En 40H no usamos advertencias visuales de momento
        holder.b.tvWarning.isVisible = false
    }
}
