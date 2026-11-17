package com.cabgon.blackhawk.ui.inspection480h

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.DrawableRes
import com.cabgon.blackhawk.ui.pdf.BaseInspectionPdfExporter

object Inspection480hPdfExporter {

    data class Header(
        val title: String,
        val fecha: String,
        val hora24: String,
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
        val responsable: String?,
        val fechaHoraCheck: String?,
        val participantLabel: String?,
        val rowKind: BaseInspectionPdfExporter.RowKind = BaseInspectionPdfExporter.RowKind.STANDARD,
        val indentLevel: Int = 0
    )

    fun export480h(
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

        val margin = 36f
        val columns = listOf(
            BaseInspectionPdfExporter.Column("Descripción", x = margin),
            BaseInspectionPdfExporter.Column("Estado", x = margin + 320f),
            BaseInspectionPdfExporter.Column("Resp.", x = margin + 378f),
            BaseInspectionPdfExporter.Column("Fecha/Hora", x = margin + 454f)
        )

        val tableConfig = BaseInspectionPdfExporter.TableConfig(
            columns = columns,
            descriptionColumnIndex = 0,
            showParticipantsBlock = true,
            signatureLabel = signatureLabel,
            subfolderName = "480H"
        )

        val rows = items.map { row ->
            val description = listOfNotNull(
                row.code.takeIf { it.isNotBlank() },
                row.description.takeIf { it.isNotBlank() }
            ).joinToString(" ").ifBlank { row.description }

            val showExecutionMeta = row.rowKind == BaseInspectionPdfExporter.RowKind.STANDARD

            BaseInspectionPdfExporter.TableRow(
                index = row.index,
                cells = listOf(
                    description,
                    if (showExecutionMeta) if (row.checked) "OK" else "Pend." else "",
                    if (showExecutionMeta) row.responsable?.takeIf { it.isNotBlank() } ?: "—" else "",
                    if (showExecutionMeta) row.fechaHoraCheck?.takeIf { it.isNotBlank() } ?: "—" else ""
                ),
                checked = showExecutionMeta && row.checked,
                participantLabel = if (showExecutionMeta) row.participantLabel else null,
                kind = row.rowKind,
                indentLevel = row.indentLevel
            )
        }

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
