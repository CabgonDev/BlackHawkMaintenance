package com.cabgon.blackhawk.ui

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.data.user.UserProfile
import com.cabgon.blackhawk.data.user.UserSessionStore
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class RegisterActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var session: UserSessionStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        session = UserSessionStore(this)

        // Views
        val etEmail = findViewById<EditText>(R.id.etEmailReg)
        val etPassword = findViewById<EditText>(R.id.etPasswordReg)
        val etPasswordConfirm = findViewById<EditText>(R.id.etPasswordConfirm)
        val spGrado = findViewById<Spinner>(R.id.spGradoReg)
        val etNombre = findViewById<EditText>(R.id.etNombreReg)
        val etMatricula = findViewById<EditText>(R.id.etMatriculaReg)
        val spEspecialidad = findViewById<Spinner>(R.id.spEspecialidadReg)
        val btnRegister = findViewById<Button>(R.id.btnRegisterConfirm)
        val tvGoLogin = findViewById<TextView>(R.id.tvGoLogin)

        // ---------- Opciones de GRADO ----------
        val grados = listOf(
            "Cabo",
            "Sgto. 2/o.",
            "Sgto. 1/o.",
            "Sbtte",
            "Tte",
            "Cap. 2/o.",
            "Cap. 1/o."
        )

        spGrado.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            grados
        )

        // ---------- Opciones de ESPECIALIDAD ----------
        val especialidades = listOf(
            "F.A.E.E.A.",
            "F.A.E.M.A."
        )

        spEspecialidad.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            especialidades
        )

        // ---------- Botón: Crear cuenta ----------
        btnRegister.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()
            val password2 = etPasswordConfirm.text.toString().trim()
            val grado = spGrado.selectedItem?.toString()?.trim().orEmpty()
            val nombre = etNombre.text.toString().trim()
            val matricula = etMatricula.text.toString().trim()
            val especialidad = spEspecialidad.selectedItem?.toString()?.trim().orEmpty()

            // Validaciones básicas
            if (email.isEmpty() || password.isEmpty() || password2.isEmpty()) {
                Toast.makeText(this, "Correo y contraseña son obligatorios", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (password.length < 6) {
                Toast.makeText(this, "La contraseña debe tener al menos 6 caracteres", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (password != password2) {
                Toast.makeText(this, "Las contraseñas no coinciden", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (grado.isEmpty() || nombre.isEmpty() || matricula.isEmpty() || especialidad.isEmpty()) {
                Toast.makeText(this, "Completa todos los datos del técnico", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnRegister.isEnabled = false

            auth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener { task ->
                    if (!task.isSuccessful) {
                        btnRegister.isEnabled = true
                        Toast.makeText(
                            this,
                            "Registro fallido: ${task.exception?.localizedMessage ?: "revisa los datos"}",
                            Toast.LENGTH_LONG
                        ).show()
                        return@addOnCompleteListener
                    }

                    val user = auth.currentUser
                    if (user == null) {
                        btnRegister.isEnabled = true
                        Toast.makeText(this, "Error: usuario nulo tras registro.", Toast.LENGTH_SHORT).show()
                        return@addOnCompleteListener
                    }

                    val profile = UserProfile(
                        uid = user.uid,
                        email = user.email ?: email,
                        grado = grado,
                        nombre = nombre,
                        matricula = matricula,
                        especialidad = especialidad
                    )

                    val data = mapOf(
                        "uid" to profile.uid,
                        "email" to profile.email,
                        "grado" to profile.grado,
                        "nombre" to profile.nombre,
                        "matricula" to profile.matricula,
                        "especialidad" to profile.especialidad,
                        "createdAt" to System.currentTimeMillis()
                    )

                    db.collection("users")
                        .document(user.uid)
                        .set(data)
                        .addOnSuccessListener {
                            session.saveProfile(profile)
                            Toast.makeText(this, "Registro exitoso.", Toast.LENGTH_SHORT).show()
                            goToStartup()
                        }
                        .addOnFailureListener { e ->
                            btnRegister.isEnabled = true
                            Toast.makeText(
                                this,
                                "Error al guardar perfil: ${e.localizedMessage}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                }
        }

        // ---------- Volver al login ----------
        tvGoLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun goToStartup() {
        startActivity(Intent(this, StartupActivity::class.java))
        finish()
    }
}
