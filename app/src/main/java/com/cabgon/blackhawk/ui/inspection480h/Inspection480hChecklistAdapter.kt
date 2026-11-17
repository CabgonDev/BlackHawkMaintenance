package com.cabgon.blackhawk.ui.inspection480h

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.data.inspection480h.Inspection480hChecklistItem
import com.cabgon.blackhawk.data.inspection480h.Inspection480hChecklistStore
import com.cabgon.blackhawk.data.inspection480h.Inspection480hRepository
import com.cabgon.blackhawk.databinding.ItemPreflightChecklistBinding
import com.cabgon.blackhawk.databinding.ItemPreflightSectionHeaderBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Inspection480hChecklistAdapter(
    private val specByOrder: Map<Int, Inspection480hChecklistItem>,
    private val metaByOrder: Map<Int, Inspection480hChecklistStore.FlatChecklistEntry>,
    private val sectionOrder: List<String>,
    private val onToggle: (itemId: String, checked: Boolean) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        data class Header(
            val sectionTitle: String,
            val done: Int,
            val total: Int,
            val expanded: Boolean
        ) : Row()

        data class ItemRow(val item: Inspection480hRepository.Item) : Row()
    }

    private class SectionHeaderVH(val b: ItemPreflightSectionHeaderBinding) :
        RecyclerView.ViewHolder(b.root)

    private class ItemVH(val b: ItemPreflightChecklistBinding) :
        RecyclerView.ViewHolder(b.root)

    private val rows = mutableListOf<Row>()
    private val expandedSections = linkedSetOf<String>()
    private var inspectionItems: List<Inspection480hRepository.Item> = emptyList()
    private var readOnly: Boolean = false

    init {
        setHasStableIds(true)
    }

    fun submitInspectionItems(newItems: List<Inspection480hRepository.Item>) {
        inspectionItems = newItems
        rebuildRows()
    }

    fun setReadOnly(value: Boolean) {
        if (readOnly == value) return
        readOnly = value
        notifyDataSetChanged()
    }

    fun applyOptimisticToggle(itemId: String, checked: Boolean, checkedByFirstLastName: String?) {
        inspectionItems = inspectionItems.map { item ->
            if (item.id != itemId) item else item.copy(
                checked = checked,
                checkedByFirstLastName = if (checked) checkedByFirstLastName else null,
                checkedAt = if (checked) System.currentTimeMillis() else null
            )
        }
        rebuildRows()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemId(position: Int): Long {
        return when (val row = rows[position]) {
            is Row.Header -> ("header:" + row.sectionTitle).hashCode().toLong()
            is Row.ItemRow -> row.item.id.hashCode().toLong()
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (rows[position]) {
            is Row.Header -> 0
            is Row.ItemRow -> 1
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == 0) {
            val b = ItemPreflightSectionHeaderBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            SectionHeaderVH(b)
        } else {
            val b = ItemPreflightChecklistBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            ItemVH(b)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> bindHeader(holder as SectionHeaderVH, row)
            is Row.ItemRow -> bindItem(holder as ItemVH, row.item)
        }
    }

    private fun bindHeader(holder: SectionHeaderVH, row: Row.Header) {
        val ctx = holder.itemView.context
        val pct = if (row.total == 0) 0 else (row.done * 100 / row.total)

        holder.b.tvSectionTitle.text = row.sectionTitle
        holder.b.tvSectionCount.text = "${row.done} / ${row.total}"
        holder.b.tvChevron.text = if (row.expanded) "▼" else "▶"
        holder.b.progressSection.max = 100
        holder.b.progressSection.progress = pct

        when {
            pct == 100 && row.total > 0 -> {
                holder.b.tvSectionState.text = "Lista"
                holder.b.tvSectionState.setBackgroundResource(R.drawable.bg_preflight_status_success)
                holder.b.tvSectionState.setTextColor(
                    ContextCompat.getColor(ctx, R.color.preflight_success_text)
                )
                holder.b.progressSection.setIndicatorColor(
                    ContextCompat.getColor(ctx, R.color.preflight_success_text)
                )
                holder.b.progressSection.trackColor =
                    ContextCompat.getColor(ctx, R.color.preflight_active_bg)
                holder.b.root.strokeColor =
                    ContextCompat.getColor(ctx, R.color.preflight_border)
            }

            row.done > 0 -> {
                holder.b.tvSectionState.text = "En curso"
                holder.b.tvSectionState.setBackgroundResource(R.drawable.bg_preflight_status_active)
                holder.b.tvSectionState.setTextColor(
                    ContextCompat.getColor(ctx, R.color.preflight_active_text)
                )
                holder.b.progressSection.setIndicatorColor(
                    ContextCompat.getColor(ctx, R.color.preflight_active_text)
                )
                holder.b.progressSection.trackColor =
                    ContextCompat.getColor(ctx, R.color.preflight_pending_bg)
                holder.b.root.strokeColor =
                    ContextCompat.getColor(ctx, R.color.preflight_border_strong)
            }

            else -> {
                holder.b.tvSectionState.text = "Pendiente"
                holder.b.tvSectionState.setBackgroundResource(R.drawable.bg_preflight_status_pending)
                holder.b.tvSectionState.setTextColor(
                    ContextCompat.getColor(ctx, R.color.preflight_pending_text)
                )
                holder.b.progressSection.setIndicatorColor(
                    ContextCompat.getColor(ctx, R.color.preflight_command_tint)
                )
                holder.b.progressSection.trackColor =
                    ContextCompat.getColor(ctx, R.color.preflight_pending_bg)
                holder.b.root.strokeColor =
                    ContextCompat.getColor(ctx, R.color.preflight_border)
            }
        }

        holder.b.root.setOnClickListener {
            if (expandedSections.contains(row.sectionTitle)) {
                expandedSections.remove(row.sectionTitle)
            } else {
                expandedSections.add(row.sectionTitle)
            }
            rebuildRows()
        }
    }

    private fun bindItem(holder: ItemVH, item: Inspection480hRepository.Item) {
        val ctx = holder.itemView.context
        val spec = specByOrder[item.orderIndex]
        val meta = metaByOrder[item.orderIndex]

        val fullTitle = spec?.title?.takeIf { it.isNotBlank() } ?: item.longText
        val baseLabel = spec?.short?.takeIf { it.isNotBlank() } ?: item.shortText
        val isRequired = spec?.required == true
        val isWarning = spec?.warning == true
        val isInfo = spec?.info == true
        val detailText = spec?.text?.takeIf { it.isNotBlank() } ?: item.longText
        val hasChildren = meta?.hasChildren == true
        val codePrefix = item.code.takeIf { it.isNotBlank() }?.let { "$it · " } ?: ""

        val indentPx = (((meta?.depth ?: 0) * 20) * ctx.resources.displayMetrics.density).toInt()
        holder.b.viewIndent.layoutParams = holder.b.viewIndent.layoutParams.apply {
            width = indentPx
        }
        holder.b.viewIndent.requestLayout()

        holder.b.imgWarning.isVisible = isWarning
        holder.b.tvWarning.isVisible = true
        holder.b.tvWarning.contentDescription = ctx.getString(R.string.ver)

        holder.b.root.strokeColor = if (isWarning) {
            ContextCompat.getColor(ctx, R.color.preflight_warning_border)
        } else {
            ContextCompat.getColor(ctx, R.color.preflight_border)
        }

        holder.b.root.setCardBackgroundColor(
            ContextCompat.getColor(
                ctx,
                if (isWarning) R.color.preflight_warning_bg else R.color.preflight_card
            )
        )

        holder.b.check.setOnCheckedChangeListener(null)
        holder.b.check.text = codePrefix + baseLabel
        holder.b.check.isChecked = item.checked
        val interactive = !readOnly && !isWarning && !isInfo && !hasChildren
        holder.b.check.isEnabled = interactive
        holder.b.check.isClickable = interactive
        holder.b.check.isFocusable = interactive
        holder.b.check.alpha = if ((hasChildren || isInfo) && !isWarning) 0.9f else 1f
        holder.b.check.setTypeface(
            null,
            when {
                isWarning || isInfo || hasChildren || isRequired -> Typeface.BOLD
                else -> Typeface.NORMAL
            }
        )

        val checkStamp = if (item.checkedAt != null) {
            val sdf = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
            val by = item.checkedByFirstLastName?.takeIf { it.isNotBlank() } ?: "—"
            " · $by · ${sdf.format(Date(item.checkedAt))}"
        } else {
            ""
        }

        holder.b.tvItemMeta.text = when {
            isWarning -> "Advertencia operacional"
            isInfo -> "Información"
            hasChildren -> "Grupo de verificación"
            isRequired -> "Paso requerido$checkStamp"
            else -> "Paso opcional$checkStamp"
        }

        holder.b.tvItemMeta.setTextColor(
            ContextCompat.getColor(
                ctx,
                when {
                    isWarning -> R.color.preflight_warning_text
                    hasChildren || isInfo -> R.color.preflight_active_text
                    else -> R.color.preflight_text_secondary
                }
            )
        )

        if (!readOnly && !isWarning && !isInfo && !hasChildren) {
            holder.b.check.setOnCheckedChangeListener { _, checked ->
                onToggle(item.id, checked)
            }
        }

        holder.b.tvWarning.setOnClickListener {
            val dialogTitle = item.code.takeIf { it.isNotBlank() }
                ?: if (hasChildren) "Grupo de verificación" else "Detalle del paso"

            val dialogMessage = buildString {
                append(fullTitle)
                if (detailText.isNotBlank() && detailText != fullTitle) {
                    append("\n\n")
                    append(detailText)
                }
                if (item.checkedAt != null || !item.checkedByFirstLastName.isNullOrBlank()) {
                    val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                    append("\n\nResponsable: ")
                    append(item.checkedByFirstLastName?.takeIf { it.isNotBlank() } ?: "—")
                    append("\nFecha/hora: ")
                    append(item.checkedAt?.let { sdf.format(Date(it)) } ?: "—")
                }
            }

            if (isWarning) {
                AlertDialog.Builder(ctx)
                    .setTitle(dialogTitle.ifBlank { "Advertencia" })
                    .setMessage(dialogMessage.ifBlank { "Sin detalle disponible." })
                    .setPositiveButton(if (readOnly || item.checked) "Cerrar" else "Enterado") { dlg, _ ->
                        dlg.dismiss()
                        if (!readOnly && !item.checked) {
                            onToggle(item.id, true)
                        }
                    }
                    .show()
            } else {
                AlertDialog.Builder(ctx)
                    .setTitle(dialogTitle)
                    .setMessage(dialogMessage.ifBlank { "Sin detalle disponible." })
                    .setPositiveButton("Cerrar", null)
                    .show()
            }
        }
    }

    private fun rebuildRows() {
        rows.clear()

        val itemsBySection = inspectionItems.groupBy { item ->
            metaByOrder[item.orderIndex]?.sectionTitle ?: "Otros"
        }

        val remainingSections = itemsBySection.keys - sectionOrder.toSet()
        val orderedSections = sectionOrder + remainingSections.sorted()

        orderedSections.forEach { sectionTitle ->
            val sectionItems = itemsBySection[sectionTitle].orEmpty()
                .sortedBy { it.orderIndex }

            if (sectionItems.isEmpty()) return@forEach

            rows += Row.Header(
                sectionTitle = sectionTitle,
                done = sectionItems.count { it.checked },
                total = sectionItems.size,
                expanded = expandedSections.contains(sectionTitle)
            )

            if (expandedSections.contains(sectionTitle)) {
                sectionItems.forEach { item ->
                    rows += Row.ItemRow(item)
                }
            }
        }

        notifyDataSetChanged()
    }
}
