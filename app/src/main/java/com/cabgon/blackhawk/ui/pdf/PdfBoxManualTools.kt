package com.cabgon.blackhawk.ui.pdf

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper

data class TocItem(
    val title: String,
    val page1: Int,     // 1-based
    val level: Int
)

data class SearchHit(
    val page1: Int,     // 1-based
    val snippet: String
)

object PdfBoxManualTools {

    fun loadDocumentFromAssets(ctx: Context, assetPath: String): PDDocument {
        ctx.assets.open(assetPath).use { input ->
            return PDDocument.load(input)
        }
    }

    fun extractToc(ctx: Context, assetPath: String): List<TocItem> {
        val doc = loadDocumentFromAssets(ctx, assetPath)
        doc.use {
            val outline: PDDocumentOutline = it.documentCatalog.documentOutline ?: return emptyList()
            val out = ArrayList<TocItem>()
            var item: PDOutlineItem? = outline.firstChild
            while (item != null) {
                walkOutline(it, item, level = 0, out = out)
                item = item.nextSibling
            }
            return out
        }
    }

    private fun walkOutline(doc: PDDocument, node: PDOutlineItem, level: Int, out: MutableList<TocItem>) {
        val title = (node.title ?: "").trim()
        val page1 = resolveOutlinePage1(doc, node)

        if (title.isNotBlank() && page1 != null) {
            out.add(TocItem(title = title, page1 = page1, level = level))
        }

        var child = node.firstChild
        while (child != null) {
            walkOutline(doc, child, level + 1, out)
            child = child.nextSibling
        }
    }

    private fun resolveOutlinePage1(doc: PDDocument, item: PDOutlineItem): Int? {
        // 1) Destination directo
        val dest = item.destination
        val pageDest = dest as? PDPageDestination
        if (pageDest != null) return (pageDest.pageNumber + 1).takeIf { it > 0 }

        // 2) Acción GoTo
        val action = item.action
        val goTo = action as? PDActionGoTo
        val adest = goTo?.destination as? PDPageDestination
        if (adest != null) return (adest.pageNumber + 1).takeIf { it > 0 }

        // 3) Si viene por Page object (algunos PDFs)
        val page = pageDest?.page ?: adest?.page
        if (page != null) {
            val index0 = doc.pages.indexOf(page)
            if (index0 >= 0) return index0 + 1
        }

        return null
    }

    fun searchText(ctx: Context, assetPath: String, query: String, maxHits: Int = 200): List<SearchHit> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val doc = loadDocumentFromAssets(ctx, assetPath)
        doc.use {
            val stripper = PDFTextStripper()
            val hits = ArrayList<SearchHit>()
            val total = it.numberOfPages

            for (i0 in 0 until total) {
                stripper.startPage = i0 + 1
                stripper.endPage = i0 + 1

                val rawText = stripper.getText(it)

                // ✅ Limpia ANTES de buscar, así el índice coincide con el texto recortado
                val clean = rawText.replace(Regex("\\s+"), " ").trim()

                val idx = clean.indexOf(q, ignoreCase = true)
                if (idx >= 0) {
                    hits.add(
                        SearchHit(
                            page1 = i0 + 1,
                            snippet = makeSnippetFromClean(clean, idx, q.length)
                        )
                    )
                    if (hits.size >= maxHits) break
                }
            }
            return hits
        }
    }

    private fun makeSnippetFromClean(clean: String, matchIndex: Int, matchLen: Int): String {
        // ✅ Más contexto = más líneas
        val before = 220  // antes era 40
        val after = 260   // antes era 60

        val start = (matchIndex - before).coerceAtLeast(0)
        val end = (matchIndex + matchLen + after).coerceAtMost(clean.length)

        val snippet = clean.substring(start, end).trim()

        return (if (start > 0) "…" else "") +
                snippet +
                (if (end < clean.length) "…" else "")
    }

}
