package com.cabgon.blackhawk.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.addCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.cabgon.blackhawk.BuildConfig
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.content.frequencies.FrequenciesFragment
import com.cabgon.blackhawk.content.generalities.GeneralitiesFragment
import com.cabgon.blackhawk.data.user.UserSessionStore
import com.cabgon.blackhawk.data.user.AuthStateCleaner
import com.cabgon.blackhawk.data.user.AppPresenceReporter
import com.cabgon.blackhawk.data.user.DeviceBindingStore
import com.cabgon.blackhawk.databinding.ActivityMainBinding
import com.cabgon.blackhawk.ui.admin.AdminHomeFragment
import com.cabgon.blackhawk.ui.enruta.EnRutaListFragment
import com.cabgon.blackhawk.ui.fragments.AiQueryFragment
import com.cabgon.blackhawk.ui.fragments.InspectionsHomeFragment
import com.cabgon.blackhawk.ui.fragments.ManualsFragment
import com.cabgon.blackhawk.ui.fragments.PartNumbersFragment
import com.cabgon.blackhawk.ui.fragments.PreflightListFragment
import com.cabgon.blackhawk.ui.gallery.GalleryFragment
import com.cabgon.blackhawk.ui.pdf.PdfViewerActivity
import com.cabgon.blackhawk.util.LogoutBus
import com.cabgon.blackhawk.util.Prefs
import com.cabgon.blackhawk.util.Roles
import com.cabgon.blackhawk.util.SessionFlags
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var toggle: ActionBarDrawerToggle

    private var statusReg: ListenerRegistration? = null
    private val db by lazy { FirebaseFirestore.getInstance() }

    private val navHistory = ArrayList<String>(16)
    private val MAX_HISTORY = 25
    private var restorePdfPosted = false

    private val tagToMenuId: Map<String, Int> = mapOf(
        TAG_AI_QUERY to R.id.nav_ai,
        TAG_ADMIN_HOME to R.id.nav_admin_panel,
        TAG_FREQUENCIES to R.id.nav_frequencies,
        TAG_GENERALITIES to R.id.nav_generalities,
        TAG_PART_NUMBERS to R.id.nav_parts,
        TAG_MANUALS to R.id.nav_manuals,
        TAG_GALLERY to R.id.nav_gallery,
        TAG_PREFLIGHT to R.id.nav_preflight,
        TAG_INSPECTIONS_HOME to R.id.nav_inspections,
        TAG_EN_RUTA to R.id.nav_en_ruta
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState.takeIf {
            SessionFlags.loginCompleted && FirebaseAuth.getInstance().currentUser != null
        })

        val authUser = FirebaseAuth.getInstance().currentUser
        if (authUser == null) {
            startActivity(
                Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            )
            finish()
            return
        }

        // MainActivity may be restored directly by Android after process death.
        // Route through StartupActivity so the new process must authenticate first.
        if (!SessionFlags.loginCompleted) {
            startActivity(
                Intent(this, StartupActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            )
            finish()
            return
        }

        if (Prefs.getPackage(this).isNullOrBlank()) {
            SessionFlags.startupCompleted = false

            startActivity(
                Intent(this, StartupActivity::class.java).apply {
                    putExtra(StartupActivity.EXTRA_FORCE_SELECT_PKG, true)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            )
            finish()
            return
        }

        if (!DeviceBindingStore(this).isBoundTo(authUser.uid)) {
            SessionFlags.startupCompleted = false
            startActivity(
                Intent(this, StartupActivity::class.java).apply {
                    putExtra(StartupActivity.EXTRA_FORCE_UPDATE_VERIFICATION, true)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            )
            finish()
            return
        }

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        setupToolbarAndDrawer()
        setupBackDispatcher()
        setupBackStackListener()

        startStatusKickListener(authUser.uid)

        val session = UserSessionStore(this)
        val role = session.getProfile()?.role ?: Roles.USER

        val isAdminOrDev = Roles.isAtLeast(role, Roles.ADMIN)
        val isModeratorPlus = Roles.isAtLeast(role, Roles.MODERATOR)
        val lastMainSection = Prefs.getLastMainSection(this)

        if (BuildConfig.DEBUG) {
            Log.d(
                "ROLE",
                "MainActivity role='$role' level=${Roles.level(role)} " +
                        "isModeratorPlus=$isModeratorPlus isAdminOrDev=$isAdminOrDev"
            )
        }

        b.navView.menu.findItem(R.id.nav_admin_panel)?.isVisible = isModeratorPlus

        b.navView.setNavigationItemSelectedListener { item ->
            val handled = handleNavSelection(item.itemId, isModeratorPlus)
            if (handled) b.drawerLayout.closeDrawer(GravityCompat.START)
            handled
        }

        if (savedInstanceState != null) {
            val restored = savedInstanceState.getStringArrayList(STATE_NAV_HISTORY) ?: arrayListOf()
            navHistory.clear()
            navHistory.addAll(restored.take(MAX_HISTORY))

            syncCheckedWithCurrentFragment()

            val currentTag = supportFragmentManager.findFragmentById(R.id.fragmentContainer)?.tag
            if (!currentTag.isNullOrBlank()) {
                if (navHistory.isEmpty()) {
                    navHistory.add(currentTag)
                } else if (navHistory.last() != currentTag) {
                    navHistory.add(currentTag)
                    trimHistoryIfNeeded()
                }
            }
        } else {
            val initialTag = lastMainSection
                ?.takeIf { tag ->
                    tagToMenuId.containsKey(tag) &&
                        (tag != TAG_ADMIN_HOME || isModeratorPlus)
                }
                ?: TAG_AI_QUERY

            if (initialTag == TAG_AI_QUERY) {
                swapRoot(TAG_AI_QUERY, R.id.nav_ai, recordHistory = true) { AiQueryFragment() }
            } else {
                swapByTag(initialTag)
            }
        }

        if (intent.getBooleanExtra(EXTRA_RESTORE_LAST_PDF, false)) {
            restoreLastPdfIfRequested()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_RESTORE_LAST_PDF, false)) {
            restoreLastPdfIfRequested()
        }
    }

    override fun onStart() {
        super.onStart()
        AppPresenceReporter.report()
    }

    override fun onDestroy() {
        super.onDestroy()
        statusReg?.remove()
        statusReg = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_NAV_HISTORY, ArrayList(navHistory))
    }

    private fun startStatusKickListener(uid: String) {
        statusReg?.remove()

        statusReg = db.collection("users").document(uid)
            .addSnapshotListener { doc, e ->
                if (LogoutBus.isLoggingOut()) return@addSnapshotListener

                if (e != null) {
                    if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        forceKickToSuspended()
                    }
                    return@addSnapshotListener
                }

                if (doc == null || !doc.exists()) return@addSnapshotListener

                val status = doc.getString("status")?.trim()?.lowercase() ?: ""
                if (status != "approved") {
                    forceKickToSuspended()
                }
            }
    }

    private fun forceKickToSuspended() {
        hideKeyboardAndClearFocus()
        LogoutBus.beginLogout()
        SessionFlags.startupCompleted = false
        SessionFlags.loginCompleted = false
        Prefs.clearLastMainSection(this)
        Prefs.clearLastPdfState(this)

        statusReg?.remove()
        statusReg = null

        supportFragmentManager.findFragmentById(R.id.fragmentContainer)?.let { f ->
            supportFragmentManager.beginTransaction()
                .remove(f)
                .commitAllowingStateLoss()
        }

        AuthStateCleaner.clear(this)
        Prefs.clearPackage(this)

        startActivity(
            Intent(this, SuspendedActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
        finish()
    }

    private fun setupBackDispatcher() {
        onBackPressedDispatcher.addCallback(this) {
            if (b.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                b.drawerLayout.closeDrawer(GravityCompat.START)
                return@addCallback
            }

            val fm = supportFragmentManager

            // 1) Primero respeta el back stack real de fragments (detalles, viewers, subpantallas)
            if (fm.backStackEntryCount > 0) {
                fm.popBackStack()
                return@addCallback
            }

            // 2) Si no hay subpantallas, entonces navega entre raíces del drawer
            if (navHistory.size > 1) {
                navHistory.removeAt(navHistory.lastIndex)
                val prevTag = navHistory.last()
                swapByTag(prevTag)
                return@addCallback
            }

            finish()
        }
    }

    private fun setupBackStackListener() {
        supportFragmentManager.addOnBackStackChangedListener {
            syncCheckedWithCurrentFragment()
        }
    }

    private fun swapByTag(tag: String) {
        val menuId = tagToMenuId[tag] ?: 0
        when (tag) {
            TAG_AI_QUERY -> swapRoot(TAG_AI_QUERY, menuId, recordHistory = false) { AiQueryFragment() }
            TAG_ADMIN_HOME -> swapRoot(TAG_ADMIN_HOME, menuId, recordHistory = false) { AdminHomeFragment() }
            TAG_FREQUENCIES -> swapRoot(TAG_FREQUENCIES, menuId, recordHistory = false) { FrequenciesFragment() }
            TAG_GENERALITIES -> swapRoot(TAG_GENERALITIES, menuId, recordHistory = false) { GeneralitiesFragment() }
            TAG_PART_NUMBERS -> swapRoot(TAG_PART_NUMBERS, menuId, recordHistory = false) { PartNumbersFragment() }
            TAG_MANUALS -> swapRoot(TAG_MANUALS, menuId, recordHistory = false) { ManualsFragment() }
            TAG_GALLERY -> swapRoot(TAG_GALLERY, menuId, recordHistory = false) { GalleryFragment() }
            TAG_PREFLIGHT -> swapRoot(TAG_PREFLIGHT, menuId, recordHistory = false) { PreflightListFragment() }
            TAG_INSPECTIONS_HOME -> swapRoot(TAG_INSPECTIONS_HOME, menuId, recordHistory = false) { InspectionsHomeFragment() }
            TAG_EN_RUTA -> swapRoot(TAG_EN_RUTA, menuId, recordHistory = false) { EnRutaListFragment() }
        }
    }

    private fun handleNavSelection(itemId: Int, isModeratorPlus: Boolean): Boolean {
        return when (itemId) {
            R.id.nav_ai ->
                swapRoot(TAG_AI_QUERY, R.id.nav_ai, recordHistory = true) { AiQueryFragment() }

            R.id.nav_admin_panel -> {
                if (!isModeratorPlus) false
                else swapRoot(TAG_ADMIN_HOME, R.id.nav_admin_panel, recordHistory = true) { AdminHomeFragment() }
            }

            R.id.nav_frequencies ->
                swapRoot(TAG_FREQUENCIES, R.id.nav_frequencies, recordHistory = true) { FrequenciesFragment() }

            R.id.nav_generalities ->
                swapRoot(TAG_GENERALITIES, R.id.nav_generalities, recordHistory = true) { GeneralitiesFragment() }

            R.id.nav_parts ->
                swapRoot(TAG_PART_NUMBERS, R.id.nav_parts, recordHistory = true) { PartNumbersFragment() }

            R.id.nav_manuals ->
                swapRoot(TAG_MANUALS, R.id.nav_manuals, recordHistory = true) { ManualsFragment() }

            R.id.nav_gallery ->
                swapRoot(TAG_GALLERY, R.id.nav_gallery, recordHistory = true) { GalleryFragment() }

            R.id.nav_preflight ->
                swapRoot(TAG_PREFLIGHT, R.id.nav_preflight, recordHistory = true) { PreflightListFragment() }

            R.id.nav_inspections ->
                swapRoot(TAG_INSPECTIONS_HOME, R.id.nav_inspections, recordHistory = true) { InspectionsHomeFragment() }

            R.id.nav_en_ruta ->
                swapRoot(TAG_EN_RUTA, R.id.nav_en_ruta, recordHistory = true) { EnRutaListFragment() }

            R.id.nav_switch_pkg -> {
                navHistory.clear()
                SessionFlags.startupCompleted = false
                Prefs.clearLastMainSection(this)
                Prefs.clearLastPdfState(this)
                startActivity(
                    Intent(this, StartupActivity::class.java).apply {
                        putExtra(StartupActivity.EXTRA_FORCE_SELECT_PKG, true)
                    }
                )
                finish()
                true
            }

            R.id.nav_logout -> {
                performLogout()
                true
            }

            else -> false
        }
    }

    private fun setupToolbarAndDrawer() {
        setSupportActionBar(b.toolbar)

        toggle = ActionBarDrawerToggle(
            this,
            b.drawerLayout,
            b.toolbar,
            R.string.app_name,
            R.string.app_name
        )

        b.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        b.drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                applyToolbarWhiteIcons()
                syncCheckedWithCurrentFragment()
            }

            override fun onDrawerClosed(drawerView: View) {
                applyToolbarWhiteIcons()
            }
        })

        applyToolbarWhiteIcons()
    }

    private fun applyToolbarWhiteIcons() {
        b.toolbar.setTitleTextColor(Color.WHITE)
        b.toolbar.setSubtitleTextColor(Color.WHITE)
        toggle.drawerArrowDrawable.color = Color.WHITE
        b.toolbar.overflowIcon?.let { icon -> DrawableCompat.setTint(icon, Color.WHITE) }
    }

    private fun performLogout() {
        hideKeyboardAndClearFocus()
        LogoutBus.beginLogout()
        SessionFlags.startupCompleted = false

        statusReg?.remove()
        statusReg = null

        supportFragmentManager.findFragmentById(R.id.fragmentContainer)?.let { f ->
            supportFragmentManager.beginTransaction()
                .remove(f)
                .commitAllowingStateLoss()
        }

        AuthStateCleaner.clear(this, clearBiometrics = false)
        SessionFlags.suppressBiometricAutoPromptAfterLogout = true
        Prefs.clearLastMainSection(this)
        Prefs.clearLastPdfState(this)

        startActivity(
            Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
        finish()
    }

    private fun swapRoot(tag: String, menuIdToCheck: Int, recordHistory: Boolean, factory: () -> Fragment): Boolean {
        hideKeyboardAndClearFocus()

        val fm = supportFragmentManager
        if (fm.isStateSaved) return false

        // Limpia subpantallas viejas antes de cambiar de sección raíz
        clearFragmentBackStackImmediate(fm)

        val current = fm.findFragmentById(R.id.fragmentContainer)
        if (current?.tag == tag) {
            setCheckedMenuItem(menuIdToCheck)
            Prefs.setLastMainSection(this, tag)
            return true
        }

        val fragment = fm.findFragmentByTag(tag) ?: factory()

        fm.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.fragmentContainer, fragment, tag)
            .commitNow()

        setCheckedMenuItem(menuIdToCheck)

        if (recordHistory) {
            val idx = navHistory.indexOf(tag)
            if (idx >= 0) {
                if (idx < navHistory.lastIndex) {
                    navHistory.subList(idx + 1, navHistory.size).clear()
                }
            } else {
                navHistory.add(tag)
                trimHistoryIfNeeded()
            }
        } else {
            if (navHistory.isEmpty() || navHistory.last() != tag) {
                navHistory.add(tag)
                trimHistoryIfNeeded()
            }
        }

        Prefs.setLastMainSection(this, tag)

        return true
    }

    private fun clearFragmentBackStackImmediate(fm: FragmentManager) {
        if (fm.backStackEntryCount == 0) return
        fm.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
    }

    private fun trimHistoryIfNeeded() {
        if (navHistory.size <= MAX_HISTORY) return
        val extra = navHistory.size - MAX_HISTORY
        if (extra > 0) {
            navHistory.subList(0, extra).clear()
        }
    }

    private fun hideKeyboardAndClearFocus() {
        currentFocus?.clearFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val token = window.decorView.rootView.windowToken ?: return
        imm.hideSoftInputFromWindow(token, 0)
    }

    private fun setCheckedMenuItem(menuItemId: Int) {
        runCatching { b.navView.setCheckedItem(menuItemId) }
    }

    private fun syncCheckedWithCurrentFragment() {
        val currentTag = supportFragmentManager.findFragmentById(R.id.fragmentContainer)?.tag ?: return
        val menuId = tagToMenuId[currentTag] ?: return
        setCheckedMenuItem(menuId)
    }

    private fun restoreLastPdfIfRequested() {
        if (restorePdfPosted || !::b.isInitialized) return
        val state = Prefs.getLastPdfState(this) ?: return

        // Part-number results are already kept by the live PartNumbersFragment.
        // Returning to the root preserves that list without reopening the PDF.
        if (state.showPartSearchLoading) {
            val currentTag = supportFragmentManager
                .findFragmentById(R.id.fragmentContainer)
                ?.tag
            if (currentTag != TAG_PART_NUMBERS) {
                swapRoot(TAG_PART_NUMBERS, R.id.nav_parts, recordHistory = true) {
                    PartNumbersFragment()
                }
            }
            Prefs.clearLastPdfState(this)
            return
        }

        restorePdfPosted = true
        b.root.post {
            restorePdfPosted = false
            if (isFinishing || isDestroyed) return@post

            startActivity(Intent(this, PdfViewerActivity::class.java).apply {
                putExtra(PdfViewerActivity.EXTRA_ASSET_PATH, state.assetPath)
                putExtra(PdfViewerActivity.EXTRA_PAGE, state.page1)
                if (state.highlightQueries.isNotEmpty()) {
                    putStringArrayListExtra(
                        PdfViewerActivity.EXTRA_HIGHLIGHT_QUERIES,
                        ArrayList(state.highlightQueries)
                    )
                }
                if (state.showPartSearchLoading) {
                    putExtra(PdfViewerActivity.EXTRA_SHOW_PART_SEARCH_LOADING, true)
                }
                state.partDescription?.let {
                    putExtra(PdfViewerActivity.EXTRA_PART_DESCRIPTION, it)
                }
                state.partDescriptionNumber?.let {
                    putExtra(PdfViewerActivity.EXTRA_PART_DESCRIPTION_NUMBER, it)
                }
                state.partDescriptionNsn?.let {
                    putExtra(PdfViewerActivity.EXTRA_PART_DESCRIPTION_NSN, it)
                }
            })
        }
    }

    companion object {
        const val EXTRA_RESTORE_LAST_PDF = "restore_last_pdf"
        private const val TAG_AI_QUERY = "AiQuery"
        private const val TAG_ADMIN_HOME = "AdminHome"
        private const val TAG_FREQUENCIES = "Frequencies"
        private const val TAG_GENERALITIES = "Generalities"
        private const val TAG_PART_NUMBERS = "PartNumbers"
        private const val TAG_MANUALS = "Manuals"
        private const val TAG_GALLERY = "Gallery"
        private const val TAG_PREFLIGHT = "Preflight"
        private const val TAG_INSPECTIONS_HOME = "InspectionsHome"
        private const val TAG_EN_RUTA = "EnRuta"
        private const val STATE_NAV_HISTORY = "state_nav_history"
    }
}
