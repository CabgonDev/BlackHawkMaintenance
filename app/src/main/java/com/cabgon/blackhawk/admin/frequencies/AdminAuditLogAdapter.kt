package com.cabgon.blackhawk.admin.frequencies

import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cabgon.blackhawk.databinding.ItemAdminAuditLogBinding
import java.util.Date

class AdminAuditLogAdapter(
    private val canRollback: Boolean,
    private val onDetails: (AdminFrequenciesRepository.AuditEntry) -> Unit,
    private val onRollback: (AdminFrequenciesRepository.AuditEntry) -> Unit
) : RecyclerView.Adapter<AdminAuditLogAdapter.VH>() {

    private var list: List<AdminFrequenciesRepository.AuditEntry> = emptyList()

    fun submit(newList: List<AdminFrequenciesRepository.AuditEntry>) {
        list = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemAdminAuditLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(list[position])

    override fun getItemCount(): Int = list.size

    inner class VH(private val b: ItemAdminAuditLogBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(e: AdminFrequenciesRepository.AuditEntry) {
            val ts = DateFormat.format("yyyy-MM-dd HH:mm", Date(e.ts)).toString()
            b.txtTitle.text = "${e.action} · v${e.version}"
            b.txtSub.text = "$ts · sha=${e.shaShort} · bytes=${e.bytes}"
            b.txtPath.text = e.releasePath ?: (e.stablePath ?: "-")

            b.btnDetails.setOnClickListener { onDetails(e) }

            val isRollbackCandidate = canRollback && e.action == "publish_frequencies" && !e.releasePath.isNullOrBlank()
            b.btnRollback.visibility = if (isRollbackCandidate) View.VISIBLE else View.GONE
            b.btnRollback.setOnClickListener { onRollback(e) }
        }
    }
}
