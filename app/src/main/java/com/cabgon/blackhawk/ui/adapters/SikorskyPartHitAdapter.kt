package com.cabgon.blackhawk.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cabgon.blackhawk.data.SikorskyPartHit
import com.cabgon.blackhawk.databinding.ItemManualHeaderBinding
import com.cabgon.blackhawk.databinding.ItemPartHitBinding

class SikorskyPartHitAdapter(
    private val onClick: (SikorskyPartHit) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    // Tipos de vista: Header de manual y fila de hit
    private companion object {
        private const val VIEW_TYPE_HEADER = 1
        private const val VIEW_TYPE_HIT = 2
    }

    // Grupo por manual
    private data class ManualGroup(
        val manualKey: String,
        val manualTitle: String,
        val hits: List<SikorskyPartHit>,
        var expanded: Boolean = true
    )

    // Filas planas que se pintan en el RecyclerView
    private sealed class Row {
        data class Header(val groupIndex: Int) : Row()
        data class HitRow(val groupIndex: Int, val hit: SikorskyPartHit) : Row()
    }

    private val groups = ArrayList<ManualGroup>()
    private val rows = ArrayList<Row>()

    /**
     * Recibe la lista de hits y la agrupa por manual.
     * El API hacia afuera no cambia: sigues llamando submit(hits).
     */
    fun submit(list: List<SikorskyPartHit>) {
        groups.clear()

        // Agrupamos por "manualShort" = nombre de archivo sin ruta ni extensión
        val grouped = list.groupBy { hit ->
            hit.assetPath.substringAfterLast('/').substringBeforeLast('.')
        }

        // Creamos los grupos ordenados por título de manual
        grouped.toSortedMap().forEach { (manualShort, hitsForManual) ->
            groups.add(
                ManualGroup(
                    manualKey = manualShort,
                    manualTitle = manualShort,
                    hits = hitsForManual.sortedWith(
                        compareBy<SikorskyPartHit> { it.page }
                            .thenBy { it.fig ?: Int.MAX_VALUE }
                    ),
                    expanded = false // por defecto todos expandidos
                )
            )
        }

        rebuildRows()
    }

    private fun rebuildRows() {
        rows.clear()
        groups.forEachIndexed { index, group ->
            // Siempre ponemos el header
            rows.add(Row.Header(index))
            // Y si está expandido, sus filas
            if (group.expanded) {
                group.hits.forEach { hit ->
                    rows.add(Row.HitRow(index, hit))
                }
            }
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (rows[position]) {
            is Row.Header -> VIEW_TYPE_HEADER
            is Row.HitRow -> VIEW_TYPE_HIT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val b = ItemManualHeaderBinding.inflate(inflater, parent, false)
                HeaderVH(b) { groupIndex ->
                    toggleGroup(groupIndex)
                }
            }
            VIEW_TYPE_HIT -> {
                val b = ItemPartHitBinding.inflate(inflater, parent, false)
                HitVH(b, onClick)
            }
            else -> throw IllegalArgumentException("viewType desconocido: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> {
                val group = groups[row.groupIndex]
                (holder as HeaderVH).bind(group, row.groupIndex)
            }
            is Row.HitRow -> {
                (holder as HitVH).bind(row.hit)
            }
        }
    }

    override fun getItemCount(): Int = rows.size

    private fun toggleGroup(groupIndex: Int) {
        if (groupIndex !in groups.indices) return
        val group = groups[groupIndex]
        group.expanded = !group.expanded
        rebuildRows()
    }

    // ViewHolder para el header de manual
    private class HeaderVH(
        private val b: ItemManualHeaderBinding,
        private val onHeaderClick: (Int) -> Unit
    ) : RecyclerView.ViewHolder(b.root) {

        fun bind(group: ManualGroup, groupIndex: Int) {
            b.txtManualTitle.text = group.manualTitle
            b.txtManualCount.text = "(${group.hits.size})"
            b.txtManualArrow.text = if (group.expanded) "▼" else "▶"

            b.root.setOnClickListener {
                onHeaderClick(groupIndex)
            }
        }
    }

    // ViewHolder para cada hit (fila clicable)
    private class HitVH(
        private val b: ItemPartHitBinding,
        private val onClick: (SikorskyPartHit) -> Unit
    ) : RecyclerView.ViewHolder(b.root) {

        fun bind(h: SikorskyPartHit) {
            val manualShort = h.assetPath.substringAfterLast('/').substringBeforeLast('.')
            val pn = h.partNumber ?: "-"
            val fig = h.fig?.toString() ?: "-"
            val nsn = h.nsn ?: "-"

            // Línea principal: solo PN
            b.txtLine1.text = pn

            // Línea secundaria: Página, NSN, FIG, manual
            b.txtLine2.text = buildString {
                append("Pág ${h.page}")
                if (nsn.isNotBlank() && nsn != "-") {
                    append("  |  NSN $nsn")
                }
                append("  |  FIG $fig")
                append("  |  $manualShort")
            }

            b.root.setOnClickListener { onClick(h) }
        }
    }
}
