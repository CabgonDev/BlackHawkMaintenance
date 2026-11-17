package com.cabgon.blackhawk.ui.fragments

import android.content.Intent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cabgon.blackhawk.data.RAGIndex
import com.cabgon.blackhawk.databinding.ItemAiResultBinding
import com.cabgon.blackhawk.databinding.ItemManualHeaderBinding
import java.util.Locale

class ResultsAdapter(
    private val titlePrefix: String = "Referencia t\u00E9cnica",
    private val proMode: Boolean = false,
    private val onOpenManual: ((RAGIndex.Hit) -> Unit)? = null
) : ListAdapter<ResultsAdapter.Row, RecyclerView.ViewHolder>(Diff) {

    private companion object {
        private const val VIEW_TYPE_HEADER = 1
        private const val VIEW_TYPE_HIT = 2
    }

    sealed class Row {
        abstract val stableId: Long

        data class Header(
            val manualKey: String,
            val manualTitle: String,
            val count: Int,
            val expanded: Boolean
        ) : Row() {
            override val stableId: Long = stableIdFor("H:$manualKey")
        }

        data class HitRow(
            val index1Based: Int,
            val manualKey: String,
            val hit: RAGIndex.Hit
        ) : Row() {
            override val stableId: Long = stableIdFor(
                "R:$index1Based:$manualKey:${hit.manual}:${hit.page}:${hit.score}"
            )
        }
    }

    private data class ManualGroup(
        val manualKey: String,
        val manualTitle: String,
        val hits: List<Row.HitRow>,
        val expanded: Boolean,
        val firstResultIndex: Int
    )

    private val expandedState = LinkedHashMap<String, Boolean>()
    private var lastHitRows: List<Row.HitRow> = emptyList()

    object Diff : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(old: Row, new: Row): Boolean = old.stableId == new.stableId
        override fun areContentsTheSame(old: Row, new: Row): Boolean = old == new
    }

    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long = getItem(position).stableId

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is Row.Header -> VIEW_TYPE_HEADER
            is Row.HitRow -> VIEW_TYPE_HIT
        }
    }

    override fun onCurrentListChanged(previousList: MutableList<Row>, currentList: MutableList<Row>) {
        super.onCurrentListChanged(previousList, currentList)
        if (currentList.isEmpty()) {
            expandedState.clear()
            lastHitRows = emptyList()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> HeaderVH(
                ItemManualHeaderBinding.inflate(inflater, parent, false)
            ) { manualKey -> toggleManual(manualKey) }

            VIEW_TYPE_HIT -> {
                val vh = HitVH(ItemAiResultBinding.inflate(inflater, parent, false))
                // Evita que este item pida foco y provoque scroll
                vh.b.root.isFocusable = false
                vh.b.root.isFocusableInTouchMode = false
                vh.b.btnOpen.isFocusable = false
                vh.b.btnOpen.isFocusableInTouchMode = false
                vh
            }

            else -> throw IllegalArgumentException("viewType desconocido: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Header -> (holder as HeaderVH).bind(row)
            is Row.HitRow -> (holder as HitVH).bind(row)
        }
    }

    private fun toggleManual(manualKey: String) {
        val current = expandedState[manualKey] ?: false
        expandedState[manualKey] = !current
        submitList(buildRows(buildGroups(lastHitRows)))
    }

    fun mapHits(hits: List<RAGIndex.Hit>, startIndex1Based: Int = 1): List<Row> {
        val newRows = hits.mapIndexed { i, hit ->
            Row.HitRow(
                index1Based = startIndex1Based + i,
                manualKey = manualKey(hit.manual),
                hit = hit
            )
        }

        if (!isSameResultSetOrContinuation(lastHitRows, newRows)) {
            expandedState.clear()
        }

        lastHitRows = newRows
        return buildRows(buildGroups(lastHitRows))
    }

    private fun buildGroups(rows: List<Row.HitRow>): List<ManualGroup> {
        if (rows.isEmpty()) return emptyList()

        val grouped = LinkedHashMap<String, MutableList<Row.HitRow>>()
        for (row in rows) {
            grouped.getOrPut(row.manualKey) { mutableListOf() }.add(row)
        }

        return grouped.map { (key, hitsForManual) ->
            ManualGroup(
                manualKey = key,
                manualTitle = manualTitle(hitsForManual.first().hit.manual),
                hits = hitsForManual,
                expanded = expandedState[key] ?: false,
                firstResultIndex = hitsForManual.minOf { it.index1Based }
            )
        }.sortedWith(compareBy<ManualGroup> { manualSortKey(it.manualTitle) }.thenBy { it.firstResultIndex })
    }

    private fun buildRows(groups: List<ManualGroup>): List<Row> {
        val rows = ArrayList<Row>(groups.size + groups.sumOf { if (it.expanded) it.hits.size else 0 })
        for (group in groups) {
            rows += Row.Header(
                manualKey = group.manualKey,
                manualTitle = group.manualTitle,
                count = group.hits.size,
                expanded = group.expanded
            )
            if (group.expanded) rows.addAll(group.hits)
        }
        return rows
    }

    private class HeaderVH(
        private val b: ItemManualHeaderBinding,
        private val onHeaderClick: (String) -> Unit
    ) : RecyclerView.ViewHolder(b.root) {

        fun bind(header: Row.Header) {
            b.txtManualTitle.text = header.manualTitle
            b.txtManualCount.text = "(${header.count})"
            b.txtManualArrow.text = if (header.expanded) "▼" else "▶"
            b.root.setOnClickListener { onHeaderClick(header.manualKey) }
        }
    }

    inner class HitVH(val b: ItemAiResultBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(row: Row.HitRow) {
            val hit = row.hit
            val page = hit.page.coerceAtLeast(1)
            val manualName = manualTitle(hit.manual)

            b.tvTitle.text = titlePrefix

            val clean = hit.snippet.replace("[", "").replace("]", "")
                .replace(Regex("\\s+"), " ").trim()
                .ifBlank { "Referencia relevante encontrada." }

            val maxLen = if (proMode) 550 else 220
            b.tvSnippet.text = trimSmart(clean, maxLen)
            b.tvSource.text = "$manualName, pág. $page"

            b.btnOpen.setOnClickListener { view ->
                val customOpen = onOpenManual
                if (customOpen != null) {
                    customOpen(hit)
                } else {
                    runCatching {
                        val ctx = view.context
                        ctx.startActivity(
                            Intent(ctx, com.cabgon.blackhawk.ui.pdf.PdfViewerActivity::class.java)
                                .putExtra("assetPath", hit.manual)
                                .putExtra("page", page)
                        )
                    }
                }
            }
        }
    }

    private fun isSameResultSetOrContinuation(
        oldRows: List<Row.HitRow>,
        newRows: List<Row.HitRow>
    ): Boolean {
        if (oldRows.isEmpty()) return newRows.isEmpty()
        if (newRows.size < oldRows.size) return false

        for (i in oldRows.indices) {
            val old = oldRows[i].hit
            val new = newRows[i].hit
            if (old.manual != new.manual || old.page != new.page || old.snippet != new.snippet) {
                return false
            }
        }
        return true
    }

    private fun manualKey(path: String): String = manualTitle(path).lowercase(Locale.US)

    private fun manualTitle(path: String): String {
        val base = basenameNoExt(path)
        val volumeMatch = Regex(
            pattern = "^TM[_\\s-]*11[_\\s-]*70[_\\s-]*23[_\\s-]*(\\d+)$",
            option = RegexOption.IGNORE_CASE
        ).matchEntire(base)
        if (volumeMatch != null) {
            return "TM_11-70-23 Vol. ${volumeMatch.groupValues[1]}"
        }

        val spaced = base
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

        val lower = spaced.lowercase(Locale.US)
        val chapterMatch = Regex(
            pattern = "(?:chapter|chap|ch|capitulo|capítulo|cap)\\s*0*(\\d+)",
            option = RegexOption.IGNORE_CASE
        ).find(spaced)

        if (chapterMatch != null) {
            return "Capítulo ${chapterMatch.groupValues[1].toIntOrNull() ?: chapterMatch.groupValues[1]}"
        }

        return when {
            lower.contains("operator") || lower.contains("operador") || lower.contains("-10") || lower.contains(" 10") ->
                "Manual del Operador"

            lower.contains("maintenance") || lower.contains("mantenimiento") || lower.contains("-23") || lower.contains(" 23") ->
                "Manual de Mantenimiento"

            lower.contains("wiring") || lower.contains("wire") || lower.contains("diagram") || lower.contains("diagrama") ->
                "Manual de Diagramas"

            lower.contains("ipc") || lower.contains("parts catalog") || lower.contains("catalog") || lower.contains("catalogo") || lower.contains("catálogo") ->
                "Catálogo Ilustrado de Partes"

            else -> toDisplayTitle(spaced)
        }
    }

    private fun manualSortKey(title: String): String {
        val chapterNumber = Regex("Capítulo\\s+(\\d+)", RegexOption.IGNORE_CASE)
            .find(title)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        return if (chapterNumber != null) {
            "000_${chapterNumber.toString().padStart(4, '0')}"
        } else {
            "100_${title.lowercase(Locale.US)}"
        }
    }

    private fun basenameNoExt(path: String): String {
        val base = path.substringAfterLast('/').substringAfterLast('\\')
        val dot = base.lastIndexOf('.')
        return if (dot > 0) base.substring(0, dot) else base
    }

    private fun toDisplayTitle(value: String): String {
        if (value.isBlank()) return "Manual"
        return value.split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { token ->
                val upper = token.uppercase(Locale.US)
                when {
                    token.length <= 3 && token.any { it.isLetter() } -> upper
                    upper.startsWith("TM") -> upper
                    else -> token.lowercase(Locale.US).replaceFirstChar { ch ->
                        if (ch.isLowerCase()) ch.titlecase(Locale.US) else ch.toString()
                    }
                }
            }
    }

    private fun trimSmart(text: String, maxLen: Int): String {
        if (text.length <= maxLen) return text
        val windowEnd = (maxLen + 80).coerceAtMost(text.length)
        val slice = text.substring(0, windowEnd)
        val sentenceEnd = Regex("[.!?;:\n]").findAll(slice).lastOrNull()?.range?.last
        val cut = when {
            sentenceEnd != null && sentenceEnd >= maxLen / 2 -> sentenceEnd + 1
            else -> text.lastIndexOf(' ', maxLen).takeIf { it >= maxLen / 2 } ?: maxLen
        }
        val trimmed = text.substring(0, cut).trimEnd()
        return if (trimmed.length < text.length) "$trimmed…" else trimmed
    }
}

private fun stableIdFor(s: String): Long {
    var h = 1125899906842597L
    for (ch in s) h = 31L * h + ch.code
    return h
}
