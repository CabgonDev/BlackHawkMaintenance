package com.cabgon.blackhawk.ui

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.ai.update.AiUpdateManager
import com.cabgon.blackhawk.ai.update.AppUpdateManager
import com.cabgon.blackhawk.data.PackageManager
import com.cabgon.blackhawk.data.user.UserSessionStore
import com.cabgon.blackhawk.databinding.ActivityStartupBinding
import com.cabgon.blackhawk.util.Prefs
import com.cabgon.blackhawk.util.Roles
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class StartupActivity : AppCompatActivity() {

    private lateinit var b: ActivityStartupBinding
    private var hadVisibleOtaActivity = false
    private var readyToEnter = false

    companion object {
        const val EXTRA_FORCE_SELECT_PKG = "force_select_pkg"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1) Sesión
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        // 2) UI
        b = ActivityStartupBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Estado inicial
        setAlphaReady()
        showSelectionUi()

        // Entrar
        b.btnEnter.setOnClickListener {
            if (!readyToEnter) return@setOnClickListener
            goMainWithFade()
        }

        val forceSelect = intent.getBooleanExtra(EXTRA_FORCE_SELECT_PKG, false)
        val savedPkgName = Prefs.getPackage(this)

        // FAST PATH: si ya hay paquete guardado y NO forzaste selección, no pidas paquete
        if (!forceSelect && !savedPkgName.isNullOrBlank()) {
            val pkg = runCatching { PackageManager.Pkg.valueOf(savedPkgName) }.getOrNull()
            if (pkg != null) {
                autoContinueWithPkg(pkg)
                return
            }
        }

        // Selección manual
        b.btnIads.setOnClickListener { startWithPkg(PackageManager.Pkg.IADS) }
        b.btnSikorsky.setOnClickListener { startWithPkg(PackageManager.Pkg.SIKORSKY) }
    }

    private fun setAlphaReady() {
        // Por si vienes de una animación previa
        b.rootStartup.alpha = 1f
    }

    private fun goMainWithFade() {
        startActivity(Intent(this@StartupActivity, MainActivity::class.java))
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        finish()
    }

    private fun showSelectionUi() {
        readyToEnter = false
        b.btnEnter.visibility = View.GONE

        b.txtSelectPackage.visibility = View.VISIBLE
        b.btnIads.visibility = View.VISIBLE
        b.btnSikorsky.visibility = View.VISIBLE
        b.btnIads.isEnabled = true
        b.btnSikorsky.isEnabled = true

        b.pbOta.visibility = View.GONE
        b.txtOtaStatus.visibility = View.GONE
    }

    private fun showProgressUi(msg: String) {
        readyToEnter = false
        b.btnEnter.visibility = View.GONE

        // Clave: ocultar el título de selección mientras verificas
        b.txtSelectPackage.visibility = View.GONE
        b.btnIads.visibility = View.GONE
        b.btnSikorsky.visibility = View.GONE

        b.pbOta.visibility = View.VISIBLE
        b.txtOtaStatus.visibility = View.VISIBLE
        b.txtOtaStatus.text = msg
    }

    private fun showEnterUi(msg: String = "Listo.") {
        readyToEnter = true

        b.pbOta.visibility = View.GONE
        b.txtOtaStatus.visibility = View.VISIBLE
        b.txtOtaStatus.text = msg

        b.btnEnter.apply {
            visibility = View.VISIBLE
            isEnabled = true
            alpha = 0f
            animate().alpha(1f).setDuration(180).start()
        }
    }

    /**
     * Auto-continue: paquete guardado.
     * Verifica OTA y al terminar muestra "Entrar" (no navega solo).
     */
    private fun autoContinueWithPkg(pkg: PackageManager.Pkg) {
        showProgressUi("Verificando actualizaciones…")

        lifecycleScope.launch {
            // APK min version
            val appCheck = AppUpdateManager.check(applicationContext)
            if (appCheck.updateRequired) {
                startActivity(Intent(this@StartupActivity, UpdateRequiredActivity::class.java))
                finish()
                return@launch
            }

            val role = UserSessionStore(this@StartupActivity).getProfile()?.role ?: Roles.USER
            val allowModelDownload = Roles.isAtLeast(role, Roles.ADMIN)

            hadVisibleOtaActivity = false
            val t0 = SystemClock.elapsedRealtime()

            val res = AiUpdateManager.checkAndUpdateForPackage(
                context = applicationContext,
                pkg = pkg,
                allowModelDownload = allowModelDownload
            ) { ev ->
                val msg = mapEventToUiAutoSilent(ev) ?: return@checkAndUpdateForPackage
                hadVisibleOtaActivity = true
                runOnUiThread { b.txtOtaStatus.text = msg }
            }

            if (res.forceBlocked) {
                showProgressUi("Actualización obligatoria fallida. Verifica conexión y reintenta.")
                return@launch
            }

            // Delay dinámico anti-parpadeo si hubo actividad visible
            if (hadVisibleOtaActivity) {
                val elapsed = SystemClock.elapsedRealtime() - t0
                val minVisibleMs = 450L
                val hold = (minVisibleMs - elapsed).coerceAtLeast(0L).coerceAtMost(450L)
                if (hold > 0) delay(hold)
            }

            showEnterUi("Listo. Presiona Entrar.")
        }
    }

    /**
     * Selección manual: guarda paquete, verifica OTA y al terminar muestra "Entrar".
     */
    private fun startWithPkg(pkg: PackageManager.Pkg) {
        Prefs.setPackage(this, pkg.name)

        b.btnIads.isEnabled = false
        b.btnSikorsky.isEnabled = false
        showProgressUi("Comprobando actualizaciones…")

        lifecycleScope.launch {
            val appCheck = AppUpdateManager.check(applicationContext)
            if (appCheck.updateRequired) {
                startActivity(Intent(this@StartupActivity, UpdateRequiredActivity::class.java))
                finish()
                return@launch
            }

            val role = UserSessionStore(this@StartupActivity).getProfile()?.role ?: Roles.USER
            val allowModelDownload = Roles.isAtLeast(role, Roles.ADMIN)

            val res = AiUpdateManager.checkAndUpdateForPackage(
                context = applicationContext,
                pkg = pkg,
                allowModelDownload = allowModelDownload
            ) { ev ->
                runOnUiThread {
                    b.txtOtaStatus.text = when (ev) {
                        is AiUpdateManager.Event.Checking -> "Comprobando actualizaciones…"
                        is AiUpdateManager.Event.ChannelSelected -> "Canal OTA: ${ev.channel}"
                        is AiUpdateManager.Event.Downloading -> "Descargando: ${ev.what}…"
                        is AiUpdateManager.Event.Verifying -> "Verificando: ${ev.what}…"
                        is AiUpdateManager.Event.Applied -> "Actualizado: ${ev.what}"
                        is AiUpdateManager.Event.UpToDate -> "Al día: ${ev.what}"
                        is AiUpdateManager.Event.Skipped -> ev.reason
                        is AiUpdateManager.Event.Error -> "Error en ${ev.what}: ${ev.message}"
                    }
                }
            }

            if (res.forceBlocked) {
                // Si falla y es bloqueante, regresamos a selección
                showSelectionUi()
                b.txtOtaStatus.visibility = View.VISIBLE
                b.txtOtaStatus.text = "Actualización obligatoria fallida. Verifica conexión y reintenta."
                return@launch
            }

            showEnterUi("Listo. Presiona Entrar.")
        }
    }

    /**
     * Auto-silent:
     * - Ignora: Checking / ChannelSelected / UpToDate / Skipped / Applied
     * - Solo muestra: Downloading / Verifying / Error
     */
    private fun mapEventToUiAutoSilent(ev: AiUpdateManager.Event): String? {
        return when (ev) {
            is AiUpdateManager.Event.Checking -> null
            is AiUpdateManager.Event.ChannelSelected -> null
            is AiUpdateManager.Event.UpToDate -> null
            is AiUpdateManager.Event.Skipped -> null
            is AiUpdateManager.Event.Applied -> null

            is AiUpdateManager.Event.Downloading -> "Descargando: ${ev.what}…"
            is AiUpdateManager.Event.Verifying -> "Verificando: ${ev.what}…"
            is AiUpdateManager.Event.Error -> "Error en ${ev.what}: ${ev.message}"
        }
    }
}
