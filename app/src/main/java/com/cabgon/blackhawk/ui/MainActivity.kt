package com.cabgon.blackhawk.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import com.cabgon.blackhawk.BuildConfig
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.content.frequencies.FrequenciesFragment
import com.cabgon.blackhawk.content.generalities.GeneralitiesFragment
import com.cabgon.blackhawk.data.user.UserSessionStore
import com.cabgon.blackhawk.databinding.ActivityMainBinding
import com.cabgon.blackhawk.ui.admin.AdminHomeFragment
import com.cabgon.blackhawk.ui.chat.ChatFragment
import com.cabgon.blackhawk.ui.enruta.EnRutaListFragment
import com.cabgon.blackhawk.ui.fragments.AiQueryFragment
import com.cabgon.blackhawk.ui.fragments.InDevelopmentFragment
import com.cabgon.blackhawk.ui.fragments.InspectionsHomeFragment
import com.cabgon.blackhawk.ui.fragments.ManualsFragment
import com.cabgon.blackhawk.ui.fragments.PartNumbersFragment
import com.cabgon.blackhawk.ui.fragments.PreflightListFragment
import com.cabgon.blackhawk.util.Prefs
import com.cabgon.blackhawk.util.Roles
import com.google.firebase.auth.FirebaseAuth

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var toggle: ActionBarDrawerToggle

    // Mapa TAG -> itemId del drawer (para mantener "checked" correcto)
    private val tagToMenuId: Map<String, Int> = mapOf(
        TAG_AI_QUERY to R.id.nav_ai,
        TAG_CHAT to R.id.nav_chat,
        TAG_ADMIN_HOME to R.id.nav_admin_panel,
        TAG_FREQUENCIES to R.id.nav_frequencies,
        TAG_GENERALITIES to R.id.nav_generalities,
        TAG_PART_NUMBERS to R.id.nav_parts,
        TAG_MANUALS to R.id.nav_manuals,
        TAG_PREFLIGHT to R.id.nav_preflight,
        TAG_INSPECTIONS_HOME to R.id.nav_inspections,
        TAG_EN_RUTA to R.id.nav_en_ruta,
        TAG_IN_DEV to R.id.nav_chat // InDev marca nav_chat
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Si por algo no hay paquete, regresamos a selección (mejor que cerrar)
        if (Prefs.getPackage(this).isNullOrBlank()) {
            startActivity(
                Intent(this, StartupActivity::class.java).apply {
                    putExtra(StartupActivity.EXTRA_FORCE_SELECT_PKG, true)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            )
            finish()
            return
        }

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        setupToolbarAndDrawer()

        val session = UserSessionStore(this)
        val role = session.getProfile()?.role ?: Roles.USER

        val isAdminOrDev = Roles.isAtLeast(role, Roles.ADMIN)
        val isModeratorPlus = Roles.isAtLeast(role, Roles.MODERATOR)

        if (BuildConfig.DEBUG) {
            Log.d(
                "ROLE",
                "MainActivity role='$role' level=${Roles.level(role)} " +
                        "isModeratorPlus=$isModeratorPlus isAdminOrDev=$isAdminOrDev " +
                        "CHAT_AI_ENABLED=${BuildConfig.CHAT_AI_ENABLED}"
            )
        }

        // Panel Admin: Moderator/Admin/Developer
        b.navView.menu.findItem(R.id.nav_admin_panel)?.isVisible = isModeratorPlus

        b.navView.setNavigationItemSelectedListener { item ->
            val handled = handleNavSelection(item.itemId, isAdminOrDev, isModeratorPlus)
            b.drawerLayout.closeDrawer(GravityCompat.START)
            handled
        }

        // Pantalla inicial
        if (savedInstanceState == null) {
            swap(TAG_AI_QUERY, R.id.nav_ai) { AiQueryFragment() }
        } else {
            syncCheckedWithCurrentFragment()
        }
    }

    private fun handleNavSelection(itemId: Int, isAdminOrDev: Boolean, isModeratorPlus: Boolean): Boolean {
        return when (itemId) {
            R.id.nav_ai ->
                swap(TAG_AI_QUERY, R.id.nav_ai) { AiQueryFragment() }

            R.id.nav_chat -> {
                if (isAdminOrDev) swap(TAG_CHAT, R.id.nav_chat) { ChatFragment() }
                else swap(TAG_IN_DEV, R.id.nav_chat) { InDevelopmentFragment() }
            }

            R.id.nav_admin_panel -> {
                if (!isModeratorPlus) false
                else swap(TAG_ADMIN_HOME, R.id.nav_admin_panel) { AdminHomeFragment() }
            }

            R.id.nav_frequencies ->
                swap(TAG_FREQUENCIES, R.id.nav_frequencies) { FrequenciesFragment() }

            R.id.nav_generalities ->
                swap(TAG_GENERALITIES, R.id.nav_generalities) { GeneralitiesFragment() }

            R.id.nav_parts ->
                swap(TAG_PART_NUMBERS, R.id.nav_parts) { PartNumbersFragment() }

            R.id.nav_manuals ->
                swap(TAG_MANUALS, R.id.nav_manuals) { ManualsFragment() }

            R.id.nav_preflight ->
                swap(TAG_PREFLIGHT, R.id.nav_preflight) { PreflightListFragment() }

            R.id.nav_inspections ->
                swap(TAG_INSPECTIONS_HOME, R.id.nav_inspections) { InspectionsHomeFragment() }

            R.id.nav_en_ruta ->
                swap(TAG_EN_RUTA, R.id.nav_en_ruta) { EnRutaListFragment() }

            R.id.nav_switch_pkg -> {
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

        // Blindaje: algunos temas re-tintean el DrawerArrow y/o cambian estados
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

        // Hamburguesa/flecha (DrawerArrowDrawable)
        toggle.drawerArrowDrawable.color = Color.WHITE

        // Overflow (tres puntitos)
        b.toolbar.overflowIcon?.let { icon ->
            DrawableCompat.setTint(icon, Color.WHITE)
        }
    }

    private fun performLogout() {
        FirebaseAuth.getInstance().signOut()
        UserSessionStore(this).clear()

        startActivity(
            Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
        finish()
    }

    /**
     * Swap robusto + UI cleanup:
     * - Reutiliza fragmentos por TAG.
     * - Evita reemplazar si ya estás en el mismo fragment (por tag).
     * - setReorderingAllowed(true) para transacciones más eficientes.
     * - Animación fade suave.
     * - Evita commit si el estado ya fue guardado.
     * - Mantiene "checked" del drawer sincronizado.
     * - Cierra teclado y limpia foco.
     */
    private fun swap(tag: String, menuIdToCheck: Int, factory: () -> Fragment): Boolean {
        hideKeyboardAndClearFocus()

        val fm = supportFragmentManager
        if (fm.isStateSaved) return false

        val current = fm.findFragmentById(R.id.fragmentContainer)
        if (current?.tag == tag) {
            setCheckedMenuItem(menuIdToCheck)
            return true
        }

        val fragment = fm.findFragmentByTag(tag) ?: factory()

        fm.beginTransaction()
            .setReorderingAllowed(true)
            .setCustomAnimations(
                android.R.anim.fade_in,
                android.R.anim.fade_out
            )
            .replace(R.id.fragmentContainer, fragment, tag)
            .commit()

        setCheckedMenuItem(menuIdToCheck)
        return true
    }

    private fun hideKeyboardAndClearFocus() {
        // Limpia foco para evitar que el teclado reaparezca
        currentFocus?.clearFocus()

        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val token = window.decorView.rootView.windowToken ?: return
        imm.hideSoftInputFromWindow(token, 0)
    }

    private fun setCheckedMenuItem(menuItemId: Int) {
        // Evita crash si el item no existe/está oculto (ej admin panel)
        runCatching {
            b.navView.setCheckedItem(menuItemId)
        }
    }

    private fun syncCheckedWithCurrentFragment() {
        val currentTag = supportFragmentManager.findFragmentById(R.id.fragmentContainer)?.tag ?: return
        val menuId = tagToMenuId[currentTag] ?: return
        setCheckedMenuItem(menuId)
    }

    private companion object {
        private const val TAG_AI_QUERY = "AiQuery"
        private const val TAG_CHAT = "Chat"
        private const val TAG_ADMIN_HOME = "AdminHome"
        private const val TAG_FREQUENCIES = "Frequencies"
        private const val TAG_GENERALITIES = "Generalities"
        private const val TAG_PART_NUMBERS = "PartNumbers"
        private const val TAG_MANUALS = "Manuals"
        private const val TAG_PREFLIGHT = "Preflight"
        private const val TAG_INSPECTIONS_HOME = "InspectionsHome"
        private const val TAG_EN_RUTA = "EnRuta"

        // Para cuando chat no permitido: usamos un tag distinto pero marcamos nav_chat
        private const val TAG_IN_DEV = "InDev"
    }
}
