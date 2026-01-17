package com.cabgon.blackhawk.ui.preflight

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
import androidx.core.graphics.withSave
import com.cabgon.blackhawk.data.preflight.PreflightChecklist
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object PdfExporter {

    interface Progress {
        fun onStart(totalSteps: Int) {}
        fun onStep(message: String, step: Int, totalSteps: Int) {}
        fun onDone() {}
    }

    /** Header COMPLETO (incluye matrícula del técnico y hora 24h). */
    data class Header(
        val title: String,
        val fecha: String,              // dd/MM/yyyy
        val hora24: String,             // HH:mm
        val matAeronave: String,        // "Mat. Aeronave" (dropdown)
        val tecnicoGrado: String,       // Grado
        val tecnicoEspecialidad: String,// Especialidad
        val tecnicoNombre: String,      // Nombre
        val hsTotales: String?,         // Hs. Totales
        val hsDisponibles: String?,     // Hs. Disp.
        val tecnicoMatricula: String?,  // Matrícula (técnico)
    )

    fun exportPreflight(
        context: Context,
        fileNameHint: String,
        header: Header,
        checklist: PreflightChecklist,
        checkedByTitle: Map<String, Boolean>,
        signatureLabel: String,
        @DrawableRes watermarkResId: Int? = null,
        watermarkAlpha: Int = 60,
        @DrawableRes headerLogoResId: Int? = null,
        signatureBitmap: Bitmap? = null,
        progress: Progress? = null,
    ): Uri? {
        // Dimensiones A4 a ~72 dpi
        val pageW = 595
        val pageH = 842
        val margin = 36

        // Reservamos espacio mínimo para pie (firma) aún en páginas intermedias
        val footerMinHeight = 120
        val contentBottom = pageH - margin - footerMinHeight

        val itemsCount = checklist.sections.sumOf { it.items.size }
        val totalSteps = 1 + 1 + itemsCount.coerceAtLeast(1) + 1
        var step = 0
        fun ping(msg: String) {
            step++
            progress?.onStep(msg, step, totalSteps)
        }
        progress?.onStart(totalSteps)

        // --------- Paints ----------
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            textSize = 18f
            color = Color.BLACK
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f
            color = Color.BLACK
        }
        val sectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            textSize = 11f
            color = Color.BLACK
        }
        val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 9.5f
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
        val stripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(18, 0, 0, 0) // gris MUY suave
            style = Paint.Style.FILL
        }

        ping("Preparando lienzo…")

        val headerLogo = headerLogoResId?.let { safeDecode(context, it) }
        val watermark = watermarkResId?.let { safeDecode(context, it) }
        ping("Cargando recursos…")

        // ---------- Helper para hacer word-wrap ----------
        fun wrapText(text: String, paint: Paint, maxWidth: Int): List<String> {
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
                    if (current.isNotEmpty()) {
                        lines += current.toString()
                    }
                    current.clear()
                    current.append(word)
                }
            }
            if (current.isNotEmpty()) {
                lines += current.toString()
            }
            return lines
        }

        // ---------- Funciones de dibujo ----------

        fun Canvas.drawWatermark() {
            watermark?.let { bmp ->
                withSave {
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

            // Título centrado, un poco más arriba
            drawText(
                header.title,
                centerX - (titleWidth / 2f),
                yStart.toFloat(),
                titlePaint
            )

            // 🔹 Subtítulo institucional
            val subtitle = "UH-60L · Escuadrón Aéreo 113 · F.A.M."
            val subWidth = subPaint.measureText(subtitle)
            drawText(
                subtitle,
                centerX - (subWidth / 2f),
                (yStart + 16).toFloat(),
                subPaint
            )

            // Espacio antes de las filas
            y += 35

            // Normalizar strings
            val horaStr   = header.hora24.ifBlank { "--:--" }
            val gradoStr  = header.tecnicoGrado.ifBlank { "—" }
            val espStr    = header.tecnicoEspecialidad.ifBlank { "—" }
            val tecStr    = header.tecnicoNombre.ifBlank { "—" }
            val hsTotStr  = header.hsTotales?.takeIf { it.isNotBlank() } ?: "—"
            val hsDispStr = header.hsDisponibles?.takeIf { it.isNotBlank() } ?: "—"

            // Fila 1: Fecha | Grado
            drawText("Fecha: ${header.fecha}", xLeft.toFloat(), y.toFloat(), subPaint)
            drawText("Grado: $gradoStr", xRight.toFloat(), y.toFloat(), subPaint)
            y += 16

            // Fila 2: Hora | Especialidad
            drawText("Hora: $horaStr", xLeft.toFloat(), y.toFloat(), subPaint)
            drawText("Especialidad: $espStr", xRight.toFloat(), y.toFloat(), subPaint)
            y += 16

            // Fila 3: Aeronave | Técnico
            drawText("Aeronave: ${header.matAeronave}", xLeft.toFloat(), y.toFloat(), subPaint)
            drawText("Técnico: $tecStr", xRight.toFloat(), y.toFloat(), subPaint)
            y += 16

            // Fila 4: Hs Totales | Hs Disp.
            drawText("Hs Totales: $hsTotStr", xLeft.toFloat(), y.toFloat(), subPaint)
            drawText("Hs Disp.: $hsDispStr", xRight.toFloat(), y.toFloat(), subPaint)
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

        // Layout de columnas para checklist (dos mitades de la hoja)
        data class ColumnLayout(
            val startX: Int,
            val width: Int,
            val numColX: Int,
            val textColX: Int,
            val checkColX: Int
        )

        fun buildColumnLayout(isRight: Boolean): ColumnLayout {
            val colGap = 24
            val totalWidth = pageW - margin * 2 - colGap
            val colWidth = totalWidth / 2
            val startX = if (!isRight) margin else margin + colWidth + colGap

            val numColWidth = 18
            val checkColWidth = 18
            val numColX = startX
            val textColX = startX + numColWidth + 4
            val checkColX = startX + colWidth - checkColWidth

            return ColumnLayout(
                startX = startX,
                width = colWidth,
                numColX = numColX,
                textColX = textColX,
                checkColX = checkColX
            )
        }

        /** Encabezado de tabla ("No. / Concepto / OK") para UNA columna. yTop = parte alta del header. */
        fun Canvas.drawChecklistHeaderRow(yTop: Int, col: ColumnLayout): Int {
            val baseline = yTop + tableHeaderPaint.textSize.toInt()

            drawText("No.", col.numColX.toFloat(), baseline.toFloat(), tableHeaderPaint)
            drawText("Concepto", col.textColX.toFloat(), baseline.toFloat(), tableHeaderPaint)
            drawText("OK", col.checkColX.toFloat(), baseline.toFloat(), tableHeaderPaint)

            val lineY = baseline + 4
            drawLine(
                col.startX.toFloat(),
                lineY.toFloat(),
                (col.startX + col.width).toFloat(),
                lineY.toFloat(),
                linePaint
            )

            // Regresamos el "top" desde donde empiezan las filas de datos
            return lineY + 6
        }

        fun Canvas.drawSectionTitleRow(title: String, yTop: Int, col: ColumnLayout): Int {
            val baseline = yTop + sectionPaint.textSize.toInt()
            drawText(title, col.textColX.toFloat(), baseline.toFloat(), sectionPaint)
            return baseline + 4
        }

        // 🔧 MODIFICADO: ahora recibe isWarning y pone MAYÚSCULAS + NEGRITAS
        fun Canvas.drawItemRow(
            globalIndex: Int,
            itemTitle: String,
            checked: Boolean,
            isWarning: Boolean,
            yTop: Int,
            col: ColumnLayout
        ): Int {
            val maxTextWidth = col.checkColX - 6 - col.textColX

            // Si es warning, texto en mayúsculas
            val displayTitle = if (isWarning) {
                itemTitle.uppercase(Locale.getDefault())
            } else {
                itemTitle
            }

            val lines = wrapText(displayTitle, smallPaint, maxTextWidth).ifEmpty { listOf("") }

            val lineHeight = smallPaint.textSize + 4f
            val rowHeight = (lineHeight * lines.size + 4f).toInt()
            val baselineFirst = yTop + smallPaint.textSize.toInt()

            // Stripe alternado
            if (globalIndex % 2 == 0) {
                drawRect(
                    col.startX.toFloat(),
                    (yTop - 2).toFloat(),
                    (col.startX + col.width).toFloat(),
                    (yTop + rowHeight + 2).toFloat(),
                    stripePaint
                )
            }

            // Guardar y cambiar typeface si es warning
            val originalTf = smallPaint.typeface
            if (isWarning) {
                smallPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }

            // Número y check alineados con la primera línea
            drawText(globalIndex.toString(), col.numColX.toFloat(), baselineFirst.toFloat(), smallPaint)
            val mark = if (checked) "✓" else ""
            drawText(mark, col.checkColX.toFloat(), baselineFirst.toFloat(), smallPaint)

            // Texto en múltiples líneas
            var baseline = baselineFirst
            for (line in lines) {
                drawText(line, col.textColX.toFloat(), baseline.toFloat(), smallPaint)
                baseline += lineHeight.toInt()
            }

            // Restaurar typeface
            smallPaint.typeface = originalTf

            return yTop + rowHeight
        }

        /** Pie con firma a la derecha, matrícula centrada y etiqueta. */
        fun Canvas.drawFooter(signature: Bitmap?, tecnicoMatricula: String?) {
            val lineW = 175

            // Bloque de firma pegado a la derecha de la hoja
            val lineRight = pageW - margin
            val lineLeft = lineRight - lineW
            val signH = 70

            var y = pageH - margin - signH - 40

            // Firma: centrada dentro del bloque [lineLeft, lineRight]
            if (signature != null) {
                val ratio = signature.width.toFloat() / signature.height.toFloat()
                val dstH = signH
                val dstW = (dstH * ratio).toInt().coerceAtMost(lineW)

                // ⬅️ CAMBIO IMPORTANTE AQUÍ:
                // antes: val sigLeft = lineRight - dstW   (pegada a la derecha del bloque)
                // ahora: centrada dentro del bloque
                val sigLeft = lineLeft + ((lineW - dstW) / 2)
                val sigTop = y

                val paintSig = Paint(Paint.ANTI_ALIAS_FLAG)
                drawBitmap(
                    signature,
                    null,
                    Rect(sigLeft, sigTop, sigLeft + dstW, sigTop + dstH),
                    paintSig
                )
            }

            y += signH + 4

            // Línea de firma (bloque a la derecha)
            drawLine(
                lineLeft.toFloat(),
                y.toFloat(),
                lineRight.toFloat(),
                y.toFloat(),
                linePaint
            )
            y += 12

            // Matrícula centrada dentro del bloque
            tecnicoMatricula
                ?.takeIf { it.isNotBlank() }
                ?.let { mat ->
                    val textWidth = smallPaint.measureText(mat)
                    val textX = lineLeft + (lineW - textWidth) / 2f
                    drawText(mat, textX, y.toFloat(), smallPaint)
                    y += 14
                }

            // Etiqueta de la firma centrada dentro del bloque
            val label = signatureLabel.ifBlank { "Firma del técnico" }
            val labelWidth = smallPaint.measureText(label)
            val labelX = lineLeft + (lineW - labelWidth) / 2f
            drawText(label, labelX, y.toFloat(), smallPaint)
            y += 14

            // Manual al lado izquierdo de la HOJA
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



        // ---------- Preparar documento ----------
        val doc = PdfDocument()
        var pageNum = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
        var canvas = page.canvas
        canvas.drawWatermark()
        var headerBottomY = canvas.drawHeader(margin)

        // Layout de columnas para esta página
        var currentLeftCol = buildColumnLayout(isRight = false)
        var currentRightCol = buildColumnLayout(isRight = true)
        var currentColumnIsRight = false
        var currentColLayout = currentLeftCol

        // y = "top" para la primera fila debajo del header de tabla
        var y = canvas.drawChecklistHeaderRow(headerBottomY + 4, currentColLayout)

        // 🔧 MODIFICADO: FlatRow incluye warning
        data class FlatRow(
            val isSection: Boolean,
            val sectionTitle: String? = null,
            val itemTitle: String? = null,
            val checked: Boolean = false,
            val warning: Boolean = false
        )

        // Aplanar secciones + ítems (nivel 1)
        val flatRows = mutableListOf<FlatRow>()
        checklist.sections.forEach { section ->
            flatRows += FlatRow(isSection = true, sectionTitle = section.title)
            section.items.forEach { item ->
                val checked = checkedByTitle[item.title] == true
                val isWarning = item.warning == true
                flatRows += FlatRow(
                    isSection = false,
                    itemTitle = item.title,
                    checked = checked,
                    warning = isWarning
                )
            }
        }

        var globalItemIndex = 0

        fun startNewPage() {
            doc.finishPage(page)
            pageNum++
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
            canvas = page.canvas
            canvas.drawWatermark()
            headerBottomY = canvas.drawHeader(margin)
            currentLeftCol = buildColumnLayout(isRight = false)
            currentRightCol = buildColumnLayout(isRight = true)
            currentColumnIsRight = false
            currentColLayout = currentLeftCol
            y = canvas.drawChecklistHeaderRow(headerBottomY + 4, currentColLayout)
        }

        // Recorremos filas (secciones + items) y pintamos en dos columnas por página
        flatRows.forEach { row ->
            // Altura aproximada necesaria
            val needed = if (row.isSection) {
                24
            } else {
                val maxTextWidth = currentColLayout.checkColX - 6 - currentColLayout.textColX
                val lineCount = wrapText(row.itemTitle.orEmpty(), smallPaint, maxTextWidth)
                    .ifEmpty { listOf("") }
                    .size
                ((smallPaint.textSize + 4f) * lineCount + 6f).toInt()
            }

            // Si no cabe en la columna actual
            if (y + needed > contentBottom) {
                if (!currentColumnIsRight) {
                    // Pasamos a la columna derecha
                    currentColumnIsRight = true
                    currentColLayout = currentRightCol
                    y = canvas.drawChecklistHeaderRow(headerBottomY + 4, currentColLayout)
                } else {
                    // Nueva página
                    startNewPage()
                }
            }

            if (row.isSection) {
                y = canvas.drawSectionTitleRow(row.sectionTitle.orEmpty(), y, currentColLayout)
            } else {
                globalItemIndex++
                y = canvas.drawItemRow(
                    globalIndex = globalItemIndex,
                    itemTitle = row.itemTitle.orEmpty(),
                    checked = row.checked,
                    isWarning = row.warning,
                    yTop = y,
                    col = currentColLayout
                )
                ping("Ítem $globalItemIndex…")
            }
        }

        // Pie solo en la última página
        canvas.drawFooter(signatureBitmap, header.tecnicoMatricula)
        doc.finishPage(page)
        ping("Pie y firma…")

        val safeName = sanitize("${fileNameHint.ifBlank { timeStamp() }}.pdf")
        val uri = saveToDocuments(context, safeName, doc)
        doc.close()
        ping("Guardando en Documentos…")
        progress?.onDone()
        return uri
    }

    fun openPdf(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Abrir con"))
    }

    private fun safeDecode(ctx: Context, @DrawableRes resId: Int): Bitmap? =
        try { BitmapFactory.decodeResource(ctx.resources, resId) } catch (_: Throwable) { null }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9._-]+"), "_")

    private fun timeStamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** Guardar en Documentos/Inspecciones/Preflights */
    private fun saveToDocuments(context: Context, fileName: String, doc: PdfDocument): Uri? =
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val relativePath = Environment.DIRECTORY_DOCUMENTS + "/Inspecciones/Preflights"
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
                val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                val targetDir = File(docsDir, "Inspecciones/Preflights")
                if (!targetDir.exists()) targetDir.mkdirs()
                val file = File(targetDir, fileName)
                FileOutputStream(file).use { fos -> doc.writeTo(fos) }
                Uri.fromFile(file)
            }
        } catch (_: Throwable) { null }
}
