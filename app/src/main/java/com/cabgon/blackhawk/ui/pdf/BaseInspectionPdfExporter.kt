package com.cabgon.blackhawk.ui.pdf

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.DrawableRes
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object BaseInspectionPdfExporter {

    data class InspectionHeader(
        val title: String,
        val fecha: String,              // dd/MM/yyyy
        val hora24: String,             // HH:mm
        val matAeronave: String,
        val supervisorGrado: String,
        val supervisorEspecialidad: String,
        val supervisorNombre: String,
        val supervisorMatricula: String?,
        val hsTotales: String?
    )

    data class Column(
        val title: String,
        val x: Float                    // posición X de la columna
    )

    data class TableRow(
        val index: Int,
        val cells: List<String>,        // tamaño debe coincidir con columns.size
        val checked: Boolean,
        val participantLabel: String?   // para bloque PARTICIPANTES
    )

    data class TableConfig(
        val columns: List<Column>,
        val descriptionColumnIndex: Int = 1,   // columna donde va el texto largo
        val showParticipantsBlock: Boolean = true,
        val signatureLabel: String = "Firma del supervisor",
        val subfolderName: String = "GENERIC"  // subcarpeta en /Documentos/Inspecciones/
    )

    fun exportInspection(
        context: Context,
        fileNameHint: String,
        header: InspectionHeader,
        rows: List<TableRow>,
        tableConfig: TableConfig,
        @DrawableRes watermarkResId: Int? = null,
        watermarkAlpha: Int = 60,
        @DrawableRes headerLogoResId: Int? = null,
        signatureBitmap: Bitmap? = null
    ): Uri? {
        val pageW = 595
        val pageH = 842
        val margin = 36

        val doc = PdfDocument()
        val headerLogo = headerLogoResId?.let { safeDecode(context, it) }
        val watermark = watermarkResId?.let { safeDecode(context, it) }

        // -------- Paints ----------
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            textSize = 18f
            color = Color.BLACK
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f
            color = Color.DKGRAY
        }
        val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 9f
            color = Color.BLACK
        }
        val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 8.5f
            color = Color.DKGRAY
        }
        val tableHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            textSize = 9.5f
            color = Color.BLACK
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.LTGRAY
            strokeWidth = 0.7f
        }

        // ---------- Helpers de dibujo ----------

        fun Canvas.drawWatermark() {
            watermark?.let { bmp ->
                val size = (pageW * 0.60f).toInt()
                val cx = pageW / 2
                val cy = pageH / 2
                val left = cx - size / 2
                val top = cy - size / 2
                val attenuated = (watermarkAlpha * 0.65f).toInt().coerceIn(0, 255)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = attenuated }
                drawBitmap(bmp, null, Rect(left, top, left + size, top + size), paint)
            }
        }

        fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
            if (text.isBlank()) return emptyList()
            if (paint.measureText(text) <= maxWidth) return listOf(text)

            val words = text.split(" ")
            val lines = mutableListOf<String>()
            val current = StringBuilder()

            for (word in words) {
                if (word.isBlank()) continue
                val candidate = if (current.isEmpty()) word else current.toString() + " " + word
                if (paint.measureText(candidate) <= maxWidth) {
                    current.clear()
                    current.append(candidate)
                } else {
                    if (current.isNotEmpty()) lines += current.toString()
                    current.clear()
                    current.append(word)
                }
            }
            if (current.isNotEmpty()) lines += current.toString()
            return lines
        }

        fun Canvas.drawHeader(yStart: Int): Int {
            var y = yStart

            // Logo a la izquierda
            headerLogo?.let { bmp ->
                val maxH = 80
                val ratio = bmp.width.toFloat() / bmp.height
                val w = (maxH * ratio).toInt().coerceAtMost(130)
                val h = maxH
                drawBitmap(bmp, null, Rect(margin, y, margin + w, y + h), null)
            }

            val xLeft = margin + (headerLogo?.let { 140 } ?: 0)
            val xRight = pageW - margin - 200
            val centerX = pageW / 2f
            val titleWidth = titlePaint.measureText(header.title)

            // Título centrado
            drawText(
                header.title,
                centerX - (titleWidth / 2f),
                (y + 20).toFloat(),
                titlePaint
            )

            // Subtítulo tipo institucional
            val subtitle = "UH-60L · Escuadrón Aéreo 113 · F.A.M."
            val subWidth = subPaint.measureText(subtitle)
            drawText(
                subtitle,
                centerX - (subWidth / 2f),
                (y + 36).toFloat(),
                subPaint
            )

            y += 50

            // Normalizar strings
            val fechaStr = header.fecha.ifBlank { "--/--/----" }
            val horaStr = header.hora24.ifBlank { "--:--" }
            val hsTotStr = header.hsTotales?.takeIf { it.isNotBlank() } ?: "—"
            val supGrado = header.supervisorGrado.ifBlank { "—" }
            val supEsp = header.supervisorEspecialidad.ifBlank { "—" }
            val supNom = header.supervisorNombre.ifBlank { "—" }
            val supMat = header.supervisorMatricula?.takeIf { it.isNotBlank() } ?: "—"

            // Fila 1: Fecha | Hora
            drawText("Fecha: $fechaStr", xLeft.toFloat(), y.toFloat(), subPaint)
            drawText("Hora: $horaStr", xRight.toFloat(), y.toFloat(), subPaint)
            y += 16

            // Fila 2: Aeronave | Hs Totales
            drawText("Aeronave: ${header.matAeronave}", xLeft.toFloat(), y.toFloat(), subPaint)
            drawText("Hs Totales: $hsTotStr", xRight.toFloat(), y.toFloat(), subPaint)
            y += 16

            // Fila 3: Supervisor
            drawText("Supervisor: $supGrado $supEsp $supNom", xLeft.toFloat(), y.toFloat(), subPaint)
            y += 16

            // Fila 4: Matrícula
            drawText("Matrícula supervisor: $supMat", xLeft.toFloat(), y.toFloat(), subPaint)
            y += 16

            // Separador
            val sepY = y + 8
            drawLine(
                margin.toFloat(),
                sepY.toFloat(),
                (pageW - margin).toFloat(),
                sepY.toFloat(),
                linePaint
            )
            return sepY + 12
        }

        fun Canvas.drawTableHeader(yTop: Int): Int {
            val yBaseline = yTop.toFloat()
            val fm = tableHeaderPaint.fontMetrics

            tableConfig.columns.forEach { col ->
                drawText(col.title, col.x, yBaseline, tableHeaderPaint)
            }

            val lineY = yBaseline + fm.descent + 2f

            drawLine(
                margin.toFloat(),
                lineY,
                (pageW - margin).toFloat(),
                lineY,
                linePaint
            )

            return (lineY + 6f).toInt()
        }

        fun Canvas.drawParticipantsBlock(startY: Int, participants: List<String>): Int {
            if (!tableConfig.showParticipantsBlock || participants.isEmpty()) return startY

            var yPos = startY

            drawLine(
                margin.toFloat(),
                (yPos - 6).toFloat(),
                (pageW - margin).toFloat(),
                (yPos - 6).toFloat(),
                linePaint
            )

            val title = "PARTICIPANTES"
            drawText(
                title,
                margin.toFloat(),
                yPos.toFloat(),
                tableHeaderPaint
            )
            yPos += 14

            participants.forEach { name ->
                val line = "• $name"
                drawText(
                    line,
                    (margin + 8).toFloat(),
                    yPos.toFloat(),
                    normalPaint
                )
                yPos += 12
            }

            return yPos + 4
        }

        fun Canvas.drawFooter(signature: Bitmap?, supervisorMatricula: String?) {
            val lineW = 175

            val lineRight = pageW - margin
            val lineLeft = lineRight - lineW
            val signH = 70

            var y = pageH - margin - signH - 40

            signature?.let { sig ->
                val ratio = sig.width.toFloat() / sig.height.toFloat()
                val dstH = signH
                val dstW = (dstH * ratio).toInt().coerceAtMost(lineW)

                val sigLeft = lineLeft + ((lineW - dstW) / 2)
                val sigTop = y

                drawBitmap(
                    sig,
                    null,
                    Rect(sigLeft, sigTop, sigLeft + dstW, sigTop + dstH),
                    Paint(Paint.ANTI_ALIAS_FLAG)
                )
            }

            y += signH + 8

            drawLine(
                lineLeft.toFloat(),
                y.toFloat(),
                lineRight.toFloat(),
                y.toFloat(),
                linePaint
            )
            y += 12

            supervisorMatricula
                ?.takeIf { it.isNotBlank() }
                ?.let { mat ->
                    val textWidth = smallPaint.measureText(mat)
                    val textX = lineLeft + (lineW - textWidth) / 2f
                    drawText(mat, textX, y.toFloat(), smallPaint)
                    y += 14
                }

            val label = tableConfig.signatureLabel.ifBlank { "Firma del supervisor" }
            val labelWidth = smallPaint.measureText(label)
            val labelX = lineLeft + (lineW - labelWidth) / 2f
            drawText(label, labelX, y.toFloat(), smallPaint)
            y += 14

            val manualText = "TM 1-1520-237-10 rev4"
            val manualPaint = Paint(smallPaint).apply {
                color = Color.RED
            }
            drawText(
                manualText,
                margin.toFloat(),
                (y + 10).toFloat(),
                manualPaint
            )
        }

        // ---------- Paginado ----------
        var pageNum = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
        var canvas = page.canvas
        canvas.drawWatermark()
        var y = canvas.drawHeader(margin)
        y = canvas.drawTableHeader(y + 4)

        val contentBottom = pageH - margin - 80

        fun newPage() {
            doc.finishPage(page)
            pageNum++
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
            canvas = page.canvas
            canvas.drawWatermark()
            y = canvas.drawHeader(margin)
            y = canvas.drawTableHeader(y + 4)
        }

        // ---------- Renglones (tabla) ----------
        val fm = normalPaint.fontMetrics
        val textHeight = fm.descent - fm.ascent
        val rowPaddingTop = 2f
        val rowPaddingBottom = 2f
        val lineSpacing = 1f
        val lineStep = textHeight + lineSpacing

        val columns = tableConfig.columns
        val descColIdx = tableConfig.descriptionColumnIndex
            .coerceIn(0, columns.lastIndex)

        for (row in rows) {
            val colXs = columns.map { it.x }

            val descX = colXs[descColIdx]
            val nextColX = if (descColIdx < colXs.lastIndex) colXs[descColIdx + 1] else (pageW - margin).toFloat()
            val maxDescWidth = nextColX - descX - 4f

            val rawDescText = row.cells.getOrNull(descColIdx).orEmpty()
            val descLines = wrapText(rawDescText, normalPaint, maxDescWidth)
            val lineCount = descLines.size.coerceAtLeast(1)

            val rowHeight = rowPaddingTop + rowPaddingBottom +
                    (lineCount - 1) * lineStep + textHeight

            if (y + rowHeight + 6 > contentBottom) {
                canvas.drawFooter(signatureBitmap, header.supervisorMatricula)
                newPage()
            }

            val rowTop = y.toFloat()
            val firstBaseline = rowTop + rowPaddingTop - fm.ascent

            // columnas simples (no descripción)
            columns.forEachIndexed { idx, col ->
                if (idx == descColIdx) return@forEachIndexed

                val value = row.cells.getOrNull(idx).orEmpty()
                val paint = if (idx == columns.lastIndex) smallPaint else normalPaint
                canvas.drawText(value, col.x, firstBaseline, paint)
            }

            // descripción multilínea
            var descBaseline = firstBaseline
            descLines.forEachIndexed { i, line ->
                if (i == 0) {
                    canvas.drawText(line, descX, descBaseline, normalPaint)
                } else {
                    descBaseline += lineStep
                    canvas.drawText(line, descX, descBaseline, normalPaint)
                }
            }

            val lastBaseline = if (descLines.isEmpty()) firstBaseline else descBaseline
            val rowBottom = lastBaseline + fm.descent + rowPaddingBottom

            canvas.drawLine(
                margin.toFloat(),
                rowBottom,
                (pageW - margin).toFloat(),
                rowBottom,
                linePaint
            )

            y = (rowBottom + 2f).toInt()
        }

        // ---------- PARTICIPANTES ----------
        val participantes = rows
            .filter { it.checked && !it.participantLabel.isNullOrBlank() }
            .mapNotNull { it.participantLabel }
            .distinct()

        if (participantes.isNotEmpty()) {
            val startY = (y + 20).coerceAtMost(contentBottom)
            y = canvas.drawParticipantsBlock(startY, participantes)
        }

        canvas.drawFooter(signatureBitmap, header.supervisorMatricula)
        doc.finishPage(page)

        val safeName = sanitize("${fileNameHint.ifBlank { timeStamp() }}.pdf")
        val uri = saveToDocuments(context, tableConfig.subfolderName, safeName, doc)
        doc.close()
        return uri
    }

    fun openPdf(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Abrir con"))
    }

    // ---------- Utilidades comunes ----------

    private fun safeDecode(ctx: Context, @DrawableRes resId: Int): Bitmap? =
        try { BitmapFactory.decodeResource(ctx.resources, resId) } catch (_: Throwable) { null }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9._-]+"), "_")

    private fun timeStamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun saveToDocuments(
        context: Context,
        subfolder: String,
        fileName: String,
        doc: PdfDocument
    ): Uri? =
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val relativePath = Environment.DIRECTORY_DOCUMENTS + "/Inspecciones/$subfolder"
                val values = ContentValues().apply {
                    put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.Files.FileColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.Files.FileColumns.RELATIVE_PATH, relativePath)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(
                    MediaStore.Files.getContentUri("external"),
                    values
                ) ?: return null
                resolver.openOutputStream(uri)?.use { os -> doc.writeTo(os) }
                uri
            } else {
                val docsDir =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                val targetDir = File(docsDir, "Inspecciones/$subfolder")
                if (!targetDir.exists()) targetDir.mkdirs()
                val file = File(targetDir, fileName)
                FileOutputStream(file).use { fos -> doc.writeTo(fos) }
                Uri.fromFile(file)
            }
        } catch (_: Throwable) { null }
}
