package com.cabgon.blackhawk.ui.pdf

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.util.Locale
import kotlin.math.roundToInt

object PdfSearchHelper {

    data class Quad(
        val left: Float,
        val right: Float,
        val bottom: Float,
        val top: Float,
        val pageWidth: Float,
        val pageHeight: Float
    ) {
        val heightAvg: Float get() = (top - bottom).coerceAtLeast(0f)
    }

    fun findMatchesBoxesInPageFromAssets(
        context: Context,
        assetPath: String,
        page1Based: Int,
        query: String
    ): List<Quad> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val qKey = toKey(q)
        if (qKey.isBlank()) return emptyList()

        context.assets.open(assetPath).use { input ->
            val doc = PDDocument.load(input)
            doc.use {
                val pageIndex0 = (page1Based - 1).coerceIn(0, it.numberOfPages - 1)
                val page = it.getPage(pageIndex0)

                // Box de referencia: normalmente CropBox coincide mejor con el render del viewer
                val box: PDRectangle =
                    page.cropBox
                        ?: page.mediaBox
                        ?: PDRectangle(0f, 0f, 612f, 792f)

                val rotation = ((page.rotation % 360) + 360) % 360

                // Colecta posiciones con getText(doc) (contexto completo)
                val collector = PositionCollector(pageIndex0 + 1)
                collector.getText(doc)
                val positions = collector.positions
                if (positions.isEmpty()) return emptyList()

                // Stream "key" (solo alfanum) con mapping a TextPosition
                val keyChars = StringBuilder(positions.size)
                val keyToTp = ArrayList<TextPosition>(positions.size)

                for (tp in positions) {
                    val u = tp.unicode ?: continue
                    if (u.isEmpty()) continue
                    val up = normalizeHyphens(u).uppercase(Locale.US)
                    for (ch in up) {
                        if (ch.isLetterOrDigit()) {
                            keyChars.append(ch)
                            keyToTp.add(tp)
                        }
                    }
                }

                val hayKey = keyChars.toString()
                if (hayKey.isBlank()) return emptyList()

                val out = ArrayList<Quad>()
                var from = 0
                while (true) {
                    val idx = hayKey.indexOf(qKey, startIndex = from)
                    if (idx < 0) break

                    val endIdx = (idx + qKey.length - 1).coerceAtMost(keyToTp.lastIndex)
                    val range = keyToTp.subList(idx, endIdx + 1)

                    val rect = rectFromPositions(range, box, rotation)
                    if (rect != null) out.add(rect)

                    from = idx + qKey.length
                    if (out.size >= 80) break
                }

                // Dedup: evita “doble highlight” cuando varios chars mapean al mismo TextPosition
                return dedupe(out)
            }
        }
    }

    private fun rectFromPositions(
        positions: List<TextPosition>,
        box: PDRectangle,
        rotation: Int
    ): Quad? {
        if (positions.isEmpty()) return null

        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY

        for (tp in positions) {
            val x = tp.xDirAdj
            val y = tp.yDirAdj
            val w = tp.widthDirAdj
            val h = tp.heightDir

            minX = minOf(minX, x)
            maxX = maxOf(maxX, x + w)
            minY = minOf(minY, y)
            maxY = maxOf(maxY, y + h)
        }

        if (!minX.isFinite() || !minY.isFinite() || !maxX.isFinite() || !maxY.isFinite()) return null

        val pageW = box.width
        val pageH = box.height

        // Offset del box (si CropBox no inicia en 0,0)
        val offX = box.lowerLeftX
        val offY = box.lowerLeftY

        // Coordenadas base (en sistema PDF) ajustadas al origen del box
        var left = minX - offX
        var right = maxX - offX
        var bottom = minY - offY
        var top = maxY - offY

        // Rotación básica (por si el PDF la trae)
        if (rotation == 90) {
            val nl = bottom
            val nr = top
            val nb = pageW - right
            val nt = pageW - left
            left = nl; right = nr; bottom = nb; top = nt
        } else if (rotation == 180) {
            val nl = pageW - right
            val nr = pageW - left
            val nb = pageH - top
            val nt = pageH - bottom
            left = nl; right = nr; bottom = nb; top = nt
        } else if (rotation == 270) {
            val nl = pageH - top
            val nr = pageH - bottom
            val nb = left
            val nt = right
            left = nl; right = nr; bottom = nb; top = nt
        }

        // Clamp
        left = left.coerceIn(0f, pageW)
        right = right.coerceIn(0f, pageW)
        bottom = bottom.coerceIn(0f, pageH)
        top = top.coerceIn(0f, pageH)

        if (right <= left || top <= bottom) return null

        return Quad(left, right, bottom, top, pageW, pageH)
    }

    private fun normalizeHyphens(s: String): String {
        return s
            .replace('\u2010', '-')
            .replace('\u2011', '-')
            .replace('\u2012', '-')
            .replace('\u2013', '-')
            .replace('\u2014', '-')
            .replace('\u2212', '-')
            .replace('\u00A0', ' ')
    }

    private fun toKey(s: String): String {
        val up = normalizeHyphens(s).uppercase(Locale.US)
        val sb = StringBuilder(up.length)
        for (c in up) if (c.isLetterOrDigit()) sb.append(c)
        return sb.toString()
    }

    private fun dedupe(list: List<Quad>): List<Quad> {
        val seen = HashSet<String>(list.size)
        val out = ArrayList<Quad>(list.size)

        for (q in list) {
            val k = listOf(q.left, q.right, q.bottom, q.top)
                .joinToString("|") { (it * 10f).roundToInt().toString() }
            if (seen.add(k)) out.add(q)
        }
        return out
    }

    private class PositionCollector(page1: Int) : PDFTextStripper() {
        val positions = ArrayList<TextPosition>(8192)

        init {
            sortByPosition = true
            startPage = page1
            endPage = page1
        }

        override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
            if (textPositions == null) return
            positions.addAll(textPositions)
        }
    }
}
