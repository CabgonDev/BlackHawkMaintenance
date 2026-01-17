// app/src/main/java/com/cabgon/blackhawk/ui/pdf/PdfSearchHelper.kt
package com.cabgon.blackhawk.ui.pdf

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlin.math.max
import kotlin.math.min

object PdfSearchHelper {

    data class Quad(
        val left: Float,
        val bottom: Float,
        val right: Float,
        val top: Float,
        val pageWidth: Float,
        val pageHeight: Float,
        val baselineAvg: Float,
        val heightAvg: Float
    )

    /**
     * Siempre regresa lista (posiblemente vacía). Nunca lanza excepción al caller.
     */
    fun findMatchesBoxesInPageFromAssets(
        context: Context,
        assetPath: String,
        page1Based: Int,
        query: String
    ): List<Quad> {
        // Query vacía → sin resultados
        if (query.isBlank()) return emptyList()

        return try {
            PDFBoxResourceLoader.init(context)

            context.assets.open(assetPath).use { input ->
                PDDocument.load(input).use { doc ->
                    if (doc.numberOfPages <= 0) return emptyList()

                    val pageIndex0 = (page1Based - 1).coerceIn(0, doc.numberOfPages - 1)

                    // Recolectar todos los glyphs de la página
                    val collector = CollectStripper()
                    collector.startPage = pageIndex0 + 1
                    collector.endPage = pageIndex0 + 1

                    // Si PDFBox falla al extraer, devolvemos vacío
                    runCatching { collector.getText(doc) }.onFailure { return emptyList() }

                    val pageW = collector.pageWidth
                    val pageH = collector.pageHeight
                    if (pageW <= 0f || pageH <= 0f || collector.seq.isEmpty()) return emptyList()

                    val normSeq = collector.seq.map { it.norm }
                    val normQuery = normalizePn(query)
                    if (normQuery.isEmpty()) return emptyList()

                    val matches = findAllMatches(normSeq, normQuery)
                    if (matches.isEmpty()) return emptyList()

                    val out = mutableListOf<Quad>()
                    for (range in matches) {
                        val from = range.first
                        val to = range.last
                        if (from < 0 || to >= collector.seq.size || from > to) continue

                        val sub = collector.seq.subList(from, to + 1)
                        if (sub.isEmpty()) continue

                        // bbox + métricas
                        var left = Float.POSITIVE_INFINITY
                        var right = Float.NEGATIVE_INFINITY
                        var top = Float.NEGATIVE_INFINITY
                        var bottom = Float.POSITIVE_INFINITY
                        var sumBaseline = 0f
                        var sumHeight = 0f
                        var n = 0

                        for (e in sub) {
                            val x0 = e.x
                            val x1 = e.x + e.w
                            val yTop = e.yTop
                            val yBottom = e.yBottom

                            left = min(left, min(x0, x1))
                            right = max(right, max(x0, x1))
                            top = max(top, max(yTop, yBottom))
                            bottom = min(bottom, min(yTop, yBottom))

                            sumBaseline += e.yBottom
                            sumHeight += e.h
                            n++
                        }

                        if (!left.isFinite() || !right.isFinite() || !top.isFinite() || !bottom.isFinite()) continue

                        out += Quad(
                            left = left,
                            bottom = bottom,
                            right = right,
                            top = top,
                            pageWidth = pageW,
                            pageHeight = pageH,
                            baselineAvg = if (n > 0) sumBaseline / n else bottom,
                            heightAvg = if (n > 0) sumHeight / n else (top - bottom)
                        )
                    }

                    out
                }
            }
        } catch (_: Throwable) {
            // Cualquier fallo (I/O, PDF corrupto, etc.) → sin resultados
            emptyList()
        }
    }

    // ==== Internals (seguros) ====

    private data class Glyph(
        val norm: Char,
        val x: Float,
        val w: Float,
        val yTop: Float,
        val yBottom: Float,
        val h: Float
    )

    private class CollectStripper : PDFTextStripper() {
        val seq = mutableListOf<Glyph>()
        var pageWidth = 0f
        var pageHeight = 0f

        override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
            if (textPositions.isNullOrEmpty()) return
            pageWidth = currentPage.mediaBox.width
            pageHeight = currentPage.mediaBox.height

            for (tp in textPositions) {
                val ch = tp.unicode?.firstOrNull() ?: ' '
                val c = normalizeChar(ch)
                if (c == '\u0000') continue

                val x0 = tp.xDirAdj
                val w = tp.widthDirAdj
                val yTop = tp.yDirAdj
                val yBottom = tp.yDirAdj - tp.heightDir
                val h = tp.heightDir

                // Evitar NaN/Inf
                if (!x0.isFinite() || !w.isFinite() || !yTop.isFinite() || !yBottom.isFinite() || !h.isFinite()) continue

                seq += Glyph(c, x0, w, yTop, yBottom, h)
            }
        }
    }

    private fun normalizePn(s: String): CharArray {
        if (s.isBlank()) return charArrayOf()
        val out = ArrayList<Char>(s.length)
        for (ch in s) {
            val n = normalizeChar(ch)
            if (n != '\u0000') out += n
        }
        return if (out.isEmpty()) charArrayOf() else out.toCharArray()
    }

    // Letras/dígitos y símbolos típicos de PN
    private fun normalizeChar(ch: Char): Char {
        val c = ch.uppercaseChar()
        return when {
            c in 'A'..'Z' -> c
            c in '0'..'9' -> c
            c == '/' || c == '-' || c == '.' || c == '_' -> c
            else -> '\u0000'
        }
    }

    // Búsqueda exacta y consecutiva (sin solapamientos)
    private fun findAllMatches(seq: List<Char>, pattern: CharArray): List<IntRange> {
        if (pattern.isEmpty() || seq.isEmpty()) return emptyList()

        val matches = mutableListOf<IntRange>()
        val n = seq.size
        val m = pattern.size

        var i = 0
        while (i <= n - m) {
            var ok = true
            var j = 0
            while (j < m) {
                if (seq[i + j] != pattern[j]) { ok = false; break }
                j++
            }
            if (ok) {
                matches += IntRange(i, i + m - 1)
                i += m // no solapar
            } else {
                i++
            }
        }
        return matches
    }
}
