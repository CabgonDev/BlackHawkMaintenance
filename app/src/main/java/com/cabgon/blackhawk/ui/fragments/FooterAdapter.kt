package com.cabgon.blackhawk.ui.fragments

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cabgon.blackhawk.databinding.ItemFooterMoreBinding

class FooterAdapter(
    private val onMore: () -> Unit
) : RecyclerView.Adapter<FooterAdapter.VH>() {

    private var visible = false
    private var shown = 0
    private var total = 0

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = 1L

    /** Muestra/oculta el footer y actualiza conteo. */
    fun show(show: Boolean, shownCount: Int = shown, totalCount: Int = total) {
        val oldVisible = visible
        visible = show
        shown = shownCount
        total = totalCount

        when {
            !oldVisible && visible -> notifyItemInserted(0)
            oldVisible && !visible -> notifyItemRemoved(0)
            oldVisible && visible  -> notifyItemChanged(0)
        }
    }

    /** Solo actualiza conteo sin cambiar visibilidad. */
    fun updateCounts(shownCount: Int, totalCount: Int) {
        shown = shownCount
        total = totalCount
        if (visible) notifyItemChanged(0)
    }

    override fun getItemCount(): Int = if (visible) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inf = LayoutInflater.from(parent.context)
        return VH(ItemFooterMoreBinding.inflate(inf, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.b.root.isFocusable = false
        holder.b.root.isFocusableInTouchMode = false

        holder.b.tvMoreMeta.text = "Mostrando $shown de $total"

        val hasMore = shown < total
        with(holder.b.btnMoreFooter) {
            isEnabled = hasMore
            text = if (hasMore) "Ver más" else "No hay más resultados"
            isFocusable = false
            isFocusableInTouchMode = false
            setOnClickListener { if (hasMore) onMore() }
        }
    }

    class VH(val b: ItemFooterMoreBinding) : RecyclerView.ViewHolder(b.root)
}
