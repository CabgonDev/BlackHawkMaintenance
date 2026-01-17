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
import com.cabgon.blackhawk.data.inspection40h.Inspection40hRepository

class Inspection40hListAdapter(
    private val onClick: (Long) -> Unit,
    private val onDelete: (Long) -> Unit
) : ListAdapter<Inspection40hRepository.InspectionWithItems, Inspection40hListAdapter.VH>(Diff) {

    object Diff : DiffUtil.ItemCallback<Inspection40hRepository.InspectionWithItems>() {
        override fun areItemsTheSame(
            oldItem: Inspection40hRepository.InspectionWithItems,
            newItem: Inspection40hRepository.InspectionWithItems
        ) = oldItem.header.id == newItem.header.id

        override fun areContentsTheSame(
            oldItem: Inspection40hRepository.InspectionWithItems,
            newItem: Inspection40hRepository.InspectionWithItems
        ) = oldItem == newItem
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val txtTitle: TextView = view.findViewById(R.id.txtTitle)
        private val txtSubtitle: TextView = view.findViewById(R.id.txtSubtitle)
        private val txtTech: TextView = view.findViewById(R.id.txtTech)
        private val txtProgress: TextView = view.findViewById(R.id.txtProgress)
        private val btnDelete: ImageButton? = view.findViewById(R.id.btnDelete)

        fun bind(row: Inspection40hRepository.InspectionWithItems) {
            val h = row.header

            // Título: tipo + matrícula
            txtTitle.text = "Inspección 40H · ${h.matAeronave}"

            // Fecha/hora desde epoch guardado
            txtSubtitle.text = "Fecha: ${formatDateTime(h.fechaEpochMillis)}"

            // Supervisor: "Grado Especialidad Nombre"
            val gradoPart = if (h.supervisorGrade.isNotBlank()) "${h.supervisorGrade} " else ""
            val espPart = if (h.supervisorSpecialty.isNotBlank()) "${h.supervisorSpecialty} " else ""
            val supLinea = "$gradoPart$espPart${h.supervisorFullName}".trim()
            txtTech.text = "Sup.: ${supLinea.ifBlank { "—" }}"

            // % de avance (igual lógica que PreflightAdapter)
            val total = row.items.size
            val done = row.items.count { it.checked }
            val pct = if (total == 0) 0 else (done * 100 / total)
            val status = if (pct == 100) "COMPLETA" else "EN PROCESO"

            txtProgress.text = "Avance: $pct%  ($done / $total) · $status"

            val colorRes = if (pct == 100) R.color.green else R.color.red
            txtProgress.setTextColor(
                ContextCompat.getColor(itemView.context, colorRes)
            )

            itemView.setOnClickListener { onClick(h.id) }
            btnDelete?.setOnClickListener { onDelete(h.id) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_inspection_40h, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    fun submit(rows: List<Inspection40hRepository.InspectionWithItems>) = submitList(rows)

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
