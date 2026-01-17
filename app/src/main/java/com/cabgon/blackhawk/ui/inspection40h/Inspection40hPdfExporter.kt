package com.cabgon.blackhawk.ui.inspection40h

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.DrawableRes
import com.cabgon.blackhawk.ui.pdf.BaseInspectionPdfExporter

object Inspection40hPdfExporter {

    data class Header(
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

    data class ItemRow(
        val index: Int,
        val code: String,
        val description: String,
        val checked: Boolean,
        val responsable: String?,          // apellido para la columna Resp.
        val fechaHoraCheck: String?,
        val participantLabel: String?      // "Grado Esp Nombre" para PARTICIPANTES
    )

    fun export40h(
        context: Context,
        fileNameHint: String,
        header: Header,
        items: List<ItemRow>,
        signatureLabel: String = "Firma del supervisor",
        @DrawableRes watermarkResId: Int? = null,
        watermarkAlpha: Int = 60,
        @DrawableRes headerLogoResId: Int? = null,
        signatureBitmap: Bitmap? = null
    ): Uri? {
        // 1) Mapear Header local → Header genérico
        val baseHeader = BaseInspectionPdfExporter.InspectionHeader(
            title = header.title,
            fecha = header.fecha,
            hora24 = header.hora24,
            matAeronave = header.matAeronave,
            supervisorGrado = header.supervisorGrado,
            supervisorEspecialidad = header.supervisorEspecialidad,
            supervisorNombre = header.supervisorNombre,
            supervisorMatricula = header.supervisorMatricula,
            hsTotales = header.hsTotales
        )

        // 2) Definir columnas con mismas X que ya tenías
        val margin = 36f
        val columns = listOf(
            BaseInspectionPdfExporter.Column("#",           x = margin),
            BaseInspectionPdfExporter.Column("Descripción", x = margin + 30f),
            BaseInspectionPdfExporter.Column("Estado",      x = margin + 280f),
            BaseInspectionPdfExporter.Column("Resp.",       x = margin + 340f),
            BaseInspectionPdfExporter.Column("Fecha/Hora",  x = margin + 430f)
        )

        val tableConfig = BaseInspectionPdfExporter.TableConfig(
            columns = columns,
            descriptionColumnIndex = 1,          // columna "Descripción"
            showParticipantsBlock = true,
            signatureLabel = signatureLabel,
            subfolderName = "40H"
        )

        // 3) Mapear items → TableRow genérico
        val rows = items.map { row ->
            BaseInspectionPdfExporter.TableRow(
                index = row.index,
                cells = listOf(
                    row.index.toString(),
                    "${row.code}  ${row.description}",
                    if (row.checked) "OK" else "Pend.",
                    row.responsable?.takeIf { it.isNotBlank() } ?: "—",
                    row.fechaHoraCheck?.takeIf { it.isNotBlank() } ?: "—"
                ),
                checked = row.checked,
                participantLabel = row.participantLabel
            )
        }

        // 4) Delegar al exporter base
        return BaseInspectionPdfExporter.exportInspection(
            context = context,
            fileNameHint = fileNameHint,
            header = baseHeader,
            rows = rows,
            tableConfig = tableConfig,
            watermarkResId = watermarkResId,
            watermarkAlpha = watermarkAlpha,
            headerLogoResId = headerLogoResId,
            signatureBitmap = signatureBitmap
        )
    }

    fun openPdf(context: Context, uri: Uri) {
        BaseInspectionPdfExporter.openPdf(context, uri)
    }
}
