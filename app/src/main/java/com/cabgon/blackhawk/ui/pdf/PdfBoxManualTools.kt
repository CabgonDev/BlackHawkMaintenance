package com.cabgon.blackhawk.ui.pdf

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.util.Locale

data class TocItem(
    val title: String,
    val page1: Int,
    val level: Int
)

data class SearchHit(
    val page1: Int,
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
        val dest = item.destination
        val pageDest = dest as? PDPageDestination
        if (pageDest != null) return (pageDest.pageNumber + 1).takeIf { it > 0 }

        val action = item.action
        val goTo = action as? PDActionGoTo
        val adest = goTo?.destination as? PDPageDestination
        if (adest != null) return (adest.pageNumber + 1).takeIf { it > 0 }

        val page = pageDest?.page ?: adest?.page
        if (page != null) {
            val index0 = doc.pages.indexOf(page)
            if (index0 >= 0) return index0 + 1
        }
        return null
    }

    /**
     * Texto de UNA página (para preview).
     */
    fun getPageText(ctx: Context, assetPath: String, page1: Int): String {
        val doc = loadDocumentFromAssets(ctx, assetPath)
        doc.use {
            val total = it.numberOfPages
            val p = page1.coerceIn(1, total)
            val stripper = PDFTextStripper().apply {
                startPage = p
                endPage = p
            }
            val raw = stripper.getText(it)
            return normalizeText(raw)
        }
    }

    /**
     * Búsqueda literal simple (texto normal).
     */
    fun searchText(ctx: Context, assetPath: String, query: String, maxHits: Int = 5000): List<SearchHit> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val doc = loadDocumentFromAssets(ctx, assetPath)
        doc.use {
            val stripper = PDFTextStripper()
            val hits = ArrayList<SearchHit>(64)
            val total = it.numberOfPages

            for (i0 in 0 until total) {
                stripper.startPage = i0 + 1
                stripper.endPage = i0 + 1

                val clean = normalizeText(stripper.getText(it))

                var from = 0
                while (true) {
                    val idx = clean.indexOf(q, startIndex = from, ignoreCase = true)
                    if (idx < 0) break

                    hits.add(SearchHit(page1 = i0 + 1, snippet = makeSnippet(clean, idx, q.length)))
                    from = idx + q.length
                    if (hits.size >= maxHits) return hits
                }
            }
            return hits
        }
    }

    /**
     * ✅ Búsqueda PN/NSN SMART (tolerante a guiones/espacios/Unicode/cortes).
     *
     * Convierte página y query a "key" alfanumérica (MS35338-42 => MS3533842),
     * busca ocurrencias en el stream key, y mapea a índice aproximado en texto normalizado
     * para generar snippets coherentes.
     */
    fun searchPartNumberSmart(ctx: Context, assetPath: String, query: String, maxHits: Int = 6000): List<SearchHit> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val needleKey = toKey(q)
        if (needleKey.length < 4) return emptyList()

        val doc = loadDocumentFromAssets(ctx, assetPath)
        doc.use {
            val stripper = PDFTextStripper()
            val hits = ArrayList<SearchHit>(64)
            val total = it.numberOfPages

            for (i0 in 0 until total) {
                stripper.startPage = i0 + 1
                stripper.endPage = i0 + 1

                val pageText = normalizeText(stripper.getText(it))
                if (pageText.isBlank()) continue

                val km = KeyMap.fromText(pageText)
                val hayKey = km.key
                if (hayKey.isBlank()) continue

                var fromKey = 0
                while (true) {
                    val kidx = hayKey.indexOf(needleKey, startIndex = fromKey)
                    if (kidx < 0) break

                    val approxTextIdx = km.keyIndexToTextIndex[kidx].coerceIn(0, pageText.length)
                    hits.add(SearchHit(page1 = i0 + 1, snippet = makeSnippet(pageText, approxTextIdx, q.length)))

                    fromKey = kidx + needleKey.length
                    if (hits.size >= maxHits) return hits
                }
            }
            return hits
        }
    }

    // ----------------- Utils -----------------

    private fun normalizeText(s: String): String {
        return s
            .replace('\u00A0', ' ')
            .replace('\u2010', '-') // hyphen
            .replace('\u2011', '-') // non-breaking hyphen
            .replace('\u2012', '-') // figure dash
            .replace('\u2013', '-') // en dash
            .replace('\u2014', '-') // em dash
            .replace('\u2212', '-') // minus
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun makeSnippet(clean: String, matchIndex: Int, matchLen: Int): String {
        val before = 120
        val after = 220
        val start = (matchIndex - before).coerceAtLeast(0)
        val end = (matchIndex + matchLen + after).coerceAtMost(clean.length)
        val snippet = clean.substring(start, end).trim()
        return (if (start > 0) "…" else "") + snippet + (if (end < clean.length) "…" else "")
    }

    private fun toKey(s: String): String {
        val up = normalizeText(s).uppercase(Locale.US)
        val sb = StringBuilder(up.length)
        for (c in up) if (c.isLetterOrDigit()) sb.append(c)
        return sb.toString()
    }

    private data class KeyMap(
        val key: String,
        val keyIndexToTextIndex: IntArray
    ) {
        companion object {
            fun fromText(text: String): KeyMap {
                val up = text.uppercase(Locale.US)
                val sb = StringBuilder(up.length)
                val idxs = ArrayList<Int>(up.length)
                for (i in up.indices) {
                    val c = up[i]
                    if (c.isLetterOrDigit()) {
                        sb.append(c)
                        idxs.add(i)
                    }
                }
                val arr = IntArray(idxs.size)
                for (i in idxs.indices) arr[i] = idxs[i]
                return KeyMap(sb.toString(), arr)
            }
        }
    }
}
