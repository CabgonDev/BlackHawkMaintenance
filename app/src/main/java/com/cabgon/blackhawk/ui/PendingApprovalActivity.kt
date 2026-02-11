package com.cabgon.blackhawk.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.data.user.UserSessionStore
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class PendingApprovalActivity : AppCompatActivity() {

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val session by lazy { UserSessionStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pending_approval)

        val tv = findViewById<TextView>(R.id.tvPendingMsg)
        val btnRetry = findViewById<Button>(R.id.btnRetryStatus)
        val btnLogout = findViewById<Button>(R.id.btnLogoutPending)

        val name = session.getProfile()?.nombre ?: "Usuario"
        tv.text = "Hola $name.\n\nTu solicitud de registro se encuentra en validación.\nPor favor sé paciente."

        btnRetry.setOnClickListener { checkAgain() }
        btnLogout.setOnClickListener { logout() }

        // Verificación inicial
        checkAgain()
    }

    private fun checkAgain() {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            goLogin()
            return
        }

        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) return@addOnSuccessListener
                session.saveProfileFromDocument(doc)

                when ((doc.getString("status") ?: "pending").lowercase()) {
                    "approved" -> {
                        Toast.makeText(this, "Bienvenido, tu registro ha sido aprobado.", Toast.LENGTH_LONG).show()
                        startActivity(Intent(this, StartupActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        })
                        finish()
                    }
                    "rejected" -> {
                        startActivity(Intent(this, RejectedActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        })
                        finish()
                    }
                }
            }
    }

    private fun logout() {
        auth.signOut()
        session.clear()
        goLogin()
    }

    private fun goLogin() {
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }
}
