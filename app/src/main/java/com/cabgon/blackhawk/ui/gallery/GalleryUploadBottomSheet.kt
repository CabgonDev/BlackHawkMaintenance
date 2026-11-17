package com.cabgon.blackhawk.ui.gallery

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.data.user.UserSessionStore
import com.cabgon.blackhawk.databinding.BottomsheetGalleryUploadBinding
import com.cabgon.blackhawk.util.Roles
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch
import java.io.File

class GalleryUploadBottomSheet : BottomSheetDialogFragment() {

    private var _b: BottomsheetGalleryUploadBinding? = null
    private val b get() = _b!!

    private val repository = GalleryRepository()
    private var preparedUpload: GalleryMediaValidator.PreparedUpload? = null
    private var tempVideoUri: Uri? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        prepareFromUri(uri, isVideo = false)
    }

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        prepareFromUri(uri, isVideo = true)
    }

    private val capturePhoto = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp: Bitmap? ->
        if (bmp == null) return@registerForActivityResult
        runCatching { GalleryMediaValidator.prepareImageFromBitmap(bmp) }
            .onSuccess {
                preparedUpload = it
                renderPrepared(it)
            }
            .onFailure { showError(it) }
    }

    private val captureVideo = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = res.data?.data ?: tempVideoUri ?: return@registerForActivityResult
        prepareFromUri(uri, isVideo = true)
    }

    override fun getTheme(): Int = R.style.App_BottomSheetTheme

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = BottomsheetGalleryUploadBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.btnPickImage.setOnClickListener { pickImage.launch("image/*") }
        b.btnTakePhoto.setOnClickListener { capturePhoto.launch(null) }
        b.btnPickVideo.setOnClickListener { pickVideo.launch("video/*") }
        b.btnRecordVideo.setOnClickListener { launchVideoCapture() }
        b.btnUpload.setOnClickListener { uploadNow() }
        b.btnCloseSheet.setOnClickListener { dismissAllowingStateLoss() }
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        bottomSheet.layoutParams = bottomSheet.layoutParams.apply {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        BottomSheetBehavior.from(bottomSheet).apply {
            isFitToContents = true
            skipCollapsed = true
            expandedOffset = 0
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun prepareFromUri(uri: Uri, isVideo: Boolean) {
        b.progress.isVisible = true
        b.btnUpload.isEnabled = false
        b.txtSelected.text = if (isVideo) "Analizando video..." else "Analizando imagen..."

        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                if (isVideo) {
                    GalleryMediaValidator.prepareVideoFromUri(requireContext(), uri) { progressText ->
                        if (isAdded && _b != null) {
                            b.txtSelected.text = progressText
                        }
                    }
                } else {
                    GalleryMediaValidator.prepareImageFromUri(requireContext(), uri)
                }
            }.onSuccess {
                preparedUpload = it
                renderPrepared(it)
                b.progress.isVisible = false
            }.onFailure {
                b.progress.isVisible = false
                b.txtSelected.text = getString(R.string.sin_archivo_seleccionado)
                showError(it)
            }
        }
    }

    private fun launchVideoCapture() {
        val outFile = File(requireContext().cacheDir, "gallery_video_${System.currentTimeMillis()}.mp4")
        tempVideoUri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            outFile
        )
        val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_DURATION_LIMIT, 30)
            putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 0)
            putExtra(MediaStore.EXTRA_OUTPUT, tempVideoUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        captureVideo.launch(intent)
    }

    private fun renderPrepared(prepared: GalleryMediaValidator.PreparedUpload) {
        val isVideo = prepared.mediaType == GalleryItem.TYPE_VIDEO

        val defaultTint = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.primary))
        val thumb = prepared.thumbBytes
        if (thumb != null) {
            val bmp = BitmapFactory.decodeByteArray(thumb, 0, thumb.size)
            if (bmp != null) {
                b.previewIcon.imageTintList = null
                b.previewIcon.setImageBitmap(bmp)
            } else {
                b.previewIcon.imageTintList = defaultTint
                b.previewIcon.setImageResource(
                    if (isVideo) R.drawable.ic_gallery_video else R.drawable.ic_gallery_image
                )
            }
        } else {
            b.previewIcon.imageTintList = defaultTint
            b.previewIcon.setImageResource(
                if (isVideo) R.drawable.ic_gallery_video else R.drawable.ic_gallery_image
            )
        }

        b.txtSelected.text = buildString {
            append(if (isVideo) "Video listo" else "Imagen lista")
            if (prepared.displayName.isNotBlank()) append(" • ${prepared.displayName}")
            if (isVideo && prepared.durationSec > 0) append(" • ${prepared.durationSec}s")
            if (prepared.sizeBytes > 0L) append(" • ${"%.1f".format(prepared.sizeBytes / 1024f / 1024f)} MB")
        }

        b.btnUpload.isEnabled = true
    }

    private fun uploadNow() {
        val prepared = preparedUpload
            ?: return showError(IllegalStateException("Selecciona una imagen o video primero."))

        val profile = UserSessionStore(requireContext()).getProfile()
            ?: return showError(IllegalStateException("No se encontro la sesion del usuario."))

        val location = b.edtTitle.text?.toString().orEmpty().trim()
        val activityDescription = b.edtDescription.text?.toString().orEmpty().trim()
        val aircraftMatricula = b.edtMatricula.text?.toString().orEmpty().trim()

        if (location.isBlank()) {
            b.edtTitle.requestFocus()
            return showError(IllegalStateException("La ubicacion es obligatoria."))
        }

        if (activityDescription.isBlank()) {
            b.edtDescription.requestFocus()
            return showError(IllegalStateException("La descripcion o actividad es obligatoria."))
        }

        b.progress.isVisible = true
        b.btnUpload.isEnabled = false
        b.txtSelected.text = "Subiendo archivo..."

        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                repository.uploadPending(
                    context = requireContext(),
                    prepared = prepared,
                    title = location,
                    description = activityDescription,
                    aircraftMatricula = aircraftMatricula,
                    uploader = profile
                )
            }.onSuccess { result ->
                val msg = when (result.status) {
                    GalleryItem.STATUS_APPROVED -> "Contenido publicado."
                    GalleryItem.STATUS_PENDING_ADMIN -> "Contenido enviado a aprobacion superior."
                    else -> if (Roles.isAtLeast(profile.role, Roles.MODERATOR)) {
                        "Contenido enviado a revision interna."
                    } else {
                        "Contenido enviado a aprobacion"
                    }
                }
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                parentFragmentManager.setFragmentResult(REQUEST_KEY, Bundle.EMPTY)
                dismissAllowingStateLoss()
            }.onFailure {
                showError(it)
                b.progress.isVisible = false
                b.btnUpload.isEnabled = true
                renderPrepared(prepared)
            }
        }
    }

    private fun showError(t: Throwable) {
        Toast.makeText(
            requireContext(),
            t.localizedMessage ?: "Operacion no disponible.",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    companion object {
        const val TAG = "GalleryUploadBottomSheet"
        const val REQUEST_KEY = "gallery_upload_done"
    }
}
