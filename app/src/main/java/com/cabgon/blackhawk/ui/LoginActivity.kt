package com.cabgon.blackhawk.ui

import android.content.Intent
import android.os.Bundle
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.data.user.UserSessionStore
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random

class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var session: UserSessionStore

    // ---------- Slideshow ----------
    private var slideJob: Job? = null
    private var showingA = true
    private var lastEffect: SlideEffect? = null
    private lateinit var imgBgA: ImageView
    private lateinit var imgBgB: ImageView

    private val startupSlides = intArrayOf(
        R.drawable.startup_01,
        R.drawable.startup_02,
        R.drawable.startup_03,
        R.drawable.startup_04,
        R.drawable.startup_05,
        R.drawable.startup_06
    )

    private var shuffledSlides: MutableList<Int> = mutableListOf()
    private var shuffledIndex: Int = 0
    private var lastSlideRes: Int? = null

    companion object {
        const val SLIDE_DURATION_MS = 2800L
        const val FADE_MS = 280L
    }

    private enum class SlideEffect {
        ZOOM,
        PAN_DIAGONAL_LR,
        PAN_RL,
        PAN_LR
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        session = UserSessionStore(this)

        imgBgA = findViewById(R.id.slideA)
        imgBgB = findViewById(R.id.slideB)

        // ✅ Arrancar slideshow cuando ya hay medidas reales
        findViewById<ImageView>(R.id.slideA).rootView.doOnPreDraw {
            startBackgroundSlideshow()
        }

        // ✅ Si ya está logueado, verificamos estatus en Firestore (gate estricto)
        val currentUser = auth.currentUser
        if (currentUser != null) {
            verifyUserStatusAndRoute(currentUser.uid)
            return
        }

        val etEmail = findViewById<EditText>(R.id.etEmail)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        val btnLogin = findViewById<Button>(R.id.btnLogin)
        val tvGoRegister = findViewById<TextView>(R.id.tvGoRegister)

        btnLogin.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Ingresa correo y contraseña", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnLogin.isEnabled = false

            auth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener { task ->
                    btnLogin.isEnabled = true
                    if (!task.isSuccessful) {
                        Toast.makeText(this, "Acceso denegado", Toast.LENGTH_SHORT).show()
                        return@addOnCompleteListener
                    }

                    val uid = auth.currentUser?.uid ?: return@addOnCompleteListener
                    verifyUserStatusAndRoute(uid)
                }
        }

        tvGoRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    private fun verifyUserStatusAndRoute(uid: String) {
        db.collection("users").document(uid)
            .get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) {
                    Toast.makeText(this, "Perfil no encontrado.", Toast.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }

                session.saveProfileFromDocument(doc)

                val status = (doc.getString("status") ?: "approved").lowercase()

                when (status) {
                    "approved" -> {
                        Toast.makeText(this, "Bienvenido.", Toast.LENGTH_SHORT).show()
                        goToStartup()
                    }
                    "rejected" -> {
                        startActivity(Intent(this, RejectedActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        })
                        finish()
                    }
                    else -> { // pending
                        startActivity(Intent(this, PendingApprovalActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        })
                        finish()
                    }
                }
            }
            .addOnFailureListener {
                startActivity(Intent(this, PendingApprovalActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        slideJob?.cancel()
    }

    private fun goToStartup() {
        startActivity(Intent(this, StartupActivity::class.java))
        finish()
    }

    // ---------------- Slideshow helpers (FIX bordes) ----------------

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

        // Si por alguna razón aún no están medidos, re-intenta con post()
        if (imgBgA.width == 0 || imgBgA.height == 0) {
            imgBgA.post { startBackgroundSlideshow() }
            return
        }

        val firstRes = nextSlideRes()
        imgBgA.setImageResource(firstRes)
        imgBgA.alpha = 1f
        imgBgB.alpha = 0f
        showingA = true

        val firstDuration = randomSlideDuration()
        applyEffect(imgBgA, pickEffect(), durationMs = firstDuration)

        slideJob?.cancel()
        slideJob = lifecycleScope.launch {
            while (isActive) {
                val duration = randomSlideDuration()
                val delayMs = (duration - FADE_MS).coerceAtLeast(FADE_MS)
                delay(delayMs)

                val nextRes = nextSlideRes()

                val incoming = if (showingA) imgBgB else imgBgA
                val outgoing = if (showingA) imgBgA else imgBgB

                incoming.setImageResource(nextRes)
                resetTransform(incoming)
                incoming.alpha = 0f

                applyEffect(incoming, pickEffect(), durationMs = duration)

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

    private fun resetTransform(v: ImageView) {
        v.animate().cancel()
        v.pivotX = v.width / 2f
        v.pivotY = v.height / 2f
        v.scaleX = 1f
        v.scaleY = 1f
        v.translationX = 0f
        v.translationY = 0f
        v.rotation = 0f
    }

    private fun pickEffect(): SlideEffect {
        val available = SlideEffect.entries.toMutableList()
        if (available.size > 1) lastEffect?.let { available.remove(it) }
        val picked = available.random()
        lastEffect = picked
        return picked
    }

    private fun randomSlideDuration(): Long {
        val jitter = Random.nextInt(-380, 420)
        return (SLIDE_DURATION_MS + jitter).coerceAtLeast(2200L)
    }

    private fun applyEffect(v: ImageView, effect: SlideEffect, durationMs: Long) {
        // ✅ usa tamaño real del ImageView, NO displayMetrics
        val w = (v.width.takeIf { it > 0 } ?: 1080).toFloat()
        val h = (v.height.takeIf { it > 0 } ?: 1920).toFloat()

        // deltas similares a Startup para evitar bordes
        val dxMax = (w * Random.nextDouble(0.03, 0.06)).roundToInt().toFloat()
        val dyMax = (h * Random.nextDouble(0.02, 0.05)).roundToInt().toFloat()

        val interp = AccelerateDecelerateInterpolator()

        v.pivotX = w / 2f
        v.pivotY = h / 2f

        when (effect) {
            SlideEffect.ZOOM -> {
                val zoomIn = Random.nextBoolean()
                val startScale = if (zoomIn) 1.06f else 1.20f
                val endScale = if (zoomIn) 1.20f else 1.06f

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
                val startY = -dyMax * dyDir
                val endY = dyMax * dyDir

                v.scaleX = 1.14f
                v.scaleY = 1.14f
                v.translationX = startX
                v.translationY = startY

                v.animate()
                    .translationX(dxMax)
                    .translationY(endY)
                    .setDuration(durationMs)
                    .setInterpolator(interp)
                    .start()
            }

            SlideEffect.PAN_RL -> {
                v.scaleX = 1.12f
                v.scaleY = 1.12f
                v.translationX = dxMax
                v.translationY = 0f

                v.animate()
                    .translationX(-dxMax)
                    .setDuration(durationMs)
                    .setInterpolator(interp)
                    .start()
            }

            SlideEffect.PAN_LR -> {
                v.scaleX = 1.12f
                v.scaleY = 1.12f
                v.translationX = -dxMax
                v.translationY = 0f

                v.animate()
                    .translationX(dxMax)
                    .setDuration(durationMs)
                    .setInterpolator(interp)
                    .start()
            }
        }
    }
}
