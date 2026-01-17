package com.cabgon.blackhawk.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.ai.update.AppUpdateManager
import kotlinx.coroutines.launch

class UpdateRequiredActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update_required)

        val tvTitle = findViewById<TextView>(R.id.tvUpdateTitle)
        val tvBody = findViewById<TextView>(R.id.tvUpdateBody)
        val tvNotes = findViewById<TextView>(R.id.tvUpdateNotes)
        val btn = findViewById<Button>(R.id.btnDownloadInstall)
        val pb = findViewById<ProgressBar>(R.id.pbUpdate)

        pb.visibility = View.GONE
        tvNotes.visibility = View.GONE

        lifecycleScope.launch {
            val check = AppUpdateManager.check(applicationContext)

            tvTitle.text = "Actualización requerida"
            tvBody.text = "Tu versión actual (${check.currentVersionCode}) es menor que la mínima requerida (${check.minAppVersionCode}). Debes actualizar para continuar."

            val apk = check.apkSpec
            if (apk?.releaseNotes != null) {
                tvNotes.visibility = View.VISIBLE
                tvNotes.text = "Notas:\n${apk.releaseNotes}"
            }

            btn.setOnClickListener {
                if (apk == null) {
                    Toast.makeText(this@UpdateRequiredActivity, "No hay APK configurado en el canal.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                // Si falta permiso para instalar, abre settings
                AppUpdateManager.ensureUnknownSourcesPermission(this@UpdateRequiredActivity)

                pb.visibility = View.VISIBLE
                btn.isEnabled = false

                lifecycleScope.launch {
                    val ok = AppUpdateManager.downloadAndPromptInstall(applicationContext, apk)
                    pb.visibility = View.GONE
                    btn.isEnabled = true

                    if (!ok) {
                        Toast.makeText(this@UpdateRequiredActivity, "No se pudo descargar/verificar el APK.", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@UpdateRequiredActivity, "Se abrió el instalador. Confirma la instalación.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onBackPressed() {
        // Bloqueo: no permitir regresar a la app sin actualizar
        moveTaskToBack(true)
    }
}
