package com.cabgon.blackhawk.ui

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.cabgon.blackhawk.BuildConfig
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.ai.update.AppUpdateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

class UpdateRequiredActivity : AppCompatActivity() {

    private lateinit var tvTitle: TextView
    private lateinit var tvBody: TextView
    private lateinit var tvNotes: TextView
    private lateinit var tvProgress: TextView
    private lateinit var tvStage: TextView
    private lateinit var tvDetail: TextView
    private lateinit var tvCurrentVersion: TextView
    private lateinit var tvAvailableVersion: TextView
    private lateinit var tvPackageSize: TextView
    private lateinit var btnReleaseNotes: Button
    private lateinit var btnDownload: Button
    private lateinit var btnCancel: Button
    private lateinit var pb: ProgressBar

    private var currentApk: AppUpdateManager.ApkSpec? = null
    private var pendingPermissionApk: AppUpdateManager.ApkSpec? = null
    private var pendingInstall = false

    private val unknownSourcesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val apk = pendingPermissionApk ?: return@registerForActivityResult
        if (packageManager.canRequestPackageInstalls()) {
            pendingPermissionApk = null
            if (pendingInstall) {
                if (!AppUpdateManager.promptInstallFromLastDownloaded(this)) {
                    tvStage.setText(R.string.update_stage_error)
                    tvDetail.setText(R.string.update_error_install)
                }
            } else {
                launchDownload(apk)
            }
        } else {
            tvStage.setText(R.string.update_stage_permission)
            tvDetail.setText(R.string.update_error_permission)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update_required)
        bindViews()
        configureInitialState()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })

        btnReleaseNotes.setOnClickListener {
            val expanded = tvNotes.isVisible
            tvNotes.isVisible = !expanded
            btnReleaseNotes.setText(
                if (expanded) R.string.update_show_notes else R.string.update_hide_notes
            )
        }
        btnDownload.setOnClickListener {
            val apk = currentApk ?: return@setOnClickListener
            if (pendingInstall) {
                if (!packageManager.canRequestPackageInstalls()) {
                    pendingPermissionApk = apk
                    tvStage.setText(R.string.update_stage_permission)
                    tvDetail.setText(R.string.update_detail_permission)
                    unknownSourcesLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = "package:$packageName".toUri()
                    })
                } else if (!AppUpdateManager.promptInstallFromLastDownloaded(this)) {
                    tvStage.setText(R.string.update_stage_error)
                    tvDetail.setText(R.string.update_error_install)
                }
            } else {
                startDownload(apk)
            }
        }
        btnCancel.setOnClickListener { cancelDownload() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                UpdateDownloadEventBus.events.collect { renderEvent(it) }
            }
        }
        renderExistingDownloadState()
        loadUpdateInfo()
    }

    private fun bindViews() {
        tvTitle = findViewById(R.id.tvUpdateTitle)
        tvBody = findViewById(R.id.tvUpdateBody)
        tvNotes = findViewById(R.id.tvUpdateNotes)
        tvProgress = findViewById(R.id.tvUpdateProgress)
        tvStage = findViewById(R.id.tvUpdateStage)
        tvDetail = findViewById(R.id.tvUpdateDetail)
        tvCurrentVersion = findViewById(R.id.tvCurrentVersion)
        tvAvailableVersion = findViewById(R.id.tvAvailableVersion)
        tvPackageSize = findViewById(R.id.tvPackageSize)
        btnReleaseNotes = findViewById(R.id.btnReleaseNotes)
        btnDownload = findViewById(R.id.btnDownloadInstall)
        btnCancel = findViewById(R.id.btnCancelDownload)
        pb = findViewById(R.id.pbUpdate)
    }

    private fun configureInitialState() {
        pb.isIndeterminate = false
        pb.progress = 0
        tvProgress.visibility = View.GONE
        tvNotes.visibility = View.GONE
        btnDownload.isEnabled = false
        btnCancel.visibility = View.GONE
        btnReleaseNotes.visibility = View.GONE
        tvStage.setText(R.string.update_stage_checking)
        tvDetail.setText(R.string.update_detail_checking)
    }

    private fun loadUpdateInfo() {
        lifecycleScope.launch {
            try {
                val check = withTimeout(15_000L) {
                    AppUpdateManager.check(applicationContext)
                }
                val apk = check.apkSpec ?: error(getString(R.string.update_error_config))
                currentApk = apk
                pendingInstall = AppUpdateManager.hasPendingInstall(this@UpdateRequiredActivity, apk.versionCode)
                tvCurrentVersion.text = getString(
                    R.string.update_version_value,
                    BuildConfig.VERSION_NAME,
                    check.currentVersionCode
                )
                tvAvailableVersion.text = getString(
                    R.string.update_version_code_value,
                    apk.versionCode
                )
                tvPackageSize.text = formatDataSize(apk.bytes ?: 0L)
                tvTitle.setText(R.string.update_title)
                tvBody.setText(R.string.update_body)
                if (pendingInstall) {
                    tvStage.setText(R.string.update_stage_ready)
                    tvDetail.setText(R.string.update_detail_ready)
                    btnDownload.setText(R.string.update_action_install)
                } else {
                    tvStage.setText(R.string.update_stage_available)
                    tvDetail.setText(R.string.update_detail_available)
                    btnDownload.setText(R.string.update_action_download)
                }
                btnDownload.isEnabled = true
                if (!apk.releaseNotes.isNullOrBlank()) {
                    tvNotes.text = apk.releaseNotes
                    btnReleaseNotes.visibility = View.VISIBLE
                } else {
                    btnReleaseNotes.visibility = View.GONE
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentApk = null
                btnDownload.isEnabled = false
                tvStage.setText(R.string.update_stage_error)
                tvDetail.text = error.message ?: getString(R.string.update_error_config)
            }
        }
    }

    private fun renderExistingDownloadState() {
        if (!UpdateDownloadState.active) return
        btnDownload.isEnabled = false
        btnCancel.visibility = View.VISIBLE
        btnCancel.isEnabled = true
        tvStage.setText(R.string.update_stage_downloading)
        val percent = UpdateDownloadState.percent
        if (percent in 0..100) {
            pb.progress = percent
            tvProgress.visibility = View.VISIBLE
            tvProgress.text = getString(R.string.update_percent, percent)
        }
    }

    private fun renderEvent(event: UpdateDownloadEvent) {
        when (event) {
            is UpdateDownloadEvent.Progress -> {
                btnDownload.isEnabled = false
                btnCancel.visibility = View.VISIBLE
                btnCancel.isEnabled = true
                tvStage.setText(R.string.update_stage_downloading)
                if (event.percent in 0..100) {
                    pb.isIndeterminate = false
                    pb.progress = event.percent
                    tvProgress.visibility = View.VISIBLE
                    tvProgress.text = getString(R.string.update_percent, event.percent)
                } else {
                    pb.isIndeterminate = true
                    tvProgress.visibility = View.GONE
                }
                tvDetail.setText(R.string.update_detail_downloading)
            }
            is UpdateDownloadEvent.Finished -> {
                btnCancel.visibility = View.GONE
                btnCancel.isEnabled = false
                btnDownload.isEnabled = !event.success && currentApk != null
                if (event.success) {
                    pb.isIndeterminate = false
                    pb.progress = 100
                    tvStage.setText(R.string.update_stage_installing)
                    tvDetail.setText(R.string.update_detail_installing)
                    tvProgress.visibility = View.VISIBLE
                    tvProgress.text = getString(R.string.update_percent, 100)
                    pendingInstall = true
                    btnDownload.setText(R.string.update_action_install)
                    btnDownload.isEnabled = true
                    tvStage.setText(R.string.update_stage_ready)
                    tvDetail.setText(R.string.update_detail_ready)
                } else {
                    tvStage.setText(R.string.update_stage_error)
                    tvDetail.setText(R.string.update_error_download)
                }
            }
        }
    }

    private fun startDownload(apk: AppUpdateManager.ApkSpec) {
        lifecycleScope.launch {
            if (isCellularConnection() && !confirmCellularDownload(apk.bytes ?: 0L)) return@launch
            launchDownload(apk)
        }
    }

    private fun launchDownload(apk: AppUpdateManager.ApkSpec) {
        if (!packageManager.canRequestPackageInstalls()) {
            pendingPermissionApk = apk
            tvStage.setText(R.string.update_stage_permission)
            tvDetail.setText(R.string.update_detail_permission)
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = "package:$packageName".toUri()
            }
            unknownSourcesLauncher.launch(intent)
            return
        }
        pb.isIndeterminate = true
        pb.progress = 0
        tvProgress.visibility = View.GONE
        tvStage.setText(R.string.update_stage_preparing)
        tvDetail.setText(R.string.update_detail_preparing)
        btnDownload.isEnabled = false
        btnCancel.visibility = View.VISIBLE
        btnCancel.isEnabled = true
        UpdateDownloadService.start(this, apk)
    }

    private fun cancelDownload() {
        UpdateDownloadService.cancel(this)
        btnCancel.isEnabled = false
        tvStage.setText(R.string.update_stage_cancelling)
        tvDetail.setText(R.string.update_detail_cancelling)
    }

    private fun isCellularConnection(): Boolean = runCatching {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return@runCatching false
        val caps = cm.getNetworkCapabilities(network) ?: return@runCatching false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }.getOrDefault(false)

    private suspend fun confirmCellularDownload(bytes: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            var dialog: AlertDialog? = null
            runOnUiThread {
                dialog = AlertDialog.Builder(this)
                    .setTitle(R.string.update_cellular_title)
                    .setMessage(
                        if (bytes > 0L) getString(R.string.update_cellular_message, formatDataSize(bytes))
                        else getString(R.string.update_cellular_message_unknown)
                    )
                    .setNegativeButton(R.string.update_cellular_wait) { _, _ ->
                        if (cont.isActive) cont.resume(false)
                    }
                    .setPositiveButton(R.string.update_cellular_confirm) { _, _ ->
                        if (cont.isActive) cont.resume(true)
                    }
                    .setOnCancelListener { if (cont.isActive) cont.resume(false) }
                    .create()
                    .also {
                        it.setCanceledOnTouchOutside(false)
                        it.show()
                    }
            }
            cont.invokeOnCancellation { runOnUiThread { dialog?.dismiss() } }
        }

    private fun formatDataSize(bytes: Long): String = when {
        bytes < 1024L -> getString(R.string.update_size_bytes, bytes)
        bytes < 1024L * 1024L -> getString(R.string.update_size_kb, bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> getString(R.string.update_size_mb, bytes / (1024.0 * 1024.0))
        else -> getString(R.string.update_size_gb, bytes / (1024.0 * 1024.0 * 1024.0))
    }

    override fun onStart() {
        super.onStart()
        UpdateDownloadVisibility.isUpdateActivityVisible = true
        renderExistingDownloadState()
    }

    override fun onStop() {
        UpdateDownloadVisibility.isUpdateActivityVisible = false
        super.onStop()
    }
}
