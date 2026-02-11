package com.cabgon.blackhawk.ui.admin.users

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cabgon.blackhawk.databinding.RowUserAdminBinding
import com.cabgon.blackhawk.databinding.RowUserRequestBinding

class UsersAdapter(
    private val mode: UsersListFragment.Mode,
    private val onUserClick: (AdminUserItem) -> Unit,
    private val onApprove: (AdminUserItem) -> Unit,
    private val onReject: (AdminUserItem) -> Unit
) : ListAdapter<AdminUserItem, RecyclerView.ViewHolder>(DIFF) {

    override fun getItemViewType(position: Int): Int {
        return if (mode == UsersListFragment.Mode.SOLICITUDES) 1 else 0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == 1) {
            val b = RowUserRequestBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            RequestVH(b, onApprove, onReject)
        } else {
            val b = RowUserAdminBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            AdminVH(b, onUserClick)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is RequestVH -> holder.bind(item)
            is AdminVH -> holder.bind(item)
        }
    }

    class AdminVH(
        private val b: RowUserAdminBinding,
        private val onClick: (AdminUserItem) -> Unit
    ) : RecyclerView.ViewHolder(b.root) {

        fun bind(u: AdminUserItem) {
            b.txtName.text = u.nombre
            b.txtSub.text = "${u.role} · ${u.email}"
            b.root.setOnClickListener { onClick(u) }
        }
    }

    class RequestVH(
        private val b: RowUserRequestBinding,
        private val onApprove: (AdminUserItem) -> Unit,
        private val onReject: (AdminUserItem) -> Unit
    ) : RecyclerView.ViewHolder(b.root) {

        fun bind(u: AdminUserItem) {
            b.txtName.text = u.nombre
            b.txtSub.text = u.email
            b.btnApprove.setOnClickListener { onApprove(u) }
            b.btnReject.setOnClickListener { onReject(u) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AdminUserItem>() {
            override fun areItemsTheSame(oldItem: AdminUserItem, newItem: AdminUserItem) = oldItem.uid == newItem.uid
            override fun areContentsTheSame(oldItem: AdminUserItem, newItem: AdminUserItem) = oldItem == newItem
        }
    }
}
