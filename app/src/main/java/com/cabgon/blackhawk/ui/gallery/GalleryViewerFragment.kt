package com.cabgon.blackhawk.ui.gallery

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.cabgon.blackhawk.databinding.FragmentGalleryViewerBinding
import kotlinx.coroutines.launch
import java.io.Serializable
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(UnstableApi::class)
class GalleryViewerFragment : Fragment() {

    private var _b: FragmentGalleryViewerBinding? = null
    private val b get() = _b!!
    private val repository = GalleryRepository()

    private var player: ExoPlayer? = null
    private var currentVideoUrl: String? = null
    private var currentIsVideo: Boolean = false

    private var playbackPositionMs: Long = 0L
    private var playbackWhenReady: Boolean = false

    private val sizeFmt = DecimalFormat("0.0")
    private val dateFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentGalleryViewerBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val item = readSerializableArg<GalleryItemSerializable>(ARG_ITEM)
            ?: run {
                Toast.makeText(requireContext(), "No se pudo abrir el elemento.", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
                return
            }

        bindMetadata(item)

        b.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        b.btnFullscreen.setOnClickListener {
            val url = currentVideoUrl ?: return@setOnClickListener
            capturePlaybackState()
            startActivity(
                Intent(requireContext(), GalleryVideoFullscreenActivity::class.java).apply {
                    putExtra(GalleryVideoFullscreenActivity.EXTRA_VIDEO_URL, url)
                    putExtra(GalleryVideoFullscreenActivity.EXTRA_POSITION_MS, playbackPositionMs)
                    putExtra(GalleryVideoFullscreenActivity.EXTRA_PLAY_WHEN_READY, playbackWhenReady)
                }
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                currentIsVideo = item.mediaType == GalleryItem.TYPE_VIDEO
                b.progressViewer.isVisible = true
                b.imageView.tag = item.id

                if (currentIsVideo) {
                    b.imageView.isVisible = true
                    b.playerView.isVisible = false
                    b.btnFullscreen.isVisible = true

                    runCatching {
                        repository.loadPreviewInto(
                            imageView = b.imageView,
                            item = item.toGalleryItem()
                        )
                    }

                    if (!isAdded || _b == null) return@launch

                    val url = repository.resolveDownloadUrl(item.storagePath)
                    currentVideoUrl = url

                    if (!isAdded || _b == null) return@launch
                    b.playerView.isVisible = true
                    bindPlayer(url, playbackPositionMs, playbackWhenReady)
                } else {
                    b.playerView.isVisible = false
                    b.btnFullscreen.isVisible = false
                    b.imageView.isVisible = true

                    runCatching {
                        repository.loadPreviewInto(
                            imageView = b.imageView,
                            item = item.toGalleryItem()
                        )
                    }

                    repository.loadViewerImageInto(
                        context = requireContext(),
                        imageView = b.imageView,
                        item = item.toGalleryItem()
                    )
                    if (!isAdded || _b == null) return@launch
                    b.progressViewer.isVisible = false
                }
            } catch (e: Exception) {
                if (!isAdded || _b == null) return@launch
                b.progressViewer.isVisible = false
                Toast.makeText(requireContext(), e.localizedMessage ?: "No se pudo cargar el archivo.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun bindMetadata(item: GalleryItemSerializable) {
        b.txtTitle.text = item.title.ifBlank { "Sin ubicacion" }
        b.txtSubtitle.text = buildString {
            append("Autor: ")
            append(item.uploaderName.ifBlank { "Sin autor" })
        }
        b.txtDesc.text = item.description.ifBlank { "Sin descripcion operativa registrada." }
        b.txtMediaBadge.text = if (item.mediaType == GalleryItem.TYPE_VIDEO) {
            if (item.durationSec > 0) "VIDEO • ${item.durationSec}s" else "VIDEO"
        } else {
            "FOTO"
        }

        val aircraft = item.aircraftMatricula.trim()
        b.txtAircraftBadge.isVisible = aircraft.isNotEmpty()
        b.txtAircraftBadge.text = aircraft

        val metaParts = mutableListOf<String>()
        val dateMs = item.approvedAtEpochMs ?: item.createdAtEpochMs
        if (dateMs != null && dateMs > 0L) {
            metaParts += dateFmt.format(Date(dateMs))
        }
        if (item.sizeBytes > 0L) {
            metaParts += "${sizeFmt.format(item.sizeBytes / 1024f / 1024f)} MB"
        }
        if (item.packageScope.isNotBlank()) {
            metaParts += item.packageScope
        }
        b.txtMeta.text = if (metaParts.isEmpty()) "Sin metadatos adicionales" else metaParts.joinToString(" • ")
    }

    override fun onStart() {
        super.onStart()
        val url = currentVideoUrl
        if (currentIsVideo && !url.isNullOrBlank() && player == null && _b != null) {
            b.progressViewer.isVisible = true
            b.imageView.isVisible = true
            b.playerView.isVisible = true
            b.btnFullscreen.isVisible = true
            bindPlayer(url, playbackPositionMs, playbackWhenReady)
        }
    }

    private fun bindPlayer(url: String, startPositionMs: Long, rememberedPlayWhenReady: Boolean) {
        releasePlayer()

        b.playerView.apply {
            setUseController(true)
            controllerAutoShow = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }

        val shouldAutoplay = if (startPositionMs <= 0L) true else rememberedPlayWhenReady

        val exo = ExoPlayer.Builder(requireContext()).build().also { created ->
            created.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (_b == null) return
                    when (playbackState) {
                        Player.STATE_BUFFERING -> b.progressViewer.isVisible = true
                        Player.STATE_READY -> {
                            b.progressViewer.isVisible = false
                            b.imageView.isVisible = false
                        }
                        Player.STATE_ENDED -> b.progressViewer.isVisible = false
                        Player.STATE_IDLE -> b.progressViewer.isVisible = true
                    }
                }
            })

            b.playerView.player = created
            created.setMediaItem(MediaItem.fromUri(url))
            created.prepare()
            if (startPositionMs > 0L) {
                created.seekTo(startPositionMs)
            }
            created.playWhenReady = shouldAutoplay
        }
        player = exo
    }

    private fun capturePlaybackState() {
        val p = player ?: return
        playbackPositionMs = p.currentPosition.coerceAtLeast(0L)
        playbackWhenReady = p.playWhenReady
    }

    override fun onStop() {
        capturePlaybackState()
        super.onStop()
        releasePlayer()
    }

    override fun onDestroyView() {
        capturePlaybackState()
        releasePlayer()
        _b = null
        super.onDestroyView()
    }

    private fun releasePlayer() {
        _b?.playerView?.player = null
        player?.release()
        player = null
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T : Serializable> readSerializableArg(key: String): T? {
        val args = arguments ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            args.getSerializable(key, T::class.java)
        } else {
            args.getSerializable(key) as? T
        }
    }

    companion object {
        private const val ARG_ITEM = "gallery_item"

        fun newInstance(item: GalleryItem): GalleryViewerFragment {
            return GalleryViewerFragment().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_ITEM, GalleryItemSerializable.from(item))
                }
            }
        }
    }
}

private data class GalleryItemSerializable(
    val id: String,
    val title: String,
    val description: String,
    val mediaType: String,
    val storagePath: String,
    val thumbPath: String?,
    val displayPath: String?,
    val uploaderName: String,
    val packageScope: String,
    val aircraftMatricula: String,
    val durationSec: Int,
    val sizeBytes: Long,
    val createdAtEpochMs: Long?,
    val approvedAtEpochMs: Long?
) : Serializable {
    fun toGalleryItem(): GalleryItem {
        return GalleryItem(
            id = id,
            status = GalleryItem.STATUS_APPROVED,
            mediaType = mediaType,
            storagePath = storagePath,
            thumbPath = thumbPath,
            displayPath = displayPath,
            uploaderUid = "",
            uploaderName = uploaderName,
            uploaderEmail = "",
            title = title,
            description = description,
            packageScope = packageScope,
            aircraftMatricula = aircraftMatricula,
            durationSec = durationSec,
            sizeBytes = sizeBytes,
            mimeType = "",
            createdAt = null,
            updatedAt = null,
            approvedAt = null,
            approvedByUid = null,
            approvedByName = null,
            rejectedAt = null,
            rejectedByUid = null,
            rejectedByName = null,
            rejectReason = null,
            isDeleted = false
        )
    }

    companion object {
        fun from(item: GalleryItem) = GalleryItemSerializable(
            id = item.id,
            title = item.title,
            description = item.description,
            mediaType = item.mediaType,
            storagePath = item.storagePath,
            thumbPath = item.thumbPath,
            displayPath = item.displayPath,
            uploaderName = item.uploaderName,
            packageScope = item.packageScope,
            aircraftMatricula = item.aircraftMatricula,
            durationSec = item.durationSec,
            sizeBytes = item.sizeBytes,
            createdAtEpochMs = item.createdAt?.toDate()?.time,
            approvedAtEpochMs = item.approvedAt?.toDate()?.time
        )
    }
}
