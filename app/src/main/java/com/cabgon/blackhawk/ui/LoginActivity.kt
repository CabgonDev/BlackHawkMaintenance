package com.cabgon.blackhawk.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.data.user.UserSessionStore
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var session: UserSessionStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        session = UserSessionStore(this)

        // Si ya está logueado, nos saltamos login
        val currentUser = auth.currentUser
        if (currentUser != null && session.getProfile() != null) {
            goToStartup()
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
                    android.util.Log.d("AUTH", "UID=$uid")
                    db.collection("users").document(uid)
                        .get()
                        .addOnSuccessListener { doc ->
                            if (!doc.exists()) {
                                Toast.makeText(this, "Perfil no encontrado.", Toast.LENGTH_LONG).show()
                                return@addOnSuccessListener
                            }
                            session.saveProfileFromDocument(doc)
                            goToStartup()
                        }
                }
        }

        tvGoRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    private fun goToStartup() {
        startActivity(Intent(this, StartupActivity::class.java))
        finish()
    }
}
