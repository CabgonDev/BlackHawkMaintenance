package com.cabgon.blackhawk.ui.adapters

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
import com.cabgon.blackhawk.data.inspection480h.Inspection480hRepository

class Inspection480hListAdapter(
    private val onClick: (String) -> Unit,
    private val onDelete: (String) -> Unit
) : ListAdapter<Inspection480hRepository.InspectionWithItems, Inspection480hListAdapter.VH>(Diff) {

    object Diff : DiffUtil.ItemCallback<Inspection480hRepository.InspectionWithItems>() {
        override fun areItemsTheSame(
            oldItem: Inspection480hRepository.InspectionWithItems,
            newItem: Inspection480hRepository.InspectionWithItems
        ) = oldItem.header.id == newItem.header.id

        override fun areContentsTheSame(
            oldItem: Inspection480hRepository.InspectionWithItems,
            newItem: Inspection480hRepository.InspectionWithItems
        ) = oldItem == newItem
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val txtTitle: TextView = view.findViewById(R.id.txtTitle)
        private val txtSubtitle: TextView = view.findViewById(R.id.txtSubtitle)
        private val txtTech: TextView = view.findViewById(R.id.txtTech)
        private val txtProgress: TextView = view.findViewById(R.id.txtProgress)
        private val btnDelete: ImageButton? = view.findViewById(R.id.btnDelete)

        fun bind(row: Inspection480hRepository.InspectionWithItems) {
            val h = row.header

            // Título: tipo + matrícula
            txtTitle.text = "Inspección 480H · ${h.matAeronave}"

            // Fecha/hora desde epoch guardado
            txtSubtitle.text = "Fecha: ${formatDateTime(h.fechaEpochMillis)}"

            // Supervisor: "Grado Especialidad Nombre"
            val gradoPart = if (h.supervisorGrade.isNotBlank()) "${h.supervisorGrade} " else ""
            val espPart = if (h.supervisorSpecialty.isNotBlank()) "${h.supervisorSpecialty} " else ""
            val supLinea = "$gradoPart$espPart${h.supervisorFullName}".trim()
            txtTech.text = "Sup.: ${supLinea.ifBlank { "—" }}"

            // % de avance (igual lógica que PreflightAdapter)
            val total = row.header.totalItems
            val done = row.header.doneItems
            val pct = if (total == 0) 0 else (done * 100 / total)
            val status = when {
                row.header.state == "closed" -> "CERRADA"
                pct == 100 && total > 0 -> "LISTA PARA CIERRE"
                done > 0 -> "EN PROCESO"
                else -> "PENDIENTE"
            }

            txtProgress.text = "Avance: $pct%  ($done / $total) · $status"

            val colorRes = when {
                row.header.state == "closed" -> R.color.inspection_success_text
                pct == 100 && total > 0 -> R.color.inspection_success_text
                done > 0 -> R.color.inspection_active_text
                else -> R.color.inspection_pending_text
            }
            txtProgress.setTextColor(
                ContextCompat.getColor(itemView.context, colorRes)
            )

            itemView.setOnClickListener { onClick(h.id) }
            btnDelete?.setOnClickListener { onDelete(h.id) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_inspection_480h, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    fun submit(rows: List<Inspection480hRepository.InspectionWithItems>) = submitList(rows)

    private fun formatDateTime(millis: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        val d = c.get(java.util.Calendar.DAY_OF_MONTH)
        val m = c.get(java.util.Calendar.MONTH) + 1
        val y = c.get(java.util.Calendar.YEAR)
        val hh = c.get(java.util.Calendar.HOUR_OF_DAY)
        val mm = c.get(java.util.Calendar.MINUTE)
        return "%02d/%02d/%04d %02d:%02d".format(d, m, y, hh, mm)
    }
}
