package com.cabgon.blackhawk.ui.admin.enruta

import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.cabgon.blackhawk.R
import java.util.Date

class AdminEnRutaAdapter(
    private val canDelete: Boolean,
    private val onOpen: (String) -> Unit,
    private val onDelete: (AdminEnRutaItem) -> Unit
) : RecyclerView.Adapter<AdminEnRutaAdapter.VH>() {

    private var list: List<AdminEnRutaItem> = emptyList()

    fun submit(newList: List<AdminEnRutaItem>) {
        list = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_admin_enruta, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = list.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(list[position])

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val txtMat = itemView.findViewById<TextView>(R.id.txtMat)
        private val txtSub = itemView.findViewById<TextView>(R.id.txtSub)
        private val btnOpen = itemView.findViewById<Button>(R.id.btnOpen)
        private val btnDelete = itemView.findViewById<Button>(R.id.btnDelete)

        fun bind(it: AdminEnRutaItem) {
            txtMat.text = "${it.matAeronave} · Cat ${it.categoria.ifBlank { "—" }}"
            val ts = if (it.lastEditTimestamp > 0L)
                DateFormat.format("yyyy-MM-dd HH:mm", Date(it.lastEditTimestamp)).toString()
            else "—"

            txtSub.text = "Ubicación: ${it.ubicacion.ifBlank { "—" }} · Última edición: $ts"

            btnOpen.setOnClickListener { _ -> onOpen(it.matAeronave) }

            btnDelete.isVisible = canDelete
            btnDelete.setOnClickListener { _ -> onDelete(it) }
        }
    }
}
