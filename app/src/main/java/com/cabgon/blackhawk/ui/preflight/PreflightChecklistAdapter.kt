package com.cabgon.blackhawk.ui.preflight

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.data.preflight.ChecklistItem
import com.cabgon.blackhawk.data.preflight.PreflightRepository
import com.cabgon.blackhawk.databinding.ItemPreflightChecklistBinding

// Alias para no pelear con otros "Item"
typealias RepoItem = PreflightRepository.Item

class PreflightChecklistAdapter(
    private val specByTitle: Map<String, ChecklistItem>,
    private val onToggle: (itemId: Long, checked: Boolean) -> Unit
) : ListAdapter<RepoItem, PreflightChecklistAdapter.VH>(Diff) {

    object Diff : DiffUtil.ItemCallback<RepoItem>() {
        override fun areItemsTheSame(oldItem: RepoItem, newItem: RepoItem): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: RepoItem, newItem: RepoItem): Boolean =
            oldItem == newItem
    }

    // inner era redundante: VH no usa miembros de la clase externa
    class VH(val b: ItemPreflightChecklistBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemPreflightChecklistBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val ctx = holder.itemView.context
        val card = holder.b.root

        val spec = specByTitle[item.title]
        val baseLabel = spec?.short?.takeIf { it.isNotBlank() } ?: item.title

        val isRequired = spec?.required == true
        val isWarning = spec?.warning == true
        val warningText = spec?.text

        // Label para ítems normales (sin emoji)
        val normalLabel = if (isRequired && !isWarning) {
            "$baseLabel *"
        } else {
            baseLabel
        }

        if (isWarning) {
            // --------- ESTILO WARNING AERONÁUTICO MODERNO ---------

            val label = baseLabel   // sin emoji, el ícono visual hace el trabajo

            // Fondo amarillo + borde gris
            card.setCardBackgroundColor(
                ContextCompat.getColor(ctx, R.color.preflight_warning_yellow)
            )
            card.strokeWidth = 2
            card.strokeColor = ContextCompat.getColor(ctx, R.color.preflight_warning_stroke)
            card.cardElevation = 4f

            // Mostrar ícono e "Ver"
            holder.b.imgWarning.isVisible = true
            holder.b.tvWarning.isVisible = true

            // Checkbox deshabilitado (no palomeable directo)
            holder.b.check.setOnCheckedChangeListener(null)
            holder.b.check.text = label
            holder.b.check.isChecked = item.checked
            holder.b.check.isEnabled = false
            holder.b.check.setTypeface(null, Typeface.BOLD)

            // Click en "Ver" → mostrar advertencia
            holder.b.tvWarning.setOnClickListener {
                if (!warningText.isNullOrBlank()) {
                    AlertDialog.Builder(ctx)
                        // Si viene vacío, usamos "Advertencia"
                        .setTitle(
                            spec.title.takeIf { it.isNotBlank() } ?: "Advertencia"
                        )
                        .setMessage(warningText)
                        .setPositiveButton("Enterado") { dlg, _ ->
                            dlg.dismiss()
                            // Se marca como reconocido; el usuario no podrá desmarcarlo
                            if (!item.checked) {
                                onToggle(item.id, true)
                            }
                        }
                        .show()
                }
            }

        } else {
            // --------- ÍTEM NORMAL ---------

            // Reset visual por reciclaje
            card.setCardBackgroundColor(
                ContextCompat.getColor(ctx, android.R.color.transparent)
            )
            card.strokeWidth = 0
            card.cardElevation = 2f

            holder.b.imgWarning.isVisible = false
            holder.b.tvWarning.isVisible = false
            holder.b.tvWarning.setOnClickListener(null)

            holder.b.check.setOnCheckedChangeListener(null)
            holder.b.check.text = normalLabel
            holder.b.check.isChecked = item.checked
            holder.b.check.isEnabled = true
            holder.b.check.setTypeface(
                null,
                if (isRequired) Typeface.BOLD else Typeface.NORMAL
            )

            holder.b.check.setOnCheckedChangeListener { _, checked ->
                onToggle(item.id, checked)
            }
        }
    }
}
