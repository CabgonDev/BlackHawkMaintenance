package com.cabgon.blackhawk.ui.fragments

import android.content.Intent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cabgon.blackhawk.data.RAGIndex
import com.cabgon.blackhawk.databinding.ItemAiResultBinding

class ResultsAdapter(
    private val titlePrefix: String = "Causa",   // o "Resultado" en modo concise
    private val proMode: Boolean = true          // PRO: texto más largo
) : ListAdapter<ResultsAdapter.Row, ResultsAdapter.VH>(Diff) {

    data class Row(val index1Based: Int, val hit: RAGIndex.Hit)

    object Diff : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(old: Row, new: Row): Boolean =
            old.index1Based == new.index1Based &&
                    old.hit.manual == new.hit.manual &&
                    old.hit.page == new.hit.page

        override fun areContentsTheSame(old: Row, new: Row): Boolean = old == new
    }

    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long {
        val r = getItem(position)
        return (r.index1Based.toString() + "|" + r.hit.manual + "|" + r.hit.page)
            .hashCode().toLong()
    }

    inner class VH(val b: ItemAiResultBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inf = LayoutInflater.from(parent.context)
        val vh = VH(ItemAiResultBinding.inflate(inf, parent, false))
        // Evita que este item pida foco y provoque scroll
        vh.b.root.isFocusable = false
        vh.b.root.isFocusableInTouchMode = false
        vh.b.btnOpen.isFocusable = false
        vh.b.btnOpen.isFocusableInTouchMode = false
        return vh
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val row = getItem(pos)
        val hit = row.hit
        val page = hit.page.coerceAtLeast(1)
        val manualName = basenameNoExt(hit.manual)

        h.b.tvTitle.text = "$titlePrefix ${row.index1Based}"

        val clean = hit.snippet.replace("[","").replace("]","")
            .replace(Regex("\\s+"), " ").trim()
            .ifBlank { "Referencia relevante encontrada." }

        val maxLen = if (proMode) 550 else 220
        h.b.tvSnippet.text = trimSmart(clean, maxLen)
        h.b.tvSource.text = "$manualName, pág. $page"

        h.b.btnOpen.setOnClickListener {
            runCatching {
                val ctx = it.context
                ctx.startActivity(
                    Intent(ctx, com.cabgon.blackhawk.ui.pdf.PdfViewerActivity::class.java)
                        .putExtra("assetPath", hit.manual)
                        .putExtra("page", page)
                )
            }
        }
    }

    fun mapHits(hits: List<RAGIndex.Hit>, startIndex1Based: Int = 1): List<Row> =
        hits.mapIndexed { i, h -> Row(startIndex1Based + i, h) }

    private fun basenameNoExt(path: String): String {
        val base = path.substringAfterLast('/').substringAfterLast('\\')
        val dot = base.lastIndexOf('.')
        return if (dot > 0) base.substring(0, dot) else base
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
