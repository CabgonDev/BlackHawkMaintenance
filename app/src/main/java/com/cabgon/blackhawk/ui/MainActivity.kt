package com.cabgon.blackhawk.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Prefs.getPackage(this) == null) {
            finish()
            return
        }

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        setSupportActionBar(b.toolbar)

        val toggle = ActionBarDrawerToggle(
            this,
            b.drawerLayout,
            b.toolbar,
            R.string.app_name,
            R.string.app_name
        )
        b.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        val session = UserSessionStore(this)
        val role = session.getProfile()?.role ?: Roles.USER

        val isAdminOrDev = Roles.isAtLeast(role, Roles.ADMIN)          // admin o dev
        val isModeratorPlus = Roles.isAtLeast(role, Roles.MODERATOR)   // moderator, admin, dev

        Log.d(
            "ROLE",
            "MainActivity role='$role' level=${Roles.level(role)} isModeratorPlus=$isModeratorPlus isAdminOrDev=$isAdminOrDev CHAT_AI_ENABLED=${BuildConfig.CHAT_AI_ENABLED}"
        )

        // ✅ Panel Admin: Moderator/Admin/Developer
        b.navView.menu.findItem(R.id.nav_admin_panel)?.isVisible = isModeratorPlus

        b.navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {

                R.id.nav_ai -> swap(AiQueryFragment())

                R.id.nav_chat -> {
                    if (isAdminOrDev) swap(ChatFragment())
                    else swap(InDevelopmentFragment())
                }

                R.id.nav_admin_panel -> {
                    if (isModeratorPlus) swap(AdminHomeFragment())
                }

                R.id.nav_frequencies -> swap(FrequenciesFragment())

                // ✅ NUEVO: Generalidades directo desde Drawer
                R.id.nav_generalities -> swap(GeneralitiesFragment())

                R.id.nav_parts -> swap(PartNumbersFragment())
                R.id.nav_manuals -> swap(ManualsFragment())
                R.id.nav_preflight -> swap(PreflightListFragment())
                R.id.nav_inspections -> swap(InspectionsHomeFragment())
                R.id.nav_en_ruta -> swap(EnRutaListFragment())

                R.id.nav_switch_pkg -> {
                    finish()
                    startActivity(
                        Intent(this, StartupActivity::class.java).apply {
                            putExtra(StartupActivity.EXTRA_FORCE_SELECT_PKG, true)
                        }
                    )
                }


                R.id.nav_logout -> performLogout()
            }

            b.drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        if (savedInstanceState == null) swap(AiQueryFragment())
    }

    private fun performLogout() {
        FirebaseAuth.getInstance().signOut()
        UserSessionStore(this).clear()

        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun swap(f: androidx.fragment.app.Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, f)
            .commit()
    }
}
