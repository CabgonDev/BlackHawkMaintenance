package com.cabgon.blackhawk.ui

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random

class StartupActivity : AppCompatActivity() {

    private lateinit var b: ActivityStartupBinding

    private var hadVisibleOtaActivity = false
    private var readyToEnter = false

    // Slideshow
    private var slideJob: Job? = null
    private var showingA = true

    // ✅ Imágenes base (res/drawable)
    private val startupSlides = intArrayOf(
        R.drawable.startup_01,
        R.drawable.startup_02,
        R.drawable.startup_03,
        R.drawable.startup_04,
        R.drawable.startup_05,
        R.drawable.startup_06
    )

    // ✅ Secuencia aleatoria (shuffle) que se consume y se re-baraja al terminar
    private var shuffledSlides: MutableList<Int> = mutableListOf()
    private var shuffledIndex: Int = 0
    private var lastSlideRes: Int? = null

    companion object {
        const val EXTRA_FORCE_SELECT_PKG = "force_select_pkg"

        // ✅ ~20% más rápido que 5200/520
        const val SLIDE_DURATION_MS = 3200L
        const val FADE_MS = 320L
    }

    private enum class SlideEffect {
        ZOOM,
        PAN_DIAGONAL_LR,
        PAN_RL,
        PAN_LR
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        b = ActivityStartupBinding.inflate(layoutInflater)
        setContentView(b.root)

        startBackgroundSlideshow()

        showSelectionUi()

        b.btnEnter.setOnClickListener {
            if (!readyToEnter) return@setOnClickListener
            goMain()
        }

        val forceSelect = intent.getBooleanExtra(EXTRA_FORCE_SELECT_PKG, false)
        val savedPkgName = Prefs.getPackage(this)

        if (!forceSelect && !savedPkgName.isNullOrBlank()) {
            val pkg = runCatching { PackageManager.Pkg.valueOf(savedPkgName) }.getOrNull()
            if (pkg != null) {
                autoContinueWithPkg(pkg)
                return
            }
        }

        b.btnIads.setOnClickListener { startWithPkg(PackageManager.Pkg.IADS) }
        b.btnSikorsky.setOnClickListener { startWithPkg(PackageManager.Pkg.SIKORSKY) }
    }

    override fun onDestroy() {
        super.onDestroy()
        slideJob?.cancel()
    }

    private fun goMain() {
        startActivity(Intent(this@StartupActivity, MainActivity::class.java))
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        finish()
    }

    private fun showSelectionUi() {
        readyToEnter = false

        b.selectionBlock.visibility = View.VISIBLE
        b.centerBlock.visibility = View.GONE

        b.btnIads.isEnabled = true
        b.btnSikorsky.isEnabled = true

        b.pbOta.visibility = View.GONE
        b.txtOtaStatus.visibility = View.GONE
        b.txtBrandTitle.visibility = View.GONE
        b.btnEnter.visibility = View.GONE
    }

    private fun showProgressUi(msg: String) {
        readyToEnter = false

        b.selectionBlock.visibility = View.GONE
        b.centerBlock.visibility = View.VISIBLE
        //b.imgBrandIcon.visibility = View.GONE


        b.pbOta.visibility = View.VISIBLE
        b.txtOtaStatus.visibility = View.VISIBLE
        b.txtOtaStatus.text = msg

        b.txtBrandTitle.visibility = View.GONE
        b.btnEnter.visibility = View.GONE
    }

    private fun showEnterUi() {
        readyToEnter = true
        /*
        b.imgBrandIcon.visibility = View.VISIBLE
        b.imgBrandIcon.alpha = 0f

        b.imgBrandIcon.animate()
            .alpha(1f)
            .setDuration(220)
            .start()
        */

        b.selectionBlock.visibility = View.GONE
        b.centerBlock.visibility = View.VISIBLE

        b.pbOta.visibility = View.GONE
        b.txtOtaStatus.visibility = View.GONE

        b.txtBrandTitle.visibility = View.VISIBLE
        b.btnEnter.visibility = View.VISIBLE
        b.btnEnter.isEnabled = true

        b.txtBrandTitle.alpha = 0f
        b.btnEnter.alpha = 0f

        b.txtBrandTitle.animate().alpha(1f).setDuration(220).start()
        b.btnEnter.animate().alpha(1f).setDuration(260).start()
    }

    private fun autoContinueWithPkg(pkg: PackageManager.Pkg) {
        showProgressUi("Verificando actualizaciones…")

        lifecycleScope.launch {
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

            if (hadVisibleOtaActivity) {
                val elapsed = SystemClock.elapsedRealtime() - t0
                val minVisibleMs = 450L
                val hold = (minVisibleMs - elapsed).coerceAtLeast(0L).coerceAtMost(450L)
                if (hold > 0) delay(hold)
            }

            showEnterUi()
        }
    }

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
                showSelectionUi()
                b.centerBlock.visibility = View.VISIBLE
                b.txtOtaStatus.visibility = View.VISIBLE
                //b.imgBrandIcon.visibility = View.GONE
                b.txtOtaStatus.text = "Actualización obligatoria fallida. Verifica conexión y reintenta."
                return@launch
            }

            showEnterUi()
        }
    }

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

    private fun nextSlideRes(): Int {
        if (startupSlides.isEmpty()) return 0

        if (shuffledSlides.isEmpty() || shuffledIndex >= shuffledSlides.size) {
            shuffledSlides = startupSlides.toMutableList()
            shuffledSlides.shuffle()
            shuffledIndex = 0

            if (lastSlideRes != null && shuffledSlides.size > 1 && shuffledSlides[0] == lastSlideRes) {
                val swapWith = 1 + Random.nextInt(shuffledSlides.size - 1)
                val tmp = shuffledSlides[0]
                shuffledSlides[0] = shuffledSlides[swapWith]
                shuffledSlides[swapWith] = tmp
            }
        }

        val res = shuffledSlides[shuffledIndex]
        shuffledIndex++
        lastSlideRes = res
        return res
    }

    private fun startBackgroundSlideshow() {
        if (startupSlides.isEmpty()) return

        val firstRes = nextSlideRes()
        b.imgBgA.setImageResource(firstRes)
        b.imgBgA.alpha = 1f
        b.imgBgB.alpha = 0f
        showingA = true

        applyEffect(b.imgBgA, pickEffect(), durationMs = SLIDE_DURATION_MS)

        slideJob?.cancel()
        slideJob = lifecycleScope.launch {
            while (isActive) {
                delay(SLIDE_DURATION_MS - FADE_MS)

                val nextRes = nextSlideRes()

                val incoming = if (showingA) b.imgBgB else b.imgBgA
                val outgoing = if (showingA) b.imgBgA else b.imgBgB

                incoming.setImageResource(nextRes)
                resetTransform(incoming)
                incoming.alpha = 0f

                applyEffect(incoming, pickEffect(), durationMs = SLIDE_DURATION_MS)

                incoming.animate()
                    .alpha(1f)
                    .setDuration(FADE_MS)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()

                outgoing.animate()
                    .alpha(0f)
                    .setDuration(FADE_MS)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()

                showingA = !showingA
            }
        }
    }

    private fun resetTransform(v: View) {
        v.animate().cancel()
        v.scaleX = 1f
        v.scaleY = 1f
        v.translationX = 0f
        v.translationY = 0f
        v.rotation = 0f
    }

    private fun pickEffect(): SlideEffect {
        val r = Random.nextInt(100)
        return when {
            r < 40 -> SlideEffect.ZOOM
            r < 60 -> SlideEffect.PAN_DIAGONAL_LR
            r < 80 -> SlideEffect.PAN_RL
            else -> SlideEffect.PAN_LR
        }
    }

    private fun applyEffect(v: View, effect: SlideEffect, durationMs: Long) {
        val w = (v.rootView.width.takeIf { it > 0 } ?: 1080)
        val h = (v.rootView.height.takeIf { it > 0 } ?: 1920)

        val dxMax = (w * Random.nextDouble(0.03, 0.06)).roundToInt().toFloat()
        val dyMax = (h * Random.nextDouble(0.02, 0.05)).roundToInt().toFloat()

        val interp = AccelerateDecelerateInterpolator()

        when (effect) {
            SlideEffect.ZOOM -> {
                val zoomIn = Random.nextBoolean()
                val startScale = if (zoomIn) 1.06f else 1.22f
                val endScale = if (zoomIn) 1.22f else 1.06f

                val dx = dxMax * 0.35f * (if (Random.nextBoolean()) 1f else -1f)
                val dy = dyMax * 0.35f * (if (Random.nextBoolean()) 1f else -1f)

                v.scaleX = startScale
                v.scaleY = startScale
                v.translationX = -dx
                v.translationY = -dy

                v.animate()
                    .scaleX(endScale)
                    .scaleY(endScale)
                    .translationX(dx)
                    .translationY(dy)
                    .setDuration(durationMs)
                    .setInterpolator(interp)
                    .start()
            }

            SlideEffect.PAN_DIAGONAL_LR -> {
                val dyDir = if (Random.nextBoolean()) 1f else -1f
                val startX = -dxMax
                val endX = dxMax
                val startY = -dyMax * dyDir
                val endY = dyMax * dyDir

                v.scaleX = 1.16f
                v.scaleY = 1.16f
                v.translationX = startX
                v.translationY = startY

                v.animate()
                    .translationX(endX)
                    .translationY(endY)
                    .setDuration(durationMs)
                    .setInterpolator(interp)
                    .start()
            }

            SlideEffect.PAN_RL -> {
                val startX = dxMax
                val endX = -dxMax

                v.scaleX = 1.14f
                v.scaleY = 1.14f
                v.translationX = startX
                v.translationY = 0f

                v.animate()
                    .translationX(endX)
                    .setDuration(durationMs)
                    .setInterpolator(interp)
                    .start()
            }

            SlideEffect.PAN_LR -> {
                val startX = -dxMax
                val endX = dxMax

                v.scaleX = 1.14f
                v.scaleY = 1.14f
                v.translationX = startX
                v.translationY = 0f

                v.animate()
                    .translationX(endX)
                    .setDuration(durationMs)
                    .setInterpolator(interp)
                    .start()
            }
        }
    }
}
