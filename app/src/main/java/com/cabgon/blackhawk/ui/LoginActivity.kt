package com.cabgon.blackhawk.ui

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Matrix
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.text.InputType
import android.text.method.KeyListener
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.data.user.UserSessionStore
import com.cabgon.blackhawk.data.user.BiometricCredentialStore
import com.cabgon.blackhawk.data.user.AuthStateCleaner
import com.cabgon.blackhawk.data.user.DeviceBindingVerifier
import com.cabgon.blackhawk.data.user.DeviceBindingStore
import com.cabgon.blackhawk.data.user.LoginFlowPolicy
import com.cabgon.blackhawk.data.user.LoginBiometricState
import com.cabgon.blackhawk.data.user.CorruptBiometricCredentialsException
import com.cabgon.blackhawk.util.LogoutBus
import com.cabgon.blackhawk.util.Prefs
import com.cabgon.blackhawk.util.SessionFlags
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.AuthResult
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.crypto.Cipher
import javax.crypto.AEADBadTagException
import android.security.keystore.KeyPermanentlyInvalidatedException
import java.security.UnrecoverableKeyException
import android.util.Log
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var session: UserSessionStore
    private lateinit var biometricCredentials: BiometricCredentialStore
    private lateinit var biometricState: LoginBiometricState
    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var cbEnableBiometrics: CheckBox
    private lateinit var btnBiometricLogin: Button
    private lateinit var btnLogin: Button
    private lateinit var tvGoRegister: TextView
    private lateinit var tvForgetUser: TextView
    private lateinit var loginContent: View
    private lateinit var loginProgress: View
    private lateinit var tvLoginStatus: TextView
    private lateinit var tvLoginStatusDetail: TextView
    private lateinit var loginStatusSpinner: View
    private lateinit var loginStatusSuccess: View
    private var statusTransitionJob: Job? = null
    private var profileStatusStageJob: Job? = null
    private var profileStatusStageReady: CompletableDeferred<Unit>? = null
    private var statusStageStartedAt = 0L
    private var currentStatusKey: String? = null
    private var pendingEmail: String? = null
    private var pendingPassword: String? = null
    private var signedInWithBiometrics = false
    private var processBiometricLock = false
    private var biometricLockUiHidden = false
    private var requireCredentials = false
    private var emailLocked = false
    private var editableEmailKeyListener: KeyListener? = null
    private var emailLockNotice: Snackbar? = null
    private var passwordVisible = false

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
        R.drawable.startup_06,
        R.drawable.startup_07
    )

    private val recentSlideRes = mutableListOf<Int>()
    private val recentEffectRes = mutableListOf<SlideEffect>()
    private var lastSlideRes: Int? = null

    companion object {
        const val SLIDE_DURATION_MS = 6500L
        const val LEGACY_SLIDE_DURATION_MS = 3500L
        const val FULL_PAN_MIN_DURATION_MS = 9_600L
        const val FULL_PAN_MAX_DURATION_MS = 12_000L
        const val FULL_PAN_TARGET_PX_PER_SECOND = 138f
        const val FULL_PAN_MIN_TRAVEL_PX = 24f
        const val RECENT_SLIDE_MEMORY = 2
        const val RECENT_EFFECT_MEMORY = 2
        const val FADE_MS = 650L
        const val STATUS_STAGE_MIN_MS = 1500L
        const val EXTRA_PROCESS_BIOMETRIC_LOCK = "process_biometric_lock"
        const val EXTRA_REQUIRE_CREDENTIALS = "require_credentials"
    }

    private enum class SlideEffect {
        ZOOM,
        PAN_DIAGONAL_LR,
        PAN_RL,
        PAN_LR,
        PAN_FULL_LR,
        PAN_FULL_RL,
        PAN_FULL_TB,
        PAN_FULL_BT
    }

    private data class SlidePanBounds(
        val scale: Float,
        val minX: Float,
        val maxX: Float,
        val minY: Float,
        val maxY: Float
    ) {
        val horizontalTravel: Float get() = maxX - minX
        val verticalTravel: Float get() = maxY - minY
        val centerX: Float get() = (minX + maxX) / 2f
        val centerY: Float get() = (minY + maxY) / 2f
    }

    @Suppress("UNUSED_PARAMETER")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        biometricState = ViewModelProvider(this)[LoginBiometricState::class.java]
        processBiometricLock = biometricState.processRequiresLogin || !SessionFlags.loginCompleted ||
            intent.getBooleanExtra(EXTRA_PROCESS_BIOMETRIC_LOCK, false)
        biometricState.processRequiresLogin = processBiometricLock
        requireCredentials = intent.getBooleanExtra(EXTRA_REQUIRE_CREDENTIALS, false)
        setContentView(R.layout.activity_login)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        session = UserSessionStore(this)
        biometricCredentials = BiometricCredentialStore(this)

        if (requireCredentials &&
            biometricState.signInTask == null && biometricState.validatingUid == null &&
            biometricState.operation == null) {
            auth.signOut()
            session.clear()
        }

        imgBgA = findViewById(R.id.slideA)
        imgBgB = findViewById(R.id.slideB)

        // ✅ Arrancar slideshow cuando ya hay medidas reales
        findViewById<ImageView>(R.id.slideA).rootView.doOnPreDraw {
            startBackgroundSlideshow()
        }

        val currentUser = auth.currentUser

        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        cbEnableBiometrics = findViewById(R.id.cbEnableBiometrics)
        btnBiometricLogin = findViewById(R.id.btnBiometricLogin)
        btnLogin = findViewById(R.id.btnLogin)
        tvGoRegister = findViewById(R.id.tvGoRegister)
        tvForgetUser = findViewById(R.id.tvForgetUser)
        loginContent = findViewById(R.id.loginContent)
        loginProgress = findViewById(R.id.loginProgress)
        tvLoginStatus = findViewById(R.id.tvLoginStatus)
        tvLoginStatusDetail = findViewById(R.id.tvLoginStatusDetail)
        loginStatusSpinner = findViewById(R.id.loginStatusSpinner)
        loginStatusSuccess = findViewById(R.id.loginStatusSuccess)
        etEmail.setText(Prefs.getLoginEmail(this).orEmpty())
        editableEmailKeyListener = etEmail.keyListener
        updateEmailLock(etEmail.text.isNotBlank() && DeviceBindingStore(this).hasAnyBinding())
        etEmail.setOnClickListener {
            if (emailLocked && emailLockNotice?.isShownOrQueued != true) {
                emailLockNotice = Snackbar.make(
                    loginContent, R.string.correo_vinculado_aviso, Snackbar.LENGTH_LONG
                ).setTextMaxLines(4)
                    .setBackgroundTint(ContextCompat.getColor(this, R.color.login_status_surface))
                    .setTextColor(ContextCompat.getColor(this, R.color.login_status_text))
                    .also { it.show() }
            }
        }
        configurePasswordToggle()

        val biometricAvailable = BiometricManager.from(this)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        if (biometricAvailable) cbEnableBiometrics.visibility = View.VISIBLE
        val hasBiometricCredentials = biometricCredentials.hasCredentials()
        if (hasBiometricCredentials) cbEnableBiometrics.isChecked = true
        if (requireCredentials && hasBiometricCredentials) {
            // Reconfirm the biometric enrollment after the one-time password login.
            // The old encrypted credential remains available if the user cancels.
            biometricState.credentialsNeedRefresh = true
        }
        if (biometricAvailable && hasBiometricCredentials) {
            btnBiometricLogin.visibility = View.VISIBLE
        }
        if (requireCredentials) {
            // The migration must be completed with the account password. Existing
            // biometric material remains stored and can be used after this one-time step.
            btnBiometricLogin.visibility = View.GONE
        }

        // Do not draw the form in the first frame while the process-lock prompt opens.
        val promptOnStart = LoginFlowPolicy.shouldPromptBiometrics(
            processBiometricLock, requireCredentials, biometricAvailable, hasBiometricCredentials,
            autoPromptSuppressed = SessionFlags.suppressBiometricAutoPromptAfterLogout
        )
        if (promptOnStart || biometricState.operation == LoginBiometricState.Operation.LOGIN) {
            loginContent.visibility = View.INVISIBLE
            biometricLockUiHidden = true
        }

        btnBiometricLogin.setOnClickListener { authenticateBiometricLogin() }

        btnLogin.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Ingresa correo y contraseña", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            signIn(email, password)
        }

        tvGoRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        tvForgetUser.setOnClickListener { forgetSavedUser() }

        if (biometricState.operation != null) {
            when (biometricState.operation) {
                LoginBiometricState.Operation.LOGIN -> authenticateBiometricLogin(startPrompt = false)
                LoginBiometricState.Operation.ENROLL -> {
                    val email = biometricState.email
                    val password = biometricState.password
                    if (email != null && password != null) {
                        authenticateForCredentialSave(email, password, startPrompt = false)
                    } else {
                        biometricState.clearPrompt()
                        revealLoginContentAfterBiometric()
                    }
                }
                LoginBiometricState.Operation.RELEASE -> authenticateBiometricForDeviceRelease(startPrompt = false)
                null -> Unit
            }
            return
        }

        if (biometricState.releaseInProgress) {
            setLoginLoading(true)
            return
        }

        val signInTask = biometricState.signInTask
        val validatingUid = biometricState.validatingUid
        if (signInTask != null || validatingUid != null) {
            signedInWithBiometrics = biometricState.signedInWithBiometrics
            pendingEmail = biometricState.email
            pendingPassword = biometricState.password
            setLoginLoading(true)
            if (signInTask != null) observeSignIn(signInTask)
            else if (validatingUid != null) {
                if (biometricState.offlineBiometricProof && !hasValidatedInternet()) {
                    if (canUnlockOffline(biometricState.email.orEmpty())) showWelcomeAndContinue()
                    else failClosedToLogin("Conectate a una red para validar tu acceso.")
                } else {
                    verifyUserStatusAndRoute(validatingUid)
                }
            }
            return
        }

        if (currentUser != null && !processBiometricLock && !requireCredentials) {
            verifyUserStatusAndRoute(currentUser.uid)
            return
        }

        if (promptOnStart) {
            btnBiometricLogin.post { authenticateBiometricLogin() }
        }
    }

    override fun onStart() {
        super.onStart()
        biometricState.releaseSignInTask?.let(::observeReleaseSignIn)
        biometricState.releaseTask?.let(::observeDeviceRelease)
    }

    private fun goSuspended() {
        setLoginLoading(false)
        AuthStateCleaner.clear(this)
        startActivity(Intent(this, SuspendedActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun verifyUserStatusAndRoute(uid: String) {
        setLoginLoading(true)

        profileStatusStageJob?.cancel()
        val profileStageReady = CompletableDeferred<Unit>()
        profileStatusStageReady = profileStageReady
        val elapsed = if (statusStageStartedAt == 0L) {
            STATUS_STAGE_MIN_MS
        } else {
            SystemClock.elapsedRealtime() - statusStageStartedAt
        }
        val remaining = (STATUS_STAGE_MIN_MS - elapsed).coerceAtLeast(0L)
        profileStatusStageJob = lifecycleScope.launch {
            delay(remaining.milliseconds)
            if (!isActive) return@launch
            showLoginStatus(
                title = getString(R.string.iniciando_sesion),
                detail = getString(R.string.verificando_perfil),
                loading = true
            )
            profileStageReady.complete(Unit)
        }

        // Profile lookup starts while the status stages continue animating.
        loadUserStatusAndRoute(uid)
    }

    private fun loadUserStatusAndRoute(uid: String) {
        db.collection("users").document(uid)
            .get(Source.SERVER)
            .addOnSuccessListener { doc ->
                if (isFinishing || isDestroyed) return@addOnSuccessListener
                if (!doc.exists()) {
                    failClosedToLogin("Perfil no encontrado.")
                    return@addOnSuccessListener
                }

                if (!session.saveProfileFromDocument(doc, uid)) {
                    failClosedToLogin("Perfil inválido o incompleto.")
                    return@addOnSuccessListener
                }

                // ✅ IMPORTANTE:
                // NO limpiamos LogoutBus al abrir LoginActivity.
                // Si el usuario viene de un logout, puede llegar un PERMISSION_DENIED tardío
                // del listener de MainActivity (porque ya hicimos signOut) y disparar el kick
                // hacia SuspendedActivity.
                // La bandera se limpia hasta que YA verificamos al usuario y vamos a rutear.
                LogoutBus.endLogout()

                val status = doc.getString("status")?.trim()?.lowercase() ?: ""

                when (status) {
                    "approved" -> {
                        DeviceBindingVerifier.verify(
                            context = this,
                            uid = uid,
                            onSuccess = {
                                // claimDevice is the commit point for the migration. If it
                                // fails, the pending marker stays in place and the next
                                // launch will require credentials again.
                                Prefs.markAuthMigrationComplete(
                                    this,
                                    Prefs.AUTH_MIGRATION_VERSION_CODE
                                )
                                if (!isFinishing && !isDestroyed) showWelcomeAndContinue()
                            },
                            onFailure = { if (!isFinishing && !isDestroyed) failClosedToLogin("Este dispositivo no está autorizado.") }
                        )
                    }
                    "rejected" -> {
                        setLoginLoading(false)
                        startActivity(Intent(this, RejectedActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        })
                        finish()
                    }
                    "suspended" -> {
                        goSuspended()
                    }
                    else -> { // pending
                        setLoginLoading(false)
                        startActivity(Intent(this, PendingApprovalActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        })
                        finish()
                    }
                }
            }
            .addOnFailureListener {
                failClosedToLogin("No se pudo validar el perfil. Intenta de nuevo.")
            }
    }

    private fun failClosedToLogin(message: String) {
        if (isFinishing || isDestroyed) return
        AuthStateCleaner.clear(this, clearBiometrics = false)
        pendingEmail = null
        pendingPassword = null
        signedInWithBiometrics = false
        biometricState.clearLoginAttempt()
        etPassword.text.clear()
        revealLoginContentAfterBiometric()
        setLoginLoading(false)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showWelcomeAndContinue() {
        val profileStageReady = profileStatusStageReady
        lifecycleScope.launch {
            profileStageReady?.await()
            if (!isActive) return@launch

            runAfterLoginStatusMinimum {
                showLoginStatus(
                    title = getString(R.string.bienvenido),
                    detail = getString(R.string.acceso_autorizado),
                    loading = false
                )
                statusTransitionJob = lifecycleScope.launch {
                    delay(STATUS_STAGE_MIN_MS.milliseconds)
                    if (isActive) continueAfterApprovedLogin()
                }
            }
        }
    }

    private fun continueAfterApprovedLogin() {
        SessionFlags.loginCompleted = true
        SessionFlags.suppressBiometricAutoPromptAfterLogout = false
        val usedOfflineBiometric = biometricState.offlineBiometricProof
        val email = pendingEmail
        val password = pendingPassword
        pendingEmail = null
        pendingPassword = null
        biometricState.clearLoginAttempt()
        etPassword.text.clear()
        if (email != null) {
            Prefs.setLoginEmail(this, email)
            updateEmailLock(DeviceBindingStore(this).hasAnyBinding())
        }

        if (email == null || password == null) {
            goToStartup(accessAlreadyValidated = !usedOfflineBiometric)
            return
        }

        if (signedInWithBiometrics) {
            signedInWithBiometrics = false
            SessionFlags.biometricUnlockCompleted = true
            goToStartup(
                forceUpdateVerification = true,
                accessAlreadyValidated = !usedOfflineBiometric
            )
            return
        }

        if (LoginFlowPolicy.shouldOfferBiometricEnrollment(
                cbEnableBiometrics.isChecked, email, password,
                biometricCredentials.hasCredentials(), biometricState.credentialsNeedRefresh
            )) {
            authenticateForCredentialSave(email, password)
        } else {
            if (!cbEnableBiometrics.isChecked) biometricCredentials.clear()
            goToStartup()
        }
    }

    private fun authenticateForCredentialSave(email: String, password: String, startPrompt: Boolean = true) {
        hideLoginStatus(animate = false)
        val cipher = if (!startPrompt) null else try {
            try {
                biometricCredentials.prepareEncryption()
            } catch (error: Exception) {
                if (!invalidateBiometricsIfUnusable(error)) throw error
                biometricCredentials.prepareEncryption()
            }
        } catch (_: Exception) {
            Toast.makeText(this, "No se pudo preparar el acceso biométrico.", Toast.LENGTH_LONG).show()
            goToStartup()
            return
        }
        biometricState.operation = LoginBiometricState.Operation.ENROLL
        biometricState.email = email
        biometricState.password = password

        val prompt = createBiometricPrompt(onSuccess = {
            try {
                biometricCredentials.save(it, email, password)
                biometricState.credentialsNeedRefresh = false
                SessionFlags.biometricUnlockCompleted = true
                Toast.makeText(this, "Acceso con huella habilitado.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("LoginActivity", "No se pudo guardar la credencial biométrica", e)
                SessionFlags.biometricUnlockCompleted = true
                Toast.makeText(this, "No se pudo guardar el acceso biométrico.", Toast.LENGTH_LONG).show()
            }
            goToStartup()
        }, onError = {
            SessionFlags.biometricUnlockCompleted = true
            Toast.makeText(this, "No se habilitó la huella. Puedes continuar con tu sesión.", Toast.LENGTH_SHORT).show()
            goToStartup()
        })
        if (!startPrompt) return
        try {
            prompt.authenticate(
                biometricPromptInfo(getString(R.string.biometria_habilitar_titulo)),
                BiometricPrompt.CryptoObject(checkNotNull(cipher))
            )
        } catch (_: Exception) {
            biometricState.clearPrompt()
            SessionFlags.biometricUnlockCompleted = true
            Toast.makeText(this, "No se pudo iniciar la autenticación biométrica.", Toast.LENGTH_LONG).show()
            goToStartup()
        }
    }

    private fun authenticateBiometricLogin(startPrompt: Boolean = true) {
        if (startPrompt && biometricState.operation != null) return
        val cipher = if (!startPrompt) null else try {
            biometricCredentials.prepareDecryption()
        } catch (error: Exception) {
            revealLoginContentAfterBiometric()
            setLoginLoading(false)
            invalidateBiometricsIfUnusable(error)
            Toast.makeText(this, "Vuelve a iniciar sesión con tu contraseña para habilitar la huella.", Toast.LENGTH_LONG).show()
            return
        }
        biometricState.operation = LoginBiometricState.Operation.LOGIN

        val prompt = createBiometricPrompt(onSuccess = { unlockedCipher: Cipher ->
            val credentials = try {
                biometricCredentials.decrypt(unlockedCipher)
            } catch (error: Exception) {
                revealLoginContentAfterBiometric()
                setLoginLoading(false)
                invalidateBiometricsIfUnusable(error)
                Toast.makeText(this, "No se pudieron recuperar las credenciales. Usa tu contraseña.", Toast.LENGTH_LONG).show()
                return@createBiometricPrompt
            }
            etEmail.setText(credentials.first)
            etPassword.text.clear()
            signedInWithBiometrics = true
            if (!hasValidatedInternet()) {
                val currentUser = auth.currentUser
                if (currentUser == null || !canUnlockOffline(credentials.first)) {
                    signedInWithBiometrics = false
                    revealLoginContentAfterBiometric()
                    setLoginLoading(false)
                    Toast.makeText(this, "Conectate a una red para validar tu acceso.", Toast.LENGTH_LONG).show()
                    return@createBiometricPrompt
                }
                // A valid Keystore unlock is the local proof; never refresh the 24h window offline.
                pendingEmail = credentials.first
                pendingPassword = credentials.second
                biometricState.email = credentials.first
                biometricState.password = credentials.second
                biometricState.validatingUid = currentUser.uid
                biometricState.signedInWithBiometrics = true
                biometricState.offlineBiometricProof = true
                setLoginLoading(true)
                showWelcomeAndContinue()
            } else {
                signIn(credentials.first, credentials.second)
            }
        }, onError = {
            revealLoginContentAfterBiometric()
            setLoginLoading(false)
        })
        if (!startPrompt) return
        try {
            prompt.authenticate(
                biometricPromptInfo(getString(R.string.biometria_login_titulo)),
                BiometricPrompt.CryptoObject(checkNotNull(cipher))
            )
        } catch (_: Exception) {
            biometricState.clearPrompt()
            revealLoginContentAfterBiometric()
            setLoginLoading(false)
            Toast.makeText(this, "No se pudo iniciar la autenticación biométrica.", Toast.LENGTH_LONG).show()
        }
    }

    private fun revealLoginContentAfterBiometric() {
        if (!biometricLockUiHidden || !::loginContent.isInitialized) return
        loginContent.visibility = View.VISIBLE
        loginContent.alpha = 1f
        biometricLockUiHidden = false
    }

    private fun hasValidatedInternet(): Boolean = try {
        val cm = getSystemService(ConnectivityManager::class.java)
            ?: throw IllegalStateException("Connectivity service unavailable")
        val network = cm.activeNetwork
        val caps = network?.let(cm::getNetworkCapabilities)
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (_: Exception) {
        // If detection fails, require the online validators rather than trusting a cache.
        true
    }

    private fun canUnlockOffline(email: String): Boolean {
        val currentUser = auth.currentUser ?: return false
        val profile = session.getProfile() ?: return false
        return currentUser.email.equals(email, ignoreCase = true) &&
            profile.uid == currentUser.uid && profile.status == "approved" &&
            DeviceBindingStore(this).isBoundTo(currentUser.uid) &&
            LoginFlowPolicy.hasFreshOfflineValidation(
                session.getLastOnlineValidationMs(), System.currentTimeMillis()
            )
    }

    private fun invalidateBiometricsIfUnusable(error: Exception): Boolean {
        val permanent = generateSequence<Throwable>(error) { it.cause }.any {
            it is KeyPermanentlyInvalidatedException || it is UnrecoverableKeyException ||
                it is AEADBadTagException || it is CorruptBiometricCredentialsException
        }
        if (!permanent) return false
        biometricCredentials.clear()
        cbEnableBiometrics.isChecked = false
        btnBiometricLogin.visibility = View.GONE
        return true
    }

    private fun createBiometricPrompt(
        onSuccess: (Cipher) -> Unit,
        onError: () -> Unit = {}
    ): BiometricPrompt =
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (isFinishing || isDestroyed) return
                biometricState.clearPrompt()
                val cipher = result.cryptoObject?.cipher
                if (cipher == null) {
                    onError()
                    Toast.makeText(this@LoginActivity, "No se pudo completar la autenticación.", Toast.LENGTH_SHORT).show()
                    return
                }
                onSuccess(cipher)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (isFinishing || isDestroyed) return
                biometricState.clearPrompt()
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    Toast.makeText(this@LoginActivity, "Autenticación biométrica cancelada o no disponible.", Toast.LENGTH_SHORT).show()
                }
                onError()
            }
        })

    private fun biometricPromptInfo(description: String): BiometricPrompt.PromptInfo =
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("BlackHawk Maintenance")
            .setSubtitle(description)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText(getString(R.string.biometria_prompt_cancelar))
            .build()

    private fun signIn(email: String, password: String) {
        setLoginLoading(true)
        biometricState.email = email
        biometricState.password = password
        biometricState.signedInWithBiometrics = signedInWithBiometrics
        val task = auth.signInWithEmailAndPassword(email, password)
        biometricState.signInTask = task
        observeSignIn(task)
    }

    private fun observeSignIn(signInTask: Task<AuthResult>) {
        signInTask.addOnCompleteListener { task ->
                if (isFinishing || isDestroyed) return@addOnCompleteListener
                biometricState.signInTask = null
                if (!task.isSuccessful) {
                    val exception = task.exception
                    if (signedInWithBiometrics && exception is FirebaseAuthException &&
                        exception.errorCode in setOf(
                            "ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL", "ERROR_INVALID_LOGIN_CREDENTIALS"
                        )) {
                        biometricState.credentialsNeedRefresh = true
                    }
                    pendingEmail = null
                    pendingPassword = null
                    etPassword.text.clear()
                    revealLoginContentAfterBiometric()
                    setLoginLoading(false)
                    signedInWithBiometrics = false
                    biometricState.clearLoginAttempt()
                    if (exception is FirebaseAuthException && exception.errorCode == "ERROR_USER_DISABLED") {
                        goSuspended()
                        return@addOnCompleteListener
                    }
                    Toast.makeText(this, "Acceso denegado. Usa tu contraseña.", Toast.LENGTH_SHORT).show()
                    return@addOnCompleteListener
                }
                pendingEmail = biometricState.email
                pendingPassword = biometricState.password
                val uid = auth.currentUser?.uid
                if (uid == null) {
                    failClosedToLogin("No se pudo confirmar la sesión.")
                } else {
                    biometricState.validatingUid = uid
                    verifyUserStatusAndRoute(uid)
                }
            }
    }

    private fun forgetSavedUser() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Olvidar usuario")
            .setMessage("Se eliminarán los datos locales y se desvinculará este dispositivo de la cuenta.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Olvidar") { _, _ ->
                beginForgetSavedUser()
            }
            .show()
    }

    private fun beginForgetSavedUser() {
        val currentUid = auth.currentUser?.uid
        if (currentUid != null && SessionFlags.loginCompleted) {
            releaseDeviceAndClearLocally(currentUid)
            return
        }

        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString()
        if (password.isBlank() && biometricCredentials.hasCredentials()) {
            authenticateBiometricForDeviceRelease()
            return
        }
        if (email.isBlank() || password.isBlank()) {
            Toast.makeText(this, getString(R.string.necesita_autenticarse_para_desvincular), Toast.LENGTH_LONG).show()
            return
        }

        signInForDeviceRelease(email, password)
    }

    private fun authenticateBiometricForDeviceRelease(startPrompt: Boolean = true) {
        if (startPrompt && biometricState.operation != null) return
        val cipher = if (!startPrompt) null else runCatching { biometricCredentials.prepareDecryption() }.getOrElse {
            Toast.makeText(this, getString(R.string.necesita_autenticarse_para_desvincular), Toast.LENGTH_LONG).show()
            return
        }
        biometricState.operation = LoginBiometricState.Operation.RELEASE

        val prompt = createBiometricPrompt(onSuccess = { unlockedCipher ->
            val credentials = runCatching { biometricCredentials.decrypt(unlockedCipher) }.getOrElse {
                Toast.makeText(this, "No se pudieron recuperar las credenciales.", Toast.LENGTH_LONG).show()
                return@createBiometricPrompt
            }
            signInForDeviceRelease(credentials.first, credentials.second)
        }, onError = {})

        if (!startPrompt) return
        runCatching {
            prompt.authenticate(
                biometricPromptInfo(getString(R.string.biometria_login_titulo)),
                BiometricPrompt.CryptoObject(checkNotNull(cipher))
            )
        }.onFailure {
            biometricState.clearPrompt()
            Toast.makeText(this, "No se pudo iniciar la autenticación biométrica.", Toast.LENGTH_LONG).show()
        }
    }

    private fun signInForDeviceRelease(email: String, password: String) {
        val task = auth.signInWithEmailAndPassword(email, password)
        biometricState.releaseSignInTask = task
        observeReleaseSignIn(task)
    }

    private fun observeReleaseSignIn(task: Task<AuthResult>) {
        setLoginLoading(true)
        task.addOnCompleteListener(this) { result ->
            if (isFinishing || isDestroyed) return@addOnCompleteListener
            if (biometricState.releaseSignInTask !== task) return@addOnCompleteListener
            biometricState.releaseSignInTask = null
            etPassword.text.clear()
            val uid = if (result.isSuccessful) result.result.user?.uid else null
            if (uid != null) releaseDeviceAndClearLocally(uid)
            else forgetReleaseFailed("No se pudo confirmar la cuenta.")
        }
    }

    private fun releaseDeviceAndClearLocally(uid: String) {
        biometricState.releaseTask?.let {
            observeDeviceRelease(it)
            return
        }
        val completion = TaskCompletionSource<Unit>()
        biometricState.releaseTask = completion.task
        observeDeviceRelease(completion.task)

        // Finish local cleanup without retaining the Activity; the task survives rotation.
        val appContext = applicationContext
        DeviceBindingVerifier.release(
            context = appContext,
            uid = uid,
            onSuccess = {
                runCatching {
                    DeviceBindingStore(appContext).clearBinding()
                    AuthStateCleaner.clear(appContext, clearSavedEmail = true)
                }.onSuccess {
                    completion.trySetResult(Unit)
                }.onFailure {
                    completion.trySetException(it as? Exception ?: Exception(it))
                }
            },
            onFailure = { completion.trySetException(it) }
        )
    }

    private fun observeDeviceRelease(task: Task<Unit>) {
        setLoginLoading(true)
        tvLoginStatus.text = getString(R.string.desvinculando_dispositivo)
        tvLoginStatusDetail.text = getString(R.string.desvinculando_dispositivo)
        task.addOnCompleteListener(this) { result ->
            if (isFinishing || isDestroyed) return@addOnCompleteListener
            if (biometricState.releaseTask !== task) return@addOnCompleteListener
            biometricState.releaseTask = null
            if (result.isSuccessful) {
                SessionFlags.suppressBiometricAutoPromptAfterLogout = false
                revealLoginContentAfterBiometric()
                etEmail.text.clear()
                etPassword.text.clear()
                cbEnableBiometrics.isChecked = false
                btnBiometricLogin.visibility = View.GONE
                updateEmailLock(false)
                setLoginLoading(false)
                Toast.makeText(this, getString(R.string.dispositivo_desvinculado), Toast.LENGTH_SHORT).show()
            } else {
                forgetReleaseFailed()
            }
        }
    }

    private fun forgetReleaseFailed(message: String = getString(R.string.no_se_pudo_desvincular_dispositivo)) {
        biometricState.releaseSignInTask = null
        biometricState.releaseTask = null
        AuthStateCleaner.clear(this, clearBiometrics = false)
        biometricState.clearLoginAttempt()
        pendingEmail = null
        pendingPassword = null
        etPassword.text.clear()
        revealLoginContentAfterBiometric()
        setLoginLoading(false)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        emailLockNotice?.dismiss()
        slideJob?.cancel()
        if (::imgBgA.isInitialized) cancelImageMatrixAnimator(imgBgA)
        if (::imgBgB.isInitialized) cancelImageMatrixAnimator(imgBgB)
        statusTransitionJob?.cancel()
        profileStatusStageJob?.cancel()
        profileStatusStageReady?.cancel()
        pendingEmail = null
        pendingPassword = null
        if (::etPassword.isInitialized) etPassword.text.clear()
    }

    private fun goToStartup(
        forceUpdateVerification: Boolean = processBiometricLock,
        accessAlreadyValidated: Boolean = true
    ) {
        if (forceUpdateVerification) {
            // La huella se valida desde LoginActivity, pero la comprobación OTA
            // pertenece a StartupActivity. Reiniciamos solo este flag de sesión
            // para que StartupActivity no salte directamente a MainActivity.
            hideLoginStatus(animate = false)
            SessionFlags.startupCompleted = false
            startActivity(Intent(this, StartupActivity::class.java).apply {
                putExtra(StartupActivity.EXTRA_FORCE_UPDATE_VERIFICATION, true)
                if (accessAlreadyValidated) {
                    putExtra(StartupActivity.EXTRA_ACCESS_ALREADY_VALIDATED, true)
                }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
            return
        }

        val hasSelectedPackage = !Prefs.getPackage(this).isNullOrBlank()

        if (hasSelectedPackage) {
            // LoginActivity ya terminó la autenticación y la validación del perfil.
            // El paquete también está seleccionado, así que no repetimos StartupActivity
            // (Firestore + OTA + espera del botón Entrar) después de la animación final.
            hideLoginStatus(animate = false)
            SessionFlags.startupCompleted = true
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
        } else {
            // Primer acceso o instalación sin paquete configurado: todavía se necesita
            // StartupActivity para que el usuario seleccione IADS/SIKORSKY.
            setLoginLoading(false)
            startActivity(Intent(this, StartupActivity::class.java).apply {
                if (accessAlreadyValidated) {
                    putExtra(StartupActivity.EXTRA_ACCESS_ALREADY_VALIDATED, true)
                }
            })
        }
        finish()
    }

    private fun setLoginLoading(loading: Boolean) {
        if (loading) {
            statusTransitionJob?.cancel()
            showLoginStatus(
                title = getString(R.string.iniciando_sesion),
                detail = getString(R.string.validando_credenciales),
                loading = true
            )
        } else {
            hideLoginStatus()
        }
        if (::loginProgress.isInitialized) {
            loginProgress.isClickable = loading
        }
        if (::btnLogin.isInitialized) btnLogin.isEnabled = !loading
        if (::btnBiometricLogin.isInitialized) btnBiometricLogin.isEnabled = !loading
        if (::cbEnableBiometrics.isInitialized) cbEnableBiometrics.isEnabled = !loading
        if (::etEmail.isInitialized) etEmail.isEnabled = !loading
        if (::etPassword.isInitialized) etPassword.isEnabled = !loading
        if (::tvGoRegister.isInitialized) tvGoRegister.isEnabled = !loading
        if (::tvForgetUser.isInitialized) tvForgetUser.isEnabled = !loading
    }

    private fun updateEmailLock(locked: Boolean) {
        emailLocked = locked
        etEmail.keyListener = if (locked) null else editableEmailKeyListener
        etEmail.isFocusable = !locked
        etEmail.isFocusableInTouchMode = !locked
        etEmail.isCursorVisible = !locked
        etEmail.isLongClickable = !locked
        etEmail.showSoftInputOnFocus = !locked
        etEmail.importantForAutofill = if (locked) View.IMPORTANT_FOR_AUTOFILL_NO else View.IMPORTANT_FOR_AUTOFILL_AUTO
        etEmail.alpha = if (locked) 0.82f else 1f
        if (!locked) emailLockNotice?.dismiss()
    }

    private fun configurePasswordToggle() {
        updatePasswordToggleIcon()
        etPassword.setOnTouchListener { _, event ->
            if (event.action != MotionEvent.ACTION_UP ||
                event.x < etPassword.width - etPassword.compoundPaddingEnd
            ) {
                return@setOnTouchListener false
            }

            passwordVisible = !passwordVisible
            val selection = etPassword.selectionStart.coerceAtLeast(0)
            etPassword.inputType = if (passwordVisible) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            etPassword.setSelection(selection.coerceAtMost(etPassword.text.length))
            updatePasswordToggleIcon()
            true
        }
    }

    private fun updatePasswordToggleIcon() {
        val icon = AppCompatResources.getDrawable(this, R.drawable.ic_visibility_24)
            ?.mutate()
            ?: return
        DrawableCompat.setTint(icon, Color.WHITE)
        etPassword.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, icon, null)
        etPassword.contentDescription = getString(R.string.mostrar_ocultar_contrasena)
    }

    private fun showLoginStatus(title: String, detail: String, loading: Boolean) {
        if (!::loginProgress.isInitialized) return

        val statusKey = "$title|$detail"
        if (currentStatusKey != statusKey) {
            currentStatusKey = statusKey
            statusStageStartedAt = SystemClock.elapsedRealtime()
        }
        tvLoginStatus.text = title
        tvLoginStatusDetail.text = detail
        loginStatusSpinner.visibility = if (loading) View.VISIBLE else View.GONE
        loginStatusSuccess.visibility = if (loading) View.GONE else View.VISIBLE

        if (loginProgress.visibility != View.VISIBLE) {
            loginProgress.alpha = 0f
            loginProgress.scaleX = 0.96f
            loginProgress.scaleY = 0.96f
            loginProgress.visibility = View.VISIBLE
            loginProgress.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(180L)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }
    }

    private fun hideLoginStatus(animate: Boolean = true) {
        if (!::loginProgress.isInitialized) return
        statusTransitionJob?.cancel()
        profileStatusStageJob?.cancel()
        profileStatusStageJob = null
        profileStatusStageReady?.cancel()
        profileStatusStageReady = null
        currentStatusKey = null
        statusStageStartedAt = 0L
        loginProgress.animate().cancel()

        if (animate && loginProgress.isVisible) {
            loginProgress.animate()
                .alpha(0f)
                .setDuration(140L)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    loginProgress.visibility = View.GONE
                    loginProgress.alpha = 1f
                }
                .start()
        } else {
            loginProgress.visibility = View.GONE
            loginProgress.alpha = 1f
        }
    }

    private fun runAfterLoginStatusMinimum(action: () -> Unit) {
        statusTransitionJob?.cancel()
        val elapsed = if (statusStageStartedAt == 0L) {
            STATUS_STAGE_MIN_MS
        } else {
            SystemClock.elapsedRealtime() - statusStageStartedAt
        }
        val remaining = (STATUS_STAGE_MIN_MS - elapsed).coerceAtLeast(0L)
        statusTransitionJob = lifecycleScope.launch {
            delay(remaining.milliseconds)
            if (isActive) action()
        }
    }

    // ---------------- Slideshow helpers ----------------

    private fun nextSlideRes(): Int {
        if (startupSlides.isEmpty()) return 0

        val allSlides = startupSlides.toList()
        var candidates = allSlides.toMutableList()

        if (candidates.size > 1) {
            lastSlideRes?.let { candidates.remove(it) }
        }

        val withoutRecent = candidates.filterNot { it in recentSlideRes }
        if (withoutRecent.isNotEmpty()) {
            candidates = withoutRecent.toMutableList()
        }

        if (candidates.isEmpty()) {
            candidates = allSlides.toMutableList()
            if (candidates.size > 1) {
                lastSlideRes?.let { candidates.remove(it) }
            }
        }

        val res = candidates.random()
        rememberSlide(res)
        lastSlideRes = res
        return res
    }

    private fun rememberSlide(slideRes: Int) {
        recentSlideRes.add(slideRes)
        while (recentSlideRes.size > RECENT_SLIDE_MEMORY) {
            recentSlideRes.removeAt(0)
        }
    }

    private fun startBackgroundSlideshow() {
        if (startupSlides.isEmpty()) return

        if (imgBgA.width == 0 || imgBgA.height == 0) {
            imgBgA.post { startBackgroundSlideshow() }
            return
        }

        val firstRes = nextSlideRes()
        imgBgA.setImageResource(firstRes)
        imgBgA.alpha = 1f
        imgBgB.alpha = 0f
        showingA = true

        val firstEffect = pickEffect(imgBgA, firstRes)
        var currentDuration = effectDurationMs(imgBgA, firstRes, firstEffect)
        applyEffect(
            v = imgBgA,
            slideRes = firstRes,
            effect = firstEffect,
            durationMs = currentDuration
        )

        slideJob?.cancel()
        slideJob = lifecycleScope.launch {
            while (isActive) {
                val delayMs = (currentDuration - FADE_MS).coerceAtLeast(FADE_MS)
                delay(delayMs.milliseconds)

                val nextRes = nextSlideRes()

                val incoming = if (showingA) imgBgB else imgBgA
                val outgoing = if (showingA) imgBgA else imgBgB

                incoming.setImageResource(nextRes)
                resetTransform(incoming)
                incoming.alpha = 0f

                val nextEffect = pickEffect(incoming, nextRes)
                val nextDuration = effectDurationMs(incoming, nextRes, nextEffect)

                applyEffect(
                    v = incoming,
                    slideRes = nextRes,
                    effect = nextEffect,
                    durationMs = nextDuration
                )

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

                currentDuration = nextDuration
                showingA = !showingA
            }
        }
    }

    private fun resetTransform(v: ImageView) {
        cancelImageMatrixAnimator(v)
        v.animate().cancel()
        v.scaleType = ImageView.ScaleType.CENTER_CROP
        v.imageMatrix = Matrix()
        v.pivotX = v.width / 2f
        v.pivotY = v.height / 2f
        v.scaleX = 1f
        v.scaleY = 1f
        v.translationX = 0f
        v.translationY = 0f
        v.rotation = 0f
    }

    private fun isFullPanSlide(slideRes: Int): Boolean {
        return slideRes == R.drawable.startup_01 ||
                slideRes == R.drawable.startup_03 ||
                slideRes == R.drawable.startup_05 ||
                slideRes == R.drawable.startup_06 ||
                slideRes == R.drawable.startup_07
    }

    private fun pickEffect(v: ImageView, slideRes: Int): SlideEffect {
        val bounds = if (isFullPanSlide(slideRes)) calculatePanBounds(v) else null
        val baseOptions = if (isFullPanSlide(slideRes)) {
            buildFullPanEffectOptions(bounds)
        } else {
            mutableListOf(
                SlideEffect.ZOOM,
                SlideEffect.PAN_DIAGONAL_LR,
                SlideEffect.PAN_RL,
                SlideEffect.PAN_LR
            )
        }

        val available = filteredEffectOptions(baseOptions)

        val pool = mutableListOf<SlideEffect>()
        for (effect in available) {
            val weight = effectWeight(effect, bounds)
            repeat(weight) { pool.add(effect) }
        }

        val chosen = pool.ifEmpty { available }.random()
        rememberEffect(chosen)
        lastEffect = chosen
        return chosen
    }

    private fun buildFullPanEffectOptions(bounds: SlidePanBounds?): MutableList<SlideEffect> {
        if (bounds == null) {
            return mutableListOf(
                SlideEffect.PAN_FULL_LR,
                SlideEffect.PAN_FULL_RL,
                SlideEffect.PAN_FULL_TB,
                SlideEffect.PAN_FULL_BT
            )
        }

        val options = mutableListOf<SlideEffect>()
        if (bounds.horizontalTravel >= FULL_PAN_MIN_TRAVEL_PX) {
            options.add(SlideEffect.PAN_FULL_LR)
            options.add(SlideEffect.PAN_FULL_RL)
        }
        if (bounds.verticalTravel >= FULL_PAN_MIN_TRAVEL_PX) {
            options.add(SlideEffect.PAN_FULL_TB)
            options.add(SlideEffect.PAN_FULL_BT)
        }

        if (options.isEmpty()) {
            options.add(SlideEffect.PAN_FULL_LR)
            options.add(SlideEffect.PAN_FULL_RL)
        }

        return options
    }

    private fun filteredEffectOptions(baseOptions: MutableList<SlideEffect>): MutableList<SlideEffect> {
        var available = baseOptions.filterNot { it in recentEffectRes }.toMutableList()
        if (available.isEmpty()) available = baseOptions.toMutableList()

        if (available.size > 1) {
            lastEffect?.let { available.remove(it) }
        }

        if (available.isEmpty()) available = baseOptions.toMutableList()
        return available
    }

    private fun effectWeight(effect: SlideEffect, bounds: SlidePanBounds?): Int {
        return when (effect) {
            SlideEffect.ZOOM -> 2
            SlideEffect.PAN_DIAGONAL_LR -> 2
            SlideEffect.PAN_RL,
            SlideEffect.PAN_LR -> 2

            SlideEffect.PAN_FULL_LR,
            SlideEffect.PAN_FULL_RL -> {
                if (bounds == null || bounds.horizontalTravel >= bounds.verticalTravel) 2 else 1
            }

            SlideEffect.PAN_FULL_TB,
            SlideEffect.PAN_FULL_BT -> {
                if (bounds != null && bounds.verticalTravel > bounds.horizontalTravel) 2 else 1
            }
        }
    }

    private fun rememberEffect(effect: SlideEffect) {
        recentEffectRes.add(effect)
        while (recentEffectRes.size > RECENT_EFFECT_MEMORY) {
            recentEffectRes.removeAt(0)
        }
    }

    private fun effectDurationMs(v: ImageView, slideRes: Int, effect: SlideEffect): Long {
        if (!isFullPanSlide(slideRes)) {
            val factor = Random.nextDouble(0.82, 1.32)
            return (LEGACY_SLIDE_DURATION_MS * factor).roundToLong()
        }

        val bounds = calculatePanBounds(v)
        val travelPx = fullPanTravelPx(bounds, effect)

        val baseDuration = if (travelPx >= FULL_PAN_MIN_TRAVEL_PX) {
            ((travelPx / FULL_PAN_TARGET_PX_PER_SECOND) * 1000f)
                .roundToLong()
                .coerceIn(FULL_PAN_MIN_DURATION_MS, FULL_PAN_MAX_DURATION_MS)
        } else {
            SLIDE_DURATION_MS.coerceIn(FULL_PAN_MIN_DURATION_MS, FULL_PAN_MAX_DURATION_MS)
        }

        val subtleVariation = Random.nextDouble(0.97, 1.04)
        return (baseDuration * subtleVariation)
            .roundToLong()
            .coerceIn(FULL_PAN_MIN_DURATION_MS, FULL_PAN_MAX_DURATION_MS)
    }

    private fun fullPanTravelPx(bounds: SlidePanBounds?, effect: SlideEffect): Float {
        if (bounds == null) return 0f

        return when (effect) {
            SlideEffect.PAN_FULL_LR,
            SlideEffect.PAN_FULL_RL -> bounds.horizontalTravel

            SlideEffect.PAN_FULL_TB,
            SlideEffect.PAN_FULL_BT -> bounds.verticalTravel

            SlideEffect.ZOOM,
            SlideEffect.PAN_DIAGONAL_LR,
            SlideEffect.PAN_RL,
            SlideEffect.PAN_LR -> 0f
        }.coerceAtLeast(0f)
    }

    private fun applyEffect(v: ImageView, slideRes: Int, effect: SlideEffect, durationMs: Long) {
        if (isFullPanSlide(slideRes)) {
            applyFullPanEffect(v, effect, durationMs)
        } else {
            applyLegacyEffect(v, effect, durationMs)
        }
    }

    private fun applyLegacyEffect(v: ImageView, effect: SlideEffect, durationMs: Long) {
        cancelImageMatrixAnimator(v)
        v.scaleType = ImageView.ScaleType.CENTER_CROP
        v.imageMatrix = Matrix()

        val w = (v.width.takeIf { it > 0 } ?: 1080)
        val h = (v.height.takeIf { it > 0 } ?: 1920)

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

                v.pivotX = w / 2f
                v.pivotY = h / 2f

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

                v.pivotX = w / 2f
                v.pivotY = h / 2f

                v.scaleX = 1.16f
                v.scaleY = 1.16f
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
                v.pivotX = w / 2f
                v.pivotY = h / 2f

                v.scaleX = 1.14f
                v.scaleY = 1.14f
                v.translationX = dxMax
                v.translationY = 0f

                v.animate()
                    .translationX(-dxMax)
                    .setDuration(durationMs)
                    .setInterpolator(interp)
                    .start()
            }

            SlideEffect.PAN_LR -> {
                v.pivotX = w / 2f
                v.pivotY = h / 2f

                v.scaleX = 1.14f
                v.scaleY = 1.14f
                v.translationX = -dxMax
                v.translationY = 0f

                v.animate()
                    .translationX(dxMax)
                    .setDuration(durationMs)
                    .setInterpolator(interp)
                    .start()
            }

            SlideEffect.PAN_FULL_LR,
            SlideEffect.PAN_FULL_RL,
            SlideEffect.PAN_FULL_TB,
            SlideEffect.PAN_FULL_BT -> Unit
        }
    }

    private fun applyFullPanEffect(v: ImageView, effect: SlideEffect, durationMs: Long) {
        val bounds = calculatePanBounds(v) ?: return

        val startX: Float
        val endX: Float
        val startY: Float
        val endY: Float

        when (effect) {
            SlideEffect.PAN_FULL_LR -> {
                startX = bounds.minX
                endX = bounds.maxX
                startY = bounds.centerY
                endY = bounds.centerY
            }

            SlideEffect.PAN_FULL_RL -> {
                startX = bounds.maxX
                endX = bounds.minX
                startY = bounds.centerY
                endY = bounds.centerY
            }

            SlideEffect.PAN_FULL_TB -> {
                startX = bounds.centerX
                endX = bounds.centerX
                startY = bounds.minY
                endY = bounds.maxY
            }

            SlideEffect.PAN_FULL_BT -> {
                startX = bounds.centerX
                endX = bounds.centerX
                startY = bounds.maxY
                endY = bounds.minY
            }

            SlideEffect.ZOOM,
            SlideEffect.PAN_DIAGONAL_LR,
            SlideEffect.PAN_RL,
            SlideEffect.PAN_LR -> return
        }

        animateImagePan(
            image = v,
            bounds = bounds,
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            durationMs = durationMs
        )
    }

    private fun calculatePanBounds(v: ImageView): SlidePanBounds? {
        val drawable = v.drawable ?: return null
        val drawableWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: return null
        val drawableHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: return null
        val viewWidth = v.width.takeIf { it > 0 } ?: return null
        val viewHeight = v.height.takeIf { it > 0 } ?: return null

        val scale = maxOf(
            viewWidth.toFloat() / drawableWidth.toFloat(),
            viewHeight.toFloat() / drawableHeight.toFloat()
        )
        val scaledWidth = drawableWidth * scale
        val scaledHeight = drawableHeight * scale

        return SlidePanBounds(
            scale = scale,
            minX = viewWidth - scaledWidth,
            maxX = 0f,
            minY = viewHeight - scaledHeight,
            maxY = 0f
        )
    }

    private fun animateImagePan(
        image: ImageView,
        bounds: SlidePanBounds,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ) {
        cancelImageMatrixAnimator(image)
        image.scaleType = ImageView.ScaleType.MATRIX
        applyImageMatrix(image, bounds, startX, startY)

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                val t = animation.animatedValue as Float
                val x = startX + ((endX - startX) * t)
                val y = startY + ((endY - startY) * t)
                applyImageMatrix(image, bounds, x, y)
            }
        }

        setImageMatrixAnimator(image, animator)
        animator.start()
    }

    private fun applyImageMatrix(
        image: ImageView,
        bounds: SlidePanBounds,
        x: Float = bounds.centerX,
        y: Float = bounds.centerY
    ) {
        val matrix = Matrix().apply {
            postScale(bounds.scale, bounds.scale)
            postTranslate(x, y)
        }
        image.imageMatrix = matrix
    }

    private fun cancelImageMatrixAnimator(image: ImageView) {
        when (image) {
            imgBgA -> {
                val animator = image.getTag(R.id.imgBgA) as? ValueAnimator
                animator?.cancel()
                image.setTag(R.id.imgBgA, null)
            }

            imgBgB -> {
                val animator = image.getTag(R.id.imgBgB) as? ValueAnimator
                animator?.cancel()
                image.setTag(R.id.imgBgB, null)
            }
        }
    }

    private fun setImageMatrixAnimator(image: ImageView, animator: ValueAnimator) {
        when (image) {
            imgBgA -> image.setTag(R.id.imgBgA, animator)
            imgBgB -> image.setTag(R.id.imgBgB, animator)
        }
    }
}
