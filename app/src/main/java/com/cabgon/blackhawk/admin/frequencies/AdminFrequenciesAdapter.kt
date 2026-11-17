package com.cabgon.blackhawk.admin.frequencies

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AdminFrequenciesAdapter(
    private val onEdit: (AdminFrequencyDoc) -> Unit,
    private val onDeleteOrTombstone: (AdminFrequencyDoc) -> Unit,
    private val onRestore: (AdminFrequencyDoc) -> Unit
) : RecyclerView.Adapter<AdminFrequenciesAdapter.VH>() {

    private var list: List<AdminFrequencyDoc> = emptyList()
    private var query: String = ""

    fun submit(newList: List<AdminFrequencyDoc>) {
        list = newList
        notifyDataSetChanged()
    }

    fun setFilter(q: String) {
        query = q.trim().lowercase()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = filtered().size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(filtered()[position])
    }

    private fun filtered(): List<AdminFrequencyDoc> {
        if (query.isBlank()) return list
        return list.filter {
            val s = buildString {
                append(it.state).append(" ")
                append(it.city).append(" ")
                append(it.airportName).append(" ")
                append(it.icao).append(" ")
                append(it.iata ?: "").append(" ")
                append(it.type).append(" ")
                append(it.callsign ?: "").append(" ")
                append(it.ident ?: "")
            }.lowercase()
            s.contains(query)
        }
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val t1 = itemView.findViewById<TextView>(android.R.id.text1)
        private val t2 = itemView.findViewById<TextView>(android.R.id.text2)

        fun bind(doc: AdminFrequencyDoc) {
            val freq = when {
                (doc.freqMHz ?: 0.0) > 0.0 -> "${doc.freqMHz} MHz"
                (doc.freqKhz ?: 0.0) > 0.0 -> "${doc.freqKhz} kHz"
                else -> "—"
            }

            val delTag = if (doc.isDeleted) " [TOMBSTONE]" else ""
            t1.text = "${doc.icao} · ${doc.type}$delTag"
            t2.text = "${doc.state} / ${doc.city} · $freq"

            itemView.setOnClickListener {
                if (!doc.isDeleted) onEdit(doc)
            }

            itemView.setOnLongClickListener {
                val ctx = itemView.context
                val opts = if (doc.isDeleted) {
                    arrayOf("Restaurar (undo)")
                } else {
                    arrayOf("Marcar para eliminar (tombstone)", "Editar")
                }

                AlertDialog.Builder(ctx)
                    .setTitle("Acciones")
                    .setItems(opts) { _, which ->
                        if (doc.isDeleted) {
                            onRestore(doc)
                        } else {
                            when (which) {
                                0 -> onDeleteOrTombstone(doc)
                                1 -> onEdit(doc)
                            }
                        }
                    }
                    .show()

                true
            }

            // Indicador visual sencillo
            itemView.alpha = if (doc.isDeleted) 0.5f else 1.0f
        }
    }
}
