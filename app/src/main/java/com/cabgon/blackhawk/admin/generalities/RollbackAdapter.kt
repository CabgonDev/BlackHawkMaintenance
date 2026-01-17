package com.cabgon.blackhawk.admin.generalities

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cabgon.blackhawk.admin.generalities.AdminGeneralitiesRollbackFragment.RollbackItem
import com.cabgon.blackhawk.databinding.ItemAdminGeneralitiesRollbackBinding

class RollbackAdapter(
    private val onRollback: (RollbackItem) -> Unit
) : RecyclerView.Adapter<RollbackAdapter.VH>() {

    private val items = mutableListOf<RollbackItem>()

    fun submit(list: List<RollbackItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(private val b: ItemAdminGeneralitiesRollbackBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: RollbackItem) {
            b.txtMain.text = "v${item.version} · bytes=${item.bytes} · sha=${item.sha256.take(8)}…"
            b.txtMeta.text = "release: ${item.releasePath}"

            // ✅ Evitar sombra de 'it' (en click listener 'it' es View)
            b.btnRollback.setOnClickListener { onRollback(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemAdminGeneralitiesRollbackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size
}
