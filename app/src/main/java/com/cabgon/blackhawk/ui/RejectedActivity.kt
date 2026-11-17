package com.cabgon.blackhawk.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.data.user.UserSessionStore
import com.cabgon.blackhawk.data.user.BiometricCredentialStore
import com.cabgon.blackhawk.util.SessionFlags
import com.google.firebase.auth.FirebaseAuth

class RejectedActivity : AppCompatActivity() {

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val session by lazy { UserSessionStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rejected)

        val tv = findViewById<TextView>(R.id.tvRejectedMsg)
        val btnLogout = findViewById<Button>(R.id.btnLogoutRejected)

        tv.text = "Lo siento, tu acceso ha sido rechazado.\n\nPonte en contacto con la unidad administrativa correspondiente."

        btnLogout.setOnClickListener {
            // ✅ Reset: siguiente login debe pasar por Startup normal
            SessionFlags.startupCompleted = false

            auth.signOut()
            session.clear()
            BiometricCredentialStore(this).clear()
            startActivity(Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
        }
    }
}
