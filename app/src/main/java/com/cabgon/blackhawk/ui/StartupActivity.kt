package com.cabgon.blackhawk.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Matrix
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.ai.await
import com.cabgon.blackhawk.ai.update.AiUpdateManager
import com.cabgon.blackhawk.ai.update.AppUpdateManager
import com.cabgon.blackhawk.data.PackageManager
import com.cabgon.blackhawk.data.user.UserSessionStore
import com.cabgon.blackhawk.data.user.DeviceBindingVerifier
import com.cabgon.blackhawk.data.user.DeviceBindingStore
import com.cabgon.blackhawk.data.user.AuthStateCleaner
import com.cabgon.blackhawk.data.user.LoginFlowPolicy
import com.cabgon.blackhawk.databinding.ActivityStartupBinding
import com.cabgon.blackhawk.manuals.update.ManualUpdateManager
import com.cabgon.blackhawk.manuals.update.ManualCatalogStore
import com.cabgon.blackhawk.util.ManualCache
import com.cabgon.blackhawk.util.Prefs
import com.cabgon.blackhawk.util.SessionFlags
import com.cabgon.blackhawk.util.DownloadProgressTracker
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.coroutines.resume

class StartupActivity : AppCompatActivity() {

    private lateinit var b: ActivityStartupBinding

    private var readyToEnter = false
    private val downloadProgress = DownloadProgressTracker()
    private var updateJob: Job? = null
    private var updateGeneration = 0
    private var downloadProblemDialog: AlertDialog? = null

    private var splashStartAtMs = 0L
    private var splashCanExit = false
    private var splashReleaseJob: Job? = null
    private var forceUpdateVerification = false

    // Slideshow
    private var slideJob: Job? = null
    private var showingA = true
    private var lastEffect: SlideEffect? = null

    private val db by lazy { FirebaseFirestore.getInstance() }

    // Imágenes base
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
        const val EXTRA_FORCE_SELECT_PKG = "force_select_pkg"
        const val EXTRA_FORCE_UPDATE_VERIFICATION = "force_update_verification"
        const val EXTRA_ACCESS_ALREADY_VALIDATED = "access_already_validated"
        const val SLIDE_DURATION_MS = 6500L
        const val LEGACY_SLIDE_DURATION_MS = 3500L
        const val FULL_PAN_MIN_DURATION_MS = 9_600L
        const val FULL_PAN_MAX_DURATION_MS = 12_000L
        const val FULL_PAN_TARGET_PX_PER_SECOND = 138f
        const val FULL_PAN_MIN_TRAVEL_PX = 24f
        const val RECENT_SLIDE_MEMORY = 2
        const val RECENT_EFFECT_MEMORY = 2
        const val FADE_MS = 650L
        const val MIN_SPLASH_DURATION_MS = 1150L
        const val SPLASH_CONTENT_HOLD_MS = 80L
        const val SPLASH_EXIT_ANIM_MS = 360L
        const val ACCESS_CHECK_TIMEOUT_MS = 5_500L
        const val APP_CHECK_TIMEOUT_MS = 5_500L
        const val MIN_USABLE_STARTUP_DOWNLINK_KBPS = 512
        const val AI_UPDATE_TIMEOUT_MS = 120_000L
        const val MANUAL_UPDATE_TIMEOUT_MS = 300_000L
        const val DOWNLOAD_PREFLIGHT_TIMEOUT_MS = 15_000L
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

    override fun onCreate(savedInstanceState: Bundle?) {
        val launchingAfterAuthentication =
            intent.getBooleanExtra(EXTRA_FORCE_UPDATE_VERIFICATION, false)
        splashStartAtMs = SystemClock.elapsedRealtime()
        splashCanExit = launchingAfterAuthentication
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { !splashCanExit }
        super.onCreate(savedInstanceState)

        if (!launchingAfterAuthentication) {
            splashScreen.setOnExitAnimationListener { splashScreenViewProvider ->
                splashScreenViewProvider.view.animate()
                    .alpha(0f)
                    .scaleX(1.06f)
                    .scaleY(1.06f)
                    .setDuration(SPLASH_EXIT_ANIM_MS)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .withEndAction { splashScreenViewProvider.remove() }
                    .start()
            }
        }

        forceUpdateVerification = launchingAfterAuthentication
        val accessAlreadyValidated = intent.getBooleanExtra(EXTRA_ACCESS_ALREADY_VALIDATED, false)

        val currentUser = FirebaseAuth.getInstance().currentUser
        if (prepareAuthMigration(hasAuthenticatedSession = currentUser != null)) {
            // Keep the Firebase session only long enough to identify the account.
            // LoginActivity signs it out, requires the password once, and the
            // migration is completed only after claimDevice succeeds.
            Prefs.clearLastMainSection(this)
            Prefs.clearLastPdfState(this)
            navigateAfterSplash {
                startActivity(Intent(this, LoginActivity::class.java).apply {
                    putExtra(LoginActivity.EXTRA_REQUIRE_CREDENTIALS, true)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }
            return
        }

        if (currentUser == null || !SessionFlags.loginCompleted) {
            // These values are only for returning from the launcher while this
            // process is alive. A new process must start at Consultas after the
            // required biometric/password authentication.
            Prefs.clearLastMainSection(this)
            Prefs.clearLastPdfState(this)
            navigateAfterSplash {
                startActivity(Intent(this, LoginActivity::class.java).apply {
                    putExtra(LoginActivity.EXTRA_PROCESS_BIOMETRIC_LOCK, !SessionFlags.loginCompleted)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }
            return
        }

        // An existing installation from before device binding has no local key yet.
        // Require a normal login so the approved account can claim this installation
        // instead of treating the migration as a suspended account.
        if (!DeviceBindingStore(this).hasKey()) {
            navigateAfterSplash {
                startActivity(Intent(this, LoginActivity::class.java).apply {
                    putExtra(LoginActivity.EXTRA_REQUIRE_CREDENTIALS, true)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }
            return
        }

        val forceSelect = intent.getBooleanExtra(EXTRA_FORCE_SELECT_PKG, false)
        if (SessionFlags.startupCompleted && !forceSelect) {
            if (DeviceBindingStore(this).isBoundTo(currentUser.uid)) {
                goMainDirectNoStartup()
            } else {
                DeviceBindingVerifier.verify(
                    context = this,
                    uid = currentUser.uid,
                    allowClaim = false,
                    onSuccess = { if (!isFinishing && !isDestroyed) goMainDirectNoStartup() },
                    onFailure = { if (!isFinishing && !isDestroyed) goToLoginAfterBindingFailure() }
                )
            }
            return
        }

        b = ActivityStartupBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.alpha = 0f

        b.root.doOnPreDraw {
            startBackgroundSlideshow()
            if (b.root.alpha == 0f) {
                b.root.animate()
                    .alpha(1f)
                    .setDuration(220L)
                    .start()
            }
            scheduleSplashRelease()
        }

        if (!accessAlreadyValidated) {
            showProgressUi("Validando acceso…")
        }

        b.btnIads.isEnabled = false
        b.btnSikorsky.isEnabled = false
        b.btnEnter.isEnabled = false

        val startupNetwork = readStartupNetworkStatus(applicationContext)
        if (!startupNetwork.hasValidatedInternet) {
            continueOfflineFlow()
            return
        }

        // LoginActivity already validated the profile and device binding in this process.
        // Keep Startup responsible for package selection and update checks without repeating
        // the access validation screen immediately after a successful login.
        if (accessAlreadyValidated) {
            continueApprovedFlow(forceUpdateVerification)
            return
        }

        // ✅ Gate online
        verifyAccessThenContinue(currentUser.uid, forceUpdateVerification)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun goMainDirectNoStartup() {
        navigateAfterSplash {
            val restoreLastPdf = Prefs.getLastPdfState(this) != null
            val intent = Intent(this, MainActivity::class.java).apply {
                // MainActivity is singleTask. Reuse the existing task when the user
                // taps the launcher icon while the process is still alive; clearing
                // the task here recreated MainActivity and reset the selected section.
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                if (restoreLastPdf) {
                    putExtra(MainActivity.EXTRA_RESTORE_LAST_PDF, true)
                }
            }
            startActivity(intent)
            finish()
        }
    }

    private fun goToLoginAfterBindingFailure() {
        AuthStateCleaner.clear(this, clearBiometrics = false)
        SessionFlags.loginCompleted = false
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }


    private fun scheduleSplashRelease() {
        if (splashCanExit) return

        splashReleaseJob?.cancel()
        splashReleaseJob = lifecycleScope.launch {
            val elapsed = SystemClock.elapsedRealtime() - splashStartAtMs
            val remaining = (MIN_SPLASH_DURATION_MS + SPLASH_CONTENT_HOLD_MS - elapsed)
                .coerceAtLeast(0L)
            if (remaining > 0L) delay(remaining.milliseconds)
            splashCanExit = true
        }
    }

    private fun navigateAfterSplash(action: () -> Unit) {
        splashReleaseJob?.cancel()
        lifecycleScope.launch {
            val elapsed = SystemClock.elapsedRealtime() - splashStartAtMs
            val remaining = (MIN_SPLASH_DURATION_MS - elapsed).coerceAtLeast(0L)
            if (remaining > 0L) delay(remaining.milliseconds)
            splashCanExit = true
            delay(70.milliseconds)
            if (!isFinishing && !isDestroyed) {
                action()
            }
        }
    }

    private fun verifyAccessThenContinue(
        uid: String,
        forceUpdateVerification: Boolean = false
    ) {
        lifecycleScope.launch {
            val doc = try {
                withTimeoutOrNull(ACCESS_CHECK_TIMEOUT_MS.milliseconds) {
                    db.collection("users").document(uid).get(Source.SERVER).await()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (isPermissionDenied(e)) {
                    goSuspended()
                    return@launch
                }
                null
            }

            if (doc == null) {
                if (UserSessionStore(this@StartupActivity).getProfile() != null) {
                    continueOfflineFlow()
                    return@launch
                }

                navigateAfterSplash {
                    startActivity(Intent(this@StartupActivity, PendingApprovalActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                    finish()
                }
                return@launch
            }

            if (!doc.exists() || !UserSessionStore(this@StartupActivity).saveProfileFromDocument(doc, uid)) {
                goSuspended()
                return@launch
            }

            val status = doc.getString("status")?.trim()?.lowercase() ?: ""

            when (status) {
                "approved" -> DeviceBindingVerifier.verify(
                    context = this@StartupActivity,
                    uid = uid,
                    allowClaim = false,
                    onSuccess = { if (!isFinishing && !isDestroyed) continueApprovedFlow(forceUpdateVerification) },
                    onFailure = { if (!isFinishing && !isDestroyed) goToLoginAfterBindingFailure() }
                )
                "rejected" -> {
                    navigateAfterSplash {
                        startActivity(Intent(this@StartupActivity, RejectedActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        })
                        finish()
                    }
                }
                "suspended" -> goSuspended()
                else -> {
                    navigateAfterSplash {
                        startActivity(Intent(this@StartupActivity, PendingApprovalActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        })
                        finish()
                    }
                }
            }
        }
    }

    private fun continueApprovedFlow(
        forceUpdateVerification: Boolean = false
    ) {
        b.btnEnter.setOnClickListener {
            if (!readyToEnter) return@setOnClickListener
            goMain()
        }

        val forceSelect = intent.getBooleanExtra(EXTRA_FORCE_SELECT_PKG, false)
        val savedPkgName = Prefs.getPackage(this)

        if (!forceSelect && !savedPkgName.isNullOrBlank()) {
            val pkg = runCatching { PackageManager.Pkg.valueOf(savedPkgName) }.getOrNull()
            if (pkg != null) {
                autoContinueWithPkg(pkg, forceUpdateVerification)
                return
            }
        }

        showSelectionUi()
        b.btnIads.isEnabled = true
        b.btnSikorsky.isEnabled = true

        b.btnIads.setOnClickListener { startWithPkg(PackageManager.Pkg.IADS) }
        b.btnSikorsky.setOnClickListener { startWithPkg(PackageManager.Pkg.SIKORSKY) }
    }

    private fun continueOfflineFlow() {
        val session = UserSessionStore(this)
        val cachedProfile = session.getProfile()

        if (cachedProfile == null) {
            navigateAfterSplash {
                startActivity(Intent(this, PendingApprovalActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }
            return
        }

        val currentUid = FirebaseAuth.getInstance().currentUser?.uid
        if (currentUid == null || cachedProfile.uid != currentUid ||
            !DeviceBindingStore(this).isBoundTo(currentUid)) {
            AuthStateCleaner.clear(this, clearBiometrics = false)
            navigateAfterSplash {
                startActivity(Intent(this@StartupActivity, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }
            return
        }

        val cachedStatus = cachedProfile.status.lowercase()
        if (cachedStatus != "approved") {
            goToLoginAfterBindingFailure()
            return
        }

        val lastOk = session.getLastOnlineValidationMs()
        val now = System.currentTimeMillis()
        if (!LoginFlowPolicy.hasFreshOfflineValidation(lastOk, now)) {
            AuthStateCleaner.clear(this, clearBiometrics = false)

            showProgressUi("Necesitas conexión para validar acceso.")
            lifecycleScope.launch {
                delay(600.milliseconds)
                navigateAfterSplash {
                    startActivity(Intent(this@StartupActivity, LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                    finish()
                }
            }
            return
        }

        if (FirebaseAuth.getInstance().currentUser == null) {
            AuthStateCleaner.clear(this, clearBiometrics = false)
            navigateAfterSplash {
                startActivity(Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }
            return
        }

        b.btnEnter.setOnClickListener {
            if (!readyToEnter) return@setOnClickListener
            goMain()
        }

        val forceSelect = intent.getBooleanExtra(EXTRA_FORCE_SELECT_PKG, false)
        val savedPkgName = Prefs.getPackage(this)

        showProgressUi("Modo offline: acceso limitado…")

        lifecycleScope.launch {
            delay(250.milliseconds)

            if (!forceSelect && !savedPkgName.isNullOrBlank()) {
                val pkg = runCatching { PackageManager.Pkg.valueOf(savedPkgName) }.getOrNull()
                if (pkg != null) {
                    showEnterUi()
                    return@launch
                }
            }

            showSelectionUi()
            b.selectionBlock.visibility = View.VISIBLE
            b.btnIads.isEnabled = true
            b.btnSikorsky.isEnabled = true

            b.btnIads.setOnClickListener { startWithPkgOffline(PackageManager.Pkg.IADS) }
            b.btnSikorsky.setOnClickListener { startWithPkgOffline(PackageManager.Pkg.SIKORSKY) }
        }
    }

    private fun goSuspended() {
        AuthStateCleaner.clear(this)

        navigateAfterSplash {
            startActivity(Intent(this, SuspendedActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        slideJob?.cancel()
        if (::b.isInitialized) {
            cancelImageMatrixAnimator(b.imgBgA)
            cancelImageMatrixAnimator(b.imgBgB)
        }
        splashReleaseJob?.cancel()
        updateJob?.cancel()
        downloadProblemDialog?.dismiss()
    }

    private fun goMain() {
        SessionFlags.startupCompleted = true
        val restoreLastPdf = Prefs.getLastPdfState(this) != null

        val intent = Intent(this@StartupActivity, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            if (restoreLastPdf) {
                putExtra(MainActivity.EXTRA_RESTORE_LAST_PDF, true)
            }
        }
        startActivity(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_OPEN,
                R.anim.fade_in,
                R.anim.fade_out
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }

        finish()
    }

    private fun showSelectionUi() {
        readyToEnter = false
        hideManualDownloadProgress()

        b.selectionBlock.visibility = View.VISIBLE
        b.centerBlock.visibility = View.GONE

        b.pbOta.visibility = View.GONE
        b.txtOtaStatus.visibility = View.GONE
        b.txtBrandTitle.visibility = View.GONE
        b.btnEnter.visibility = View.GONE
    }

    private fun showProgressUi(msg: String) {
        readyToEnter = false
        hideManualDownloadProgress()

        b.selectionBlock.visibility = View.GONE
        b.centerBlock.visibility = View.VISIBLE

        b.pbOta.visibility = View.VISIBLE
        b.txtOtaStatus.visibility = View.VISIBLE
        b.txtOtaStatus.text = msg

        b.txtBrandTitle.visibility = View.GONE
        b.btnEnter.visibility = View.GONE
    }

    private fun showEnterUi(warning: String? = null) {
        readyToEnter = true
        hideManualDownloadProgress()

        b.selectionBlock.visibility = View.GONE
        b.centerBlock.visibility = View.VISIBLE

        b.pbOta.visibility = View.GONE
        b.txtOtaStatus.visibility = if (warning == null) View.GONE else View.VISIBLE
        if (warning != null) b.txtOtaStatus.text = warning

        b.txtBrandTitle.visibility = View.VISIBLE
        b.btnEnter.visibility = View.VISIBLE
        b.btnEnter.isEnabled = true

        b.txtBrandTitle.alpha = 0f
        b.btnEnter.alpha = 0f

        b.txtBrandTitle.animate().alpha(1f).setDuration(220).start()
        b.btnEnter.animate().alpha(1f).setDuration(260).start()
    }

    private fun updateManualDownloadProgress(event: ManualUpdateManager.Event.Downloading) {
        val itemCount = event.itemCount.coerceAtLeast(1)
        val itemIndex = event.itemIndex.coerceIn(1, itemCount)
        val itemProgress = event.progress01.coerceIn(0f, 1f)
        val itemPercent = (itemProgress * 100f).roundToInt()
        downloadProgress.updateManual(itemIndex, itemCount, itemProgress, event.bytesTransferred)

        b.manualProgressBlock.visibility = View.VISIBLE
        b.txtManualTotalProgress.text = getString(
            R.string.startup_manual_total_progress,
            combinedDownloadPercent()
        )
        b.txtManualItemProgress.text = getString(
            R.string.startup_manual_item_progress,
            event.what,
            itemPercent
        )
        b.progressManualTotal.setProgressCompat(combinedDownloadPercent(), true)
        b.progressManualItem.setProgressCompat(itemPercent, true)
    }

    private fun updateAiDownloadProgress(event: AiUpdateManager.Event.Downloading) {
        downloadProgress.updateIndex(event.progress01)
        val percent = (event.progress01.coerceIn(0f, 1f) * 100f).roundToInt()

        b.manualProgressBlock.visibility = View.VISIBLE
        b.txtManualTotalProgress.text = getString(
            R.string.startup_manual_total_progress,
            combinedDownloadPercent()
        )
        b.txtManualItemProgress.text = getString(
            R.string.startup_manual_item_progress,
            event.what,
            percent
        )
        b.progressManualTotal.setProgressCompat(combinedDownloadPercent(), true)
        b.progressManualItem.setProgressCompat(percent, true)
    }

    private fun markAiDownloadComplete() {
        downloadProgress.completeIndex()
        renderTotalDownloadProgress()
    }

    private fun combinedDownloadPercent(): Int {
        return downloadProgress.percent()
    }

    private fun renderTotalDownloadProgress() {
        if (b.manualProgressBlock.visibility != View.VISIBLE) return
        val percent = combinedDownloadPercent()
        b.txtManualTotalProgress.text = getString(R.string.startup_manual_total_progress, percent)
        b.progressManualTotal.setProgressCompat(percent, true)
    }

    private fun hideManualDownloadProgress() {
        if (!::b.isInitialized) return
        b.manualProgressBlock.visibility = View.GONE
        b.progressManualTotal.progress = 0
        b.progressManualItem.progress = 0
    }

    private fun autoContinueWithPkg(
        pkg: PackageManager.Pkg,
        forceUpdateVerification: Boolean = false
    ) {
        runPackageUpdates(pkg, forceUpdateVerification)
    }

    private fun startWithPkg(pkg: PackageManager.Pkg) {
        if (Prefs.getPackage(this) != pkg.name) {
            Prefs.clearLastPdfState(this)
        }
        Prefs.setPackage(this, pkg.name)
        b.btnIads.isEnabled = false
        b.btnSikorsky.isEnabled = false
        runPackageUpdates(pkg, forceUpdateVerification = true)
    }

    private fun runPackageUpdates(pkg: PackageManager.Pkg, forceUpdateVerification: Boolean) {
        downloadProblemDialog?.dismiss()
        updateJob?.cancel()
        val generation = ++updateGeneration
        showProgressUi(getString(R.string.startup_checking_updates))
        downloadProgress.reset()

        updateJob = lifecycleScope.launch {
            val hadCompletePackage = withContext(Dispatchers.IO) {
                hasCompleteLocalPackage(applicationContext, pkg)
            }
            try {
                if (!hasInternet(applicationContext)) {
                    showDownloadProblem(pkg, getString(R.string.startup_downloads_offline))
                    return@launch
                }

                // An optional content-download decision must never bypass a mandatory APK update.
                val appCheck = withTimeoutOrNull(APP_CHECK_TIMEOUT_MS.milliseconds) {
                    AppUpdateManager.check(applicationContext)
                }
                if (appCheck == null) {
                    showDownloadProblem(pkg, getString(R.string.startup_download_check_failed), allowEnter = false)
                    return@launch
                }
                if (appCheck.updateRequired) {
                    navigateAfterSplash {
                        startActivity(Intent(this@StartupActivity, UpdateRequiredActivity::class.java))
                        finish()
                    }
                    return@launch
                }

                if (!forceUpdateVerification && hadCompletePackage &&
                    isConnectionLikelySlow(applicationContext)) {
                    skipRemoteUpdatesForWeakConnection()
                    return@launch
                }

                val plan = estimateDownloadPlan(pkg)
                downloadProgress.reset(plan.indexBytes, plan.manualBytes)
                if (!confirmCellularDownloadIfNeeded(plan)) {
                    showEnterUi(getString(R.string.startup_downloads_skipped))
                    return@launch
                }
                val approvedCellular = isCellularConnection(applicationContext)

                val runAiUpdate: suspend () -> AiUpdateManager.Result = {
                    AiUpdateManager.checkAndUpdateForPackage(applicationContext, pkg) { event ->
                        runOnUiThread {
                            if (generation == updateGeneration && !isFinishing && !isDestroyed) {
                                handleAiDownloadEvent(event)
                            }
                        }
                    }
                }
                val aiResult = if (hadCompletePackage) {
                    withTimeoutOrNull(AI_UPDATE_TIMEOUT_MS.milliseconds) { runAiUpdate() }
                } else {
                    runAiUpdate()
                }
                if (aiResult == null) {
                    showDownloadProblem(pkg, getString(R.string.startup_download_timeout))
                    return@launch
                }
                if (aiResult.forceBlocked) {
                    showDownloadProblem(
                        pkg,
                        getString(R.string.startup_mandatory_update_failed),
                        allowEnter = false
                    )
                    return@launch
                }
                val aiFailed = aiResult.events.any { it is AiUpdateManager.Event.Error }
                if (!aiFailed) markAiDownloadComplete()

                if (!approvedCellular && !confirmCellularDownloadIfNeeded(DownloadPlan(0L, plan.manualBytes))) {
                    showEnterUi(getString(R.string.startup_downloads_skipped))
                    return@launch
                }

                val runManualUpdate: suspend () -> ManualUpdateManager.Result = {
                    ManualUpdateManager.checkAndUpdateForPackage(applicationContext, pkg) { event ->
                        runOnUiThread {
                            if (generation == updateGeneration && !isFinishing && !isDestroyed) {
                                handleManualDownloadEvent(event)
                            }
                        }
                    }
                }
                val manualResult = if (hadCompletePackage) {
                    withTimeoutOrNull(MANUAL_UPDATE_TIMEOUT_MS.milliseconds) { runManualUpdate() }
                } else {
                    runManualUpdate()
                }
                if (manualResult == null) {
                    showDownloadProblem(pkg, getString(R.string.startup_download_timeout))
                    return@launch
                }
                val completeNow = withContext(Dispatchers.IO) {
                    hasCompleteLocalPackage(applicationContext, pkg)
                }
                if (aiFailed || manualResult.hasErrors || !completeNow) {
                    showDownloadProblem(pkg, getString(R.string.startup_downloads_incomplete))
                    return@launch
                }
                downloadProgress.completeManuals()
                renderTotalDownloadProgress()

                b.txtOtaStatus.text = getString(
                    if (aiResult.updatedIndex || manualResult.downloadedCount > 0)
                        R.string.startup_downloads_complete
                    else R.string.startup_manuals_up_to_date
                )
                renderTotalDownloadProgress()
                delay(450.milliseconds)
                showEnterUi()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                showDownloadProblem(pkg, getString(R.string.startup_downloads_incomplete))
            }
        }
    }

    private fun handleAiDownloadEvent(event: AiUpdateManager.Event) {
        when (event) {
            is AiUpdateManager.Event.Downloading -> updateAiDownloadProgress(event)
            is AiUpdateManager.Event.Applied, is AiUpdateManager.Event.UpToDate -> markAiDownloadComplete()
            else -> Unit
        }
        b.txtOtaStatus.text = when (event) {
            is AiUpdateManager.Event.Checking -> getString(R.string.startup_checking_updates)
            is AiUpdateManager.Event.ChannelSelected -> getString(R.string.startup_ota_channel, event.channel)
            is AiUpdateManager.Event.Downloading -> getString(R.string.startup_downloading, event.what)
            is AiUpdateManager.Event.Verifying -> getString(R.string.startup_verifying, event.what)
            is AiUpdateManager.Event.Applied -> getString(R.string.startup_updated, event.what)
            is AiUpdateManager.Event.UpToDate -> getString(R.string.startup_up_to_date, event.what)
            is AiUpdateManager.Event.Skipped -> event.reason
            is AiUpdateManager.Event.Error -> getString(R.string.startup_update_error, event.what, event.message)
        }
    }

    private fun handleManualDownloadEvent(event: ManualUpdateManager.Event) {
        when (event) {
            is ManualUpdateManager.Event.Downloading -> updateManualDownloadProgress(event)
            is ManualUpdateManager.Event.Applied -> {
                downloadProgress.updateManual(event.itemIndex, event.itemCount, 1f)
                b.progressManualItem.setProgressCompat(100, true)
                b.txtManualItemProgress.text = getString(R.string.startup_manual_item_progress, event.what, 100)
                renderTotalDownloadProgress()
            }
            is ManualUpdateManager.Event.UpToDate -> {
                downloadProgress.updateManual(event.itemIndex, event.itemCount, 1f)
                renderTotalDownloadProgress()
            }
            else -> Unit
        }
        b.txtOtaStatus.text = when (event) {
            is ManualUpdateManager.Event.Info -> event.message.trim()
            is ManualUpdateManager.Event.Checking -> getString(R.string.startup_manual_checking)
            is ManualUpdateManager.Event.UpToDate -> getString(R.string.startup_manual_up_to_date, event.what)
            is ManualUpdateManager.Event.Downloading -> getString(
                R.string.startup_downloading_manual_progress, event.what,
                (event.progress01.coerceIn(0f, 1f) * 100f).roundToInt()
            )
            is ManualUpdateManager.Event.Applied -> getString(R.string.startup_manual_updated, event.what)
            is ManualUpdateManager.Event.Error -> getString(R.string.startup_manual_error, event.what, event.message)
        }
    }

    private fun showDownloadProblem(pkg: PackageManager.Pkg, message: String, allowEnter: Boolean = true) {
        if (isFinishing || isDestroyed) return
        b.pbOta.visibility = View.GONE
        b.txtOtaStatus.visibility = View.VISIBLE
        b.txtOtaStatus.text = message
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.startup_download_problem_title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.startup_download_retry) { _, _ ->
                runPackageUpdates(pkg, forceUpdateVerification = true)
            }
        if (allowEnter) {
            builder.setNegativeButton(R.string.entrar) { _, _ ->
                showEnterUi(getString(R.string.startup_downloads_incomplete))
            }
        }
        downloadProblemDialog?.dismiss()
        downloadProblemDialog = builder.show()
    }

    private fun startWithPkgOffline(pkg: PackageManager.Pkg) {
        if (Prefs.getPackage(this) != pkg.name) {
            Prefs.clearLastPdfState(this)
        }
        Prefs.setPackage(this, pkg.name)
        b.btnIads.isEnabled = false
        b.btnSikorsky.isEnabled = false
        showProgressUi("Modo offline: listo para entrar…")
        lifecycleScope.launch {
            delay(250.milliseconds)
            showEnterUi()
        }
    }

    private suspend fun skipRemoteUpdatesForWeakConnection() {
        showProgressUi("Conexión lenta/inestable: omitiendo actualizaciones…")
        delay(250.milliseconds)
        showEnterUi()
    }

    private data class StartupNetworkStatus(
        val hasValidatedInternet: Boolean,
        val estimatedDownKbps: Int,
        val isLikelySlow: Boolean
    )

    private fun readStartupNetworkStatus(ctx: Context): StartupNetworkStatus {
        return try {
            val cm = ctx.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return StartupNetworkStatus(
                hasValidatedInternet = false,
                estimatedDownKbps = 0,
                isLikelySlow = false
            )
            val caps = cm.getNetworkCapabilities(net) ?: return StartupNetworkStatus(
                hasValidatedInternet = false,
                estimatedDownKbps = 0,
                isLikelySlow = false
            )

            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val downKbps = caps.linkDownstreamBandwidthKbps
            val likelySlow = validated && downKbps in 1 until MIN_USABLE_STARTUP_DOWNLINK_KBPS

            StartupNetworkStatus(
                hasValidatedInternet = validated,
                estimatedDownKbps = downKbps,
                isLikelySlow = likelySlow
            )
        } catch (_: Exception) {
            StartupNetworkStatus(
                hasValidatedInternet = true,
                estimatedDownKbps = 0,
                isLikelySlow = false
            )
        }
    }

    private fun isConnectionLikelySlow(ctx: Context): Boolean {
        return readStartupNetworkStatus(ctx).isLikelySlow
    }

    /**
     * Returns true until the one-time device/credential migration for this release
     * completes after a successful claimDevice call.
     */
    private fun prepareAuthMigration(hasAuthenticatedSession: Boolean): Boolean {
        if (!Prefs.AUTH_MIGRATION_ENABLED) return false

        val targetVersion = Prefs.AUTH_MIGRATION_VERSION_CODE
        if (Prefs.getPendingAuthResetVersion(this) == targetVersion) return true
        if (Prefs.getCompletedAuthMigrationVersion(this)?.let { it >= targetVersion } == true) {
            return false
        }
        if (!hasAuthenticatedSession) return false

        Prefs.setPendingAuthResetVersion(this, targetVersion)
        return true
    }

    private fun hasInternet(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) {
            true
        }
    }

    private fun isCellularConnection(ctx: Context): Boolean {
        return runCatching {
            val cm = ctx.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return@runCatching false
            val caps = cm.getNetworkCapabilities(network) ?: return@runCatching false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }.getOrDefault(false)
    }

    private data class DownloadPlan(val indexBytes: Long?, val manualBytes: Long?) {
        val totalBytes: Long?
            get() = if (indexBytes == null || manualBytes == null) null else indexBytes + manualBytes
    }

    private suspend fun estimateDownloadPlan(pkg: PackageManager.Pkg): DownloadPlan {
        return try {
            withTimeoutOrNull(DOWNLOAD_PREFLIGHT_TIMEOUT_MS.milliseconds) {
                DownloadPlan(
                    AiUpdateManager.estimatePendingDownloadBytes(applicationContext, pkg),
                    ManualUpdateManager.estimatePendingDownloadBytes(applicationContext, pkg)
                )
            } ?: DownloadPlan(null, null)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            DownloadPlan(null, null)
        }
    }

    private suspend fun confirmCellularDownloadIfNeeded(plan: DownloadPlan): Boolean {
        if (!isCellularConnection(applicationContext) || plan.totalBytes == 0L) return true
        return askToUseCellularData(plan.totalBytes)
    }

    private suspend fun askToUseCellularData(bytes: Long?): Boolean =
        suspendCancellableCoroutine { cont ->
            var dialog: AlertDialog? = null
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    if (cont.isActive) cont.resume(false)
                    return@runOnUiThread
                }

                dialog = AlertDialog.Builder(this)
                    .setTitle(R.string.startup_cellular_download_title)
                    .setMessage(if (bytes == null) {
                        getString(R.string.startup_cellular_download_unknown)
                    } else {
                        getString(
                            R.string.startup_cellular_download_message,
                            formatDataSize(bytes)
                        )
                    })
                    .setNegativeButton(R.string.startup_cellular_download_cancel) { _, _ ->
                        if (cont.isActive) cont.resume(false)
                    }
                    .setPositiveButton(R.string.startup_cellular_download_continue) { _, _ ->
                        if (cont.isActive) cont.resume(true)
                    }
                    .setOnCancelListener {
                        if (cont.isActive) cont.resume(false)
                    }
                    .create()
                    .also {
                        it.setCanceledOnTouchOutside(false)
                        it.show()
                    }
            }

            cont.invokeOnCancellation {
                runOnUiThread { dialog?.dismiss() }
            }
        }

    private fun formatDataSize(bytes: Long): String {
        val value = bytes.toDouble()
        return when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> String.format(Locale.getDefault(), "%.0f KB", value / 1024.0)
            else -> String.format(Locale.getDefault(), "%.1f MB", value / (1024.0 * 1024.0))
        }
    }

    private fun hasCompleteLocalPackage(
        context: Context,
        pkg: PackageManager.Pkg
    ): Boolean = runCatching {
        val indexName = PackageManager.indexAssetPath(pkg).substringAfterLast('/')
        val indexFile = File(context.filesDir, indexName)
        com.cabgon.blackhawk.util.DownloadedFileCommit.recover(indexFile)
        if (!indexFile.isFile || indexFile.length() <= 0L) return@runCatching false

        val manuals = ManualCatalogStore.resolveManuals(context, pkg)
        manuals.isNotEmpty() && manuals.all { manual ->
            ManualCache.getLocalIfExists(
                context,
                manual.id,
                manual.localRelativePath
            ) != null
        }
    }.getOrDefault(false)

    private fun isPermissionDenied(e: Exception): Boolean {
        val fe = e as? FirebaseFirestoreException ?: return false
        return fe.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
    }

    // ---------- Slideshow ----------

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

        val firstRes = nextSlideRes()
        b.imgBgA.setImageResource(firstRes)
        b.imgBgA.alpha = 1f
        b.imgBgB.alpha = 0f
        showingA = true

        val firstEffect = pickEffect(b.imgBgA, firstRes)
        var currentDuration = effectDurationMs(b.imgBgA, firstRes, firstEffect)
        applyEffect(
            v = b.imgBgA,
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

                val incoming = if (showingA) b.imgBgB else b.imgBgA
                val outgoing = if (showingA) b.imgBgA else b.imgBgB

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
        if (!::b.isInitialized) return

        when (image) {
            b.imgBgA -> {
                val animator = image.getTag(R.id.imgBgA) as? ValueAnimator
                animator?.cancel()
                image.setTag(R.id.imgBgA, null)
            }

            b.imgBgB -> {
                val animator = image.getTag(R.id.imgBgB) as? ValueAnimator
                animator?.cancel()
                image.setTag(R.id.imgBgB, null)
            }
        }
    }

    private fun setImageMatrixAnimator(image: ImageView, animator: ValueAnimator) {
        when (image) {
            b.imgBgA -> image.setTag(R.id.imgBgA, animator)
            b.imgBgB -> image.setTag(R.id.imgBgB, animator)
        }
    }

}
