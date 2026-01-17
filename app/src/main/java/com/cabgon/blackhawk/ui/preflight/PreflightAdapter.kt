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
        private val btnDelete: ImageButton? = view.findViewById(R.id.btnDelete)

        fun bind(row: PreflightRepository.InspectionWithItems) {
            val h = row.header

            txtTitle.text = "Inspección Pre-Vuelo · ${h.matAeronave}"
            txtSubtitle.text = "Fecha: ${formatDate(h.fechaEpochMillis)}  ${h.hora24}"

            val gradoPart = if (h.tecnicoGrado.isNotBlank()) "${h.tecnicoGrado} " else ""
            val espPart = if (h.tecnicoEspecialidad.isNotBlank()) "${h.tecnicoEspecialidad} " else ""
            txtTech.text = "Téc.: $gradoPart$espPart${h.tecnicoNombre} "

// 👉 Porcentaje de avance + estado
            val total = row.items.size
            val done = row.items.count { it.checked }
            val pct = if (total == 0) 0 else (done * 100 / total)

            val status = if (pct == 100) "COMPLETA" else "EN PROCESO"
            txtProgress.text = "Avance: $pct%  ($done / $total) · $status"

// Color según estado (usa colors.xml: red / green)
            val colorRes = if (pct == 100) R.color.green else R.color.red
            txtProgress.setTextColor(
                ContextCompat.getColor(itemView.context, colorRes)
            )


            itemView.setOnClickListener { onClick(h.id) }
            btnDelete?.setOnClickListener { onDelete(h.id) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_preflight, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    // Conveniencia: el fragment te pasa la lista tal cual viene del repositorio
    fun submit(rows: List<PreflightRepository.InspectionWithItems>) = submitList(rows)

    private fun formatDate(millis: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        val d = c.get(java.util.Calendar.DAY_OF_MONTH)
        val m = c.get(java.util.Calendar.MONTH) + 1
        val y = c.get(java.util.Calendar.YEAR)
        return "%02d/%02d/%04d".format(d, m, y)
    }
}
