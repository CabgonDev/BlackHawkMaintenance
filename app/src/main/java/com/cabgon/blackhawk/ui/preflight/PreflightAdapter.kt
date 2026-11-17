package com.cabgon.blackhawk.ui.preflight

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.data.preflight.PreflightRepository
import com.google.android.material.progressindicator.LinearProgressIndicator

class PreflightAdapter(
    private val onClick: (inspectionId: Long) -> Unit,
    private val onDelete: (inspectionId: Long) -> Unit
) : ListAdapter<PreflightRepository.InspectionWithItems, PreflightAdapter.VH>(Diff) {

    object Diff : DiffUtil.ItemCallback<PreflightRepository.InspectionWithItems>() {
        override fun areItemsTheSame(
            oldItem: PreflightRepository.InspectionWithItems,
            newItem: PreflightRepository.InspectionWithItems
        ) = oldItem.header.id == newItem.header.id

        override fun areContentsTheSame(
            oldItem: PreflightRepository.InspectionWithItems,
            newItem: PreflightRepository.InspectionWithItems
        ) = oldItem == newItem
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val txtTitle: TextView = view.findViewById(R.id.txtTitle)
        private val txtSubtitle: TextView = view.findViewById(R.id.txtSubtitle)
        private val txtTech: TextView = view.findViewById(R.id.txtTech)
        private val txtProgress: TextView = view.findViewById(R.id.txtProgress)
        private val tvStatusChip: TextView = view.findViewById(R.id.tvStatusChip)
        private val progressMini: LinearProgressIndicator = view.findViewById(R.id.progressMini)
        private val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)

        fun bind(row: PreflightRepository.InspectionWithItems) {
            val h = row.header

            txtTitle.text = h.matAeronave.ifBlank { "Sin matrícula" }
            txtSubtitle.text = "${formatDate(h.fechaEpochMillis)} · ${h.hora24.ifBlank { "--:--" }}"

            val tecnico = listOf(h.tecnicoGrado, h.tecnicoEspecialidad, h.tecnicoNombre)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { "Técnico sin asignar" }
            txtTech.text = tecnico

            val total = row.items.size
            val done = row.items.count { it.checked }
            val pct = if (total == 0) 0 else (done * 100 / total)

            progressMini.max = 100
            progressMini.progress = pct
            txtProgress.text = "Avance $pct% · $done / $total pasos"

            when {
                pct == 100 && total > 0 -> {
                    tvStatusChip.text = "Lista"
                    tvStatusChip.setBackgroundResource(R.drawable.bg_preflight_status_success)
                    tvStatusChip.setTextColor(ContextCompat.getColor(itemView.context, R.color.preflight_success_text))
                    txtProgress.setTextColor(ContextCompat.getColor(itemView.context, R.color.preflight_success_text))
                    progressMini.setIndicatorColor(ContextCompat.getColor(itemView.context, R.color.preflight_success_text))
                    progressMini.trackColor = ContextCompat.getColor(itemView.context, R.color.preflight_active_bg)
                }

                done > 0 -> {
                    tvStatusChip.text = "En curso"
                    tvStatusChip.setBackgroundResource(R.drawable.bg_preflight_status_active)
                    tvStatusChip.setTextColor(ContextCompat.getColor(itemView.context, R.color.preflight_active_text))
                    txtProgress.setTextColor(ContextCompat.getColor(itemView.context, R.color.preflight_active_text))
                    progressMini.setIndicatorColor(ContextCompat.getColor(itemView.context, R.color.preflight_active_text))
                    progressMini.trackColor = ContextCompat.getColor(itemView.context, R.color.preflight_pending_bg)
                }

                else -> {
                    tvStatusChip.text = "Pendiente"
                    tvStatusChip.setBackgroundResource(R.drawable.bg_preflight_status_pending)
                    tvStatusChip.setTextColor(ContextCompat.getColor(itemView.context, R.color.preflight_pending_text))
                    txtProgress.setTextColor(ContextCompat.getColor(itemView.context, R.color.preflight_text_secondary))
                    progressMini.setIndicatorColor(ContextCompat.getColor(itemView.context, R.color.preflight_command_tint))
                    progressMini.trackColor = ContextCompat.getColor(itemView.context, R.color.preflight_pending_bg)
                }
            }

            itemView.setOnClickListener { onClick(h.id) }
            btnDelete.setOnClickListener { onDelete(h.id) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_preflight, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    fun submit(rows: List<PreflightRepository.InspectionWithItems>) = submitList(rows)

    private fun formatDate(millis: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        val d = c.get(java.util.Calendar.DAY_OF_MONTH)
        val m = c.get(java.util.Calendar.MONTH) + 1
        val y = c.get(java.util.Calendar.YEAR)
        return "%02d/%02d/%04d".format(d, m, y)
    }
}
