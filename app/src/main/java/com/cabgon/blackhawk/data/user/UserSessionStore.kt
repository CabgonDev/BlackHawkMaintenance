package com.cabgon.blackhawk.data.user

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.firestore.DocumentSnapshot

class UserSessionStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("user_session", Context.MODE_PRIVATE)

    fun isLoggedIn(): Boolean = prefs.contains(KEY_UID)

    fun saveProfile(profile: UserProfile) {
        prefs.edit()
            .putString(KEY_UID, profile.uid)
            .putString(KEY_EMAIL, profile.email)
            .putString(KEY_GRADO, profile.grado)
            .putString(KEY_NOMBRE, profile.nombre)
            .putString(KEY_MATRICULA, profile.matricula)
            .putString(KEY_ESPECIALIDAD, profile.especialidad)
            .putString(KEY_ROLE, profile.role) // ✅ nuevo
            .apply()
    }

    fun getProfile(): UserProfile? {
        val uid = prefs.getString(KEY_UID, null) ?: return null
        val email = prefs.getString(KEY_EMAIL, null) ?: return null
        val grado = prefs.getString(KEY_GRADO, null) ?: return null
        val nombre = prefs.getString(KEY_NOMBRE, null) ?: return null
        val matricula = prefs.getString(KEY_MATRICULA, null) ?: return null
        val especialidad = prefs.getString(KEY_ESPECIALIDAD, null) ?: return null

        // ✅ default seguro: "user"
        val role = prefs.getString(KEY_ROLE, "user") ?: "user"

        return UserProfile(
            uid = uid,
            email = email,
            grado = grado,
            nombre = nombre,
            matricula = matricula,
            especialidad = especialidad,
            role = role
        )
    }

    fun saveProfileFromDocument(doc: DocumentSnapshot) {
        val uid = doc.getString("uid") ?: return
        val email = doc.getString("email") ?: ""
        val grado = doc.getString("grado") ?: ""
        val nombre = doc.getString("nombre") ?: ""
        val matricula = doc.getString("matricula") ?: ""
        val especialidad = doc.getString("especialidad") ?: ""

        // ✅ nuevo: role (si no existe, cae a "user")
        val role = doc.getString("role") ?: "user"

        val profile = UserProfile(
            uid = uid,
            email = email,
            grado = grado,
            nombre = nombre,
            matricula = matricula,
            especialidad = especialidad,
            role = role
        )

        saveProfile(profile)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_UID = "uid"
        private const val KEY_EMAIL = "email"
        private const val KEY_GRADO = "grado"
        private const val KEY_NOMBRE = "nombre"
        private const val KEY_MATRICULA = "matricula"
        private const val KEY_ESPECIALIDAD = "especialidad"
        private const val KEY_ROLE = "role" // ✅ nuevo
    }
}
