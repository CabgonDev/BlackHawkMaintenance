package com.cabgon.blackhawk.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.ai.update.AppUpdateManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class UpdateRequiredActivity : AppCompatActivity() {

    private lateinit var tvTitle: TextView
    private lateinit var tvBody: TextView
    private lateinit var tvNotes: TextView
    private lateinit var tvProgress: TextView
    private lateinit var btnDownload: Button
    private lateinit var btnCancel: Button
    private lateinit var pb: ProgressBar

    private var currentApk: AppUpdateManager.ApkSpec? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update_required)

        tvTitle = findViewById(R.id.tvUpdateTitle)
        tvBody = findViewById(R.id.tvUpdateBody)
        tvNotes = findViewById(R.id.tvUpdateNotes)
        tvProgress = findViewById(R.id.tvUpdateProgress)
        btnDownload = findViewById(R.id.btnDownloadInstall)
        btnCancel = findViewById(R.id.btnCancelDownload)
        pb = findViewById(R.id.pbUpdate)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // No dejamos salir al usuario, solo mandamos la app al background.
                moveTaskToBack(true)
            }
        })

        // Estado inicial
        pb.visibility = View.GONE
        pb.isIndeterminate = false
        pb.progress = 0
        tvNotes.visibility = View.GONE
        tvProgress.visibility = View.GONE
        btnCancel.visibility = View.VISIBLE
        btnCancel.isEnabled = false

        // Escuchar eventos del servicio (progreso / fin)
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                UpdateDownloadEventBus.events.collectLatest { event ->
                    when (event) {
                        is UpdateDownloadEvent.Progress -> {
                            val percent = event.percent
                            if (percent in 0..100) {
                                pb.visibility = View.VISIBLE
                                pb.isIndeterminate = false
                                pb.progress = percent
                                tvProgress.visibility = View.VISIBLE
                                tvProgress.text =
                                    "Descargando actualización... $percent%"
                            } else {
                                pb.visibility = View.VISIBLE
                                pb.isIndeterminate = true
                                tvProgress.visibility = View.VISIBLE
                                tvProgress.text = "Descargando actualización..."
                            }
                        }

                        is UpdateDownloadEvent.Finished -> {
                            pb.visibility = View.GONE
                            btnDownload.isEnabled = true
                            btnCancel.isEnabled = false

                            if (event.success) {
                                tvProgress.visibility = View.VISIBLE
                                tvProgress.text = "Descarga completa. Abriendo instalador..."
                                Toast.makeText(
                                    this@UpdateRequiredActivity,
                                    "Se abrió el instalador. Confirma la instalación.",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                tvProgress.visibility = View.VISIBLE
                                tvProgress.text = "Descarga cancelada o fallida."
                                Toast.makeText(
                                    this@UpdateRequiredActivity,
                                    "La descarga fue cancelada o falló.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
            }
        }

        // Cargar info de actualización y configurar botones
        lifecycleScope.launch {
            val check = AppUpdateManager.check(applicationContext)

            tvTitle.text = "Actualización requerida"
            tvBody.text =
                "Tu versión actual (${check.currentVersionCode}) es menor que la mínima requerida (${check.minAppVersionCode}).\n\nDebes actualizar para continuar."

            currentApk = check.apkSpec

            val apk = currentApk
            if (apk?.releaseNotes != null) {
                tvNotes.visibility = View.VISIBLE
                tvNotes.text = "Notas de la versión:\n${apk.releaseNotes}"
            }

            btnDownload.setOnClickListener {
                val selectedApk = currentApk
                if (selectedApk == null) {
                    Toast.makeText(
                        this@UpdateRequiredActivity,
                        "No hay APK configurado en el canal.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }

                startDownload(selectedApk)
            }

            btnCancel.setOnClickListener {
                UpdateDownloadService.cancel(this@UpdateRequiredActivity)
                pb.isIndeterminate = false
                pb.progress = 0
                pb.visibility = View.GONE
                tvProgress.visibility = View.VISIBLE
                tvProgress.text = "Cancelando descarga..."
                btnCancel.isEnabled = false
                btnDownload.isEnabled = true
            }
        }
    }

    private fun startDownload(apk: AppUpdateManager.ApkSpec) {
        // Permiso de orígenes desconocidos (Android O+)
        AppUpdateManager.ensureUnknownSourcesPermission(this@UpdateRequiredActivity)

        pb.visibility = View.VISIBLE
        pb.isIndeterminate = true
        pb.progress = 0
        tvProgress.visibility = View.VISIBLE
        tvProgress.text = "Preparando descarga..."
        btnDownload.isEnabled = false
        btnCancel.isEnabled = true

        UpdateDownloadService.start(this@UpdateRequiredActivity, apk)

        Toast.makeText(
            this@UpdateRequiredActivity,
            "Descarga en segundo plano iniciada.",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onStart() {
        super.onStart()
        UpdateDownloadVisibility.isUpdateActivityVisible = true

        // 🔹 Si hay un APK descargado pendiente, abre el instalador
        AppUpdateManager.promptInstallFromLastDownloaded(this)
    }


    override fun onStop() {
        super.onStop()
        UpdateDownloadVisibility.isUpdateActivityVisible = false
    }

}
