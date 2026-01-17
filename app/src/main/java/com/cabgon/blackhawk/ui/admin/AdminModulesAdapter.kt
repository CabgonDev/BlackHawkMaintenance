package com.cabgon.blackhawk.ui.admin

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.cabgon.blackhawk.R

class AdminModulesAdapter(
    private val items: List<AdminModule>,
    private val onClick: (AdminModule) -> Unit
) : RecyclerView.Adapter<AdminModulesAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_admin_module, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title = itemView.findViewById<TextView>(R.id.txtModuleTitle)
        private val desc = itemView.findViewById<TextView>(R.id.txtModuleDesc)

        fun bind(m: AdminModule) {
            title.setText(m.titleRes)
            desc.text = m.desc
            itemView.setOnClickListener { onClick(m) }
        }
    }
}
