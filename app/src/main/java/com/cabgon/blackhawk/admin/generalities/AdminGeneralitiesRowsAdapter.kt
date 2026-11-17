package com.cabgon.blackhawk.admin.generalities

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cabgon.blackhawk.databinding.ItemAdminGeneralityRow2Binding
import com.cabgon.blackhawk.databinding.ItemAdminGeneralityRow3Binding

class AdminGeneralitiesRowsAdapter(
    initialColCount: Int,
    private val canEdit: Boolean,
    private val onDeleteRow: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var colCount: Int = initialColCount.coerceIn(2, 3)
    private val rows: MutableList<MutableList<String>> = mutableListOf()

    fun setRows(newRows: List<List<String>>, colCount: Int) {
        this.colCount = colCount.coerceIn(2, 3)
        rows.clear()
        newRows.forEach { r ->
            val m = MutableList(this.colCount) { "" }
            for (i in 0 until this.colCount) m[i] = r.getOrNull(i).orEmpty()
            rows.add(m)
        }
        notifyDataSetChanged()
    }

    fun setColumnCount(n: Int, preserveData: Boolean, refreshRows: Boolean) {
        val newCount = n.coerceIn(2, 3)
        if (newCount == colCount) return

        if (preserveData) {
            rows.forEach { r ->
                if (newCount > colCount) {
                    while (r.size < newCount) r.add("")
                } else {
                    while (r.size > newCount) r.removeAt(r.lastIndex)
                }
            }
        } else {
            rows.clear()
        }

        colCount = newCount
        if (refreshRows) notifyDataSetChanged()
    }

    fun addEmptyRow() {
        val r = MutableList(colCount) { "" }
        rows.add(r)
        notifyItemInserted(rows.lastIndex)
    }

    fun removeAt(index: Int) {
        if (index !in rows.indices) return
        rows.removeAt(index)
        notifyItemRemoved(index)
    }

    fun getRowsNormalized(colCount: Int): List<List<String>> {
        val n = colCount.coerceIn(2, 3)
        return rows.map { r ->
            List(n) { i -> r.getOrNull(i).orEmpty() }
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = if (colCount == 2) 2 else 3

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 2) Row2VH(ItemAdminGeneralityRow2Binding.inflate(inf, parent, false))
        else Row3VH(ItemAdminGeneralityRow3Binding.inflate(inf, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is Row2VH) holder.bind(position)
        if (holder is Row3VH) holder.bind(position)
    }

    inner class Row2VH(private val b: ItemAdminGeneralityRow2Binding) : RecyclerView.ViewHolder(b.root) {
        fun bind(pos: Int) {
            val r = rows[pos]

            b.edt1.setText(r.getOrNull(0).orEmpty())
            b.edt2.setText(r.getOrNull(1).orEmpty())

            b.btnDelete.isEnabled = canEdit
            b.btnDelete.alpha = if (canEdit) 1f else 0.35f
            b.edt1.isEnabled = canEdit
            b.edt2.isEnabled = canEdit

            b.btnDelete.setOnClickListener { onDeleteRow(pos) }

            bindWatcher(b.edt1) { r[0] = it }
            bindWatcher(b.edt2) { r[1] = it }
        }
    }

    inner class Row3VH(private val b: ItemAdminGeneralityRow3Binding) : RecyclerView.ViewHolder(b.root) {
        fun bind(pos: Int) {
            val r = rows[pos]

            b.edt1.setText(r.getOrNull(0).orEmpty())
            b.edt2.setText(r.getOrNull(1).orEmpty())
            b.edt3.setText(r.getOrNull(2).orEmpty())

            b.btnDelete.isEnabled = canEdit
            b.btnDelete.alpha = if (canEdit) 1f else 0.35f
            b.edt1.isEnabled = canEdit
            b.edt2.isEnabled = canEdit
            b.edt3.isEnabled = canEdit

            b.btnDelete.setOnClickListener { onDeleteRow(pos) }

            bindWatcher(b.edt1) { r[0] = it }
            bindWatcher(b.edt2) { r[1] = it }
            bindWatcher(b.edt3) { r[2] = it }
        }
    }

    private fun bindWatcher(editText: android.widget.EditText, onText: (String) -> Unit) {
        val tagKey = "tw"
        val old = editText.getTag(editText.id)
        if (old is TextWatcher) editText.removeTextChangedListener(old)

        val tw = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { onText(s?.toString().orEmpty()) }
        }
        editText.addTextChangedListener(tw)
        editText.setTag(editText.id, tw)
    }
}
