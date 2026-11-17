package com.cabgon.blackhawk.ui.gallery

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.databinding.ItemGalleryBinding
import com.cabgon.blackhawk.databinding.ItemGalleryFeaturedBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Locale

class GalleryAdapter(
    private val repository: GalleryRepository,
    private val onClick: (GalleryItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<GalleryItem>()
    private val dateFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    private val sizeFmt = DecimalFormat("0.0")

    fun submit(list: List<GalleryItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == 0 && items.isNotEmpty()) TYPE_FEATURED else TYPE_STANDARD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_FEATURED -> FeaturedVH(ItemGalleryFeaturedBinding.inflate(inflater, parent, false))
            else -> StandardVH(ItemGalleryBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is FeaturedVH -> holder.bind(item)
            is StandardVH -> holder.bind(item)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is FeaturedVH -> holder.clear()
            is StandardVH -> holder.clear()
        }
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = items.size

    private fun defaultTitle(item: GalleryItem): String {
        return item.title.ifBlank {
            if (item.mediaType == GalleryItem.TYPE_VIDEO) "Video operativo" else "Fotografía operativa"
        }
    }

    private fun subtitle(item: GalleryItem): String {
        return item.uploaderName.ifBlank { item.uploaderEmail.ifBlank { "Usuario del escuadrón" } }
    }

    private fun description(item: GalleryItem): String {
        return item.description.ifBlank {
            if (item.mediaType == GalleryItem.TYPE_VIDEO) {
                "Registro en video • ${item.durationSec}s • ${humanSize(item.sizeBytes)}"
            } else {
                "Registro fotográfico • ${humanSize(item.sizeBytes)}"
            }
        }
    }

    private fun meta(item: GalleryItem): String {
        val dateText = item.approvedAt?.toDate()?.let(dateFmt::format)
            ?: item.createdAt?.toDate()?.let(dateFmt::format)
            ?: "Sin fecha"
        return "$dateText • ${humanSize(item.sizeBytes)}"
    }

    private fun mediaBadge(item: GalleryItem): String {
        return if (item.mediaType == GalleryItem.TYPE_VIDEO) {
            "VIDEO • ${item.durationSec}s"
        } else {
            "FOTO"
        }
    }

    private fun humanSize(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        return "${sizeFmt.format(bytes / 1024f / 1024f)} MB"
    }

    private abstract inner class BaseVH : RecyclerView.ViewHolder {
        protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private var previewJob: Job? = null

        constructor(root: View) : super(root)

        protected fun bindCommon(
            item: GalleryItem,
            imageView: ImageView,
            titleView: TextView,
            subtitleView: TextView,
            descView: TextView,
            metaView: TextView,
            mediaBadgeView: TextView,
            aircraftBadgeView: TextView,
            container: View
        ) {
            titleView.text = defaultTitle(item)
            subtitleView.text = subtitle(item)
            descView.text = description(item)
            descView.isVisible = descView.text.isNotBlank()
            metaView.text = meta(item)
            mediaBadgeView.text = mediaBadge(item)

            val aircraft = item.aircraftMatricula.trim()
            aircraftBadgeView.isVisible = aircraft.isNotEmpty()
            aircraftBadgeView.text = aircraft

            imageView.setImageResource(
                if (item.mediaType == GalleryItem.TYPE_VIDEO) R.drawable.ic_gallery_video
                else R.drawable.ic_gallery_image
            )
            imageView.tag = item.id

            previewJob?.cancel()
            previewJob = scope.launch {
                runCatching { repository.loadPreviewInto(imageView, item) }
            }

            container.setOnClickListener { onClick(item) }
        }

        open fun clear() {
            previewJob?.cancel()
            previewJob = null
        }
    }

    private inner class StandardVH(val b: ItemGalleryBinding) : BaseVH(b.root) {
        fun bind(item: GalleryItem) {
            bindCommon(
                item = item,
                imageView = b.imgPreview,
                titleView = b.txtTitle,
                subtitleView = b.txtSubtitle,
                descView = b.txtDesc,
                metaView = b.txtMeta,
                mediaBadgeView = b.txtMediaBadge,
                aircraftBadgeView = b.txtAircraftBadge,
                container = b.root
            )
        }
    }

    private inner class FeaturedVH(val b: ItemGalleryFeaturedBinding) : BaseVH(b.root) {
        fun bind(item: GalleryItem) {
            bindCommon(
                item = item,
                imageView = b.imgPreview,
                titleView = b.txtTitle,
                subtitleView = b.txtSubtitle,
                descView = b.txtDesc,
                metaView = b.txtMeta,
                mediaBadgeView = b.txtMediaBadge,
                aircraftBadgeView = b.txtAircraftBadge,
                container = b.root
            )
        }
    }

    companion object {
        private const val TYPE_FEATURED = 1
        private const val TYPE_STANDARD = 2
    }
}
