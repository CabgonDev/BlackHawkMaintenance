package com.cabgon.blackhawk.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cabgon.blackhawk.data.SikorskyPartHit
import com.cabgon.blackhawk.databinding.ItemManualHeaderBinding
import com.cabgon.blackhawk.databinding.ItemPartHitBinding

class SikorskyPartHitAdapter(
    private val onClick: (SikorskyPartHit) -> Unit
) : ListAdapter<SikorskyPartHitAdapter.Row, RecyclerView.ViewHolder>(RowDiff) {

    private companion object {
        private const val VIEW_TYPE_HEADER = 1
        private const val VIEW_TYPE_HIT = 2

        private val RowDiff = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(oldItem: Row, newItem: Row): Boolean =
                oldItem.stableId == newItem.stableId

            override fun areContentsTheSame(oldItem: Row, newItem: Row): Boolean =
                oldItem == newItem
        }
    }

    // Estado expand/collapse por manual
    private val expandedState = LinkedHashMap<String, Boolean>()

    // ✅ Fuente de verdad para reconstruir rows al expand/collapse
    private var lastHits: List<SikorskyPartHit> = emptyList()

    /** Filas planas para pintar en RecyclerView */
    sealed class Row {
        abstract val stableId: Long

        data class Header(
            val manualShort: String,
            val count: Int,
            val expanded: Boolean
        ) : Row() {
            override val stableId: Long = stableIdFor("H:$manualShort")
        }

        data class HitRow(
            val manualShort: String,
            val hit: SikorskyPartHit
        ) : Row() {
            override val stableId: Long =
                stableIdFor("R:$manualShort:${hit.assetPath}:${hit.page}:${hit.partNumber ?: ""}:${hit.nsn ?: ""}:${hit.fig ?: -1}")
        }
    }

    /**
     * API igual que tu adapter original:
     * recibe lista plana y la agrupa internamente.
     */
    fun submit(list: List<SikorskyPartHit>) {
        lastHits = list
        val groups = buildGroups(lastHits)
        submitList(buildRows(groups))
    }

    private data class ManualGroup(
        val manualShort: String,
        val hits: List<SikorskyPartHit>,
        val expanded: Boolean
    )

    private fun buildGroups(list: List<SikorskyPartHit>): List<ManualGroup> {
        val grouped = list.groupBy { hit ->
            hit.assetPath.substringAfterLast('/').substringBeforeLast('.')
        }

        return grouped.toSortedMap().map { (manualShort, hitsForManual) ->
            // Default: expandido (si prefieres colapsado, cambia a false)
            val expanded = expandedState[manualShort] ?: false

            val sortedHits = hitsForManual.sortedWith(
                compareBy<SikorskyPartHit> { it.page }
                    .thenBy { it.fig ?: Int.MAX_VALUE }
            )

            ManualGroup(
                manualShort = manualShort,
                hits = sortedHits,
                expanded = expanded
            )
        }
    }

    private fun buildRows(groups: List<ManualGroup>): List<Row> {
        val rows = ArrayList<Row>(groups.size * 2)
        for (g in groups) {
            rows.add(Row.Header(g.manualShort, g.hits.size, g.expanded))
            if (g.expanded) {
                for (hit in g.hits) {
                    rows.add(Row.HitRow(g.manualShort, hit))
                }
            }
        }
        return rows
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is Row.Header -> VIEW_TYPE_HEADER
            is Row.HitRow -> VIEW_TYPE_HIT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val b = ItemManualHeaderBinding.inflate(inflater, parent, false)
                HeaderVH(b) { manualShort -> toggleManual(manualShort) }
            }
            VIEW_TYPE_HIT -> {
                val b = ItemPartHitBinding.inflate(inflater, parent, false)
                HitVH(b, onClick)
            }
            else -> throw IllegalArgumentException("viewType desconocido: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Header -> (holder as HeaderVH).bind(row)
            is Row.HitRow -> (holder as HitVH).bind(row.hit, row.manualShort)
        }
    }

    private fun toggleManual(manualShort: String) {
        val current = expandedState[manualShort] ?: true
        expandedState[manualShort] = !current

        val groups = buildGroups(lastHits)
        submitList(buildRows(groups))
    }

    private class HeaderVH(
        private val b: ItemManualHeaderBinding,
        private val onHeaderClick: (String) -> Unit
    ) : RecyclerView.ViewHolder(b.root) {

        fun bind(header: Row.Header) {
            b.txtManualTitle.text = header.manualShort
            b.txtManualCount.text = "(${header.count})"
            b.txtManualArrow.text = if (header.expanded) "▼" else "▶"
            b.root.setOnClickListener { onHeaderClick(header.manualShort) }
        }
    }

    private class HitVH(
        private val b: ItemPartHitBinding,
        private val onClick: (SikorskyPartHit) -> Unit
    ) : RecyclerView.ViewHolder(b.root) {

        fun bind(h: SikorskyPartHit, manualShort: String) {
            val pn = h.partNumber ?: "-"
            val fig = h.fig?.toString() ?: "-"
            val nsn = h.nsn ?: "-"

            b.txtLine1.text = pn

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

/** Hash estable sencillo para DiffUtil IDs */
private fun stableIdFor(s: String): Long {
    var h = 1125899906842597L
    for (ch in s) h = 31L * h + ch.code
    return h
}
