package com.cabgon.blackhawk.ui.admin.gallery

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.cabgon.blackhawk.databinding.ItemAdminGalleryBinding
import com.cabgon.blackhawk.ui.gallery.GalleryItem
import java.text.SimpleDateFormat
import java.util.Locale

class AdminGalleryAdapter(
    private val canDelete: Boolean,
    private val onOpen: (GalleryItem) -> Unit,
    private val onApprove: (GalleryItem) -> Unit,
    private val onReject: (GalleryItem) -> Unit,
    private val onDelete: (GalleryItem) -> Unit
) : RecyclerView.Adapter<AdminGalleryAdapter.AdminGalleryVH>() {

    private val items = mutableListOf<GalleryItem>()
    private val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

    fun submit(list: List<GalleryItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AdminGalleryVH {
        val inflater = LayoutInflater.from(parent.context)
        return AdminGalleryVH(ItemAdminGalleryBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: AdminGalleryVH, position: Int) {
        val item = items[position]
        val b = holder.b

        b.txtTitle.text = item.title.ifBlank {
            if (item.mediaType == GalleryItem.TYPE_VIDEO) "Video" else "Imagen"
        }

        b.txtMeta.text = buildString {
            append(item.uploaderName.ifBlank { item.uploaderEmail.ifBlank { "Usuario" } })
            if (item.packageScope.isNotBlank()) append(" • ${item.packageScope}")
            if (item.aircraftMatricula.isNotBlank()) append(" • ${item.aircraftMatricula}")
            item.createdAt?.toDate()?.let { append(" • ${fmt.format(it)}") }
        }

        b.txtDesc.text = buildString {
            append(
                item.description.ifBlank {
                    if (item.mediaType == GalleryItem.TYPE_VIDEO) "Video ${item.durationSec}s" else "Imagen"
                }
            )
            append(" • ")
            append(
                when (item.status) {
                    GalleryItem.STATUS_PENDING_MODERATOR -> "Pendiente moderador"
                    GalleryItem.STATUS_PENDING_ADMIN -> "Pendiente admin"
                    GalleryItem.STATUS_APPROVED -> "Aprobado"
                    GalleryItem.STATUS_REJECTED -> "Rechazado"
                    else -> item.status
                }
            )
        }

        b.btnOpen.setOnClickListener { onOpen(item) }
        b.btnApprove.setOnClickListener { onApprove(item) }
        b.btnReject.setOnClickListener { onReject(item) }
        b.btnDelete.setOnClickListener { onDelete(item) }

        val pending = GalleryItem.isPendingStatus(item.status)
        b.btnApprove.isEnabled = pending
        b.btnReject.isEnabled = pending
        b.btnDelete.isVisible = canDelete
    }

    override fun getItemCount(): Int = items.size

    class AdminGalleryVH(val b: ItemAdminGalleryBinding) : RecyclerView.ViewHolder(b.root)
}