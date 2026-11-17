package com.cabgon.blackhawk.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.data.user.UserSessionStore
import com.cabgon.blackhawk.data.user.BiometricCredentialStore
import com.cabgon.blackhawk.util.LogoutBus
import com.cabgon.blackhawk.util.Prefs
import com.cabgon.blackhawk.util.SessionFlags
import com.google.firebase.auth.FirebaseAuth

class SuspendedActivity : AppCompatActivity() {

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val session by lazy { UserSessionStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_suspended)

        val tv = findViewById<TextView>(R.id.tvSuspendedMsg)
        val btnLogout = findViewById<Button>(R.id.btnLogoutSuspended)

        tv.text = "Tu cuenta ha sido suspendida, ponte en contacto con la seccion administrativa correspondiente"

        btnLogout.setOnClickListener {
            forceLogoutToLogin()
        }
    }

    private fun forceLogoutToLogin() {
        LogoutBus.beginLogout()
        SessionFlags.startupCompleted = false

        auth.signOut()

        // Opción D: limpieza local
        session.clear()
        BiometricCredentialStore(this).clear()
        Prefs.clearPackage(this)

        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }
}
