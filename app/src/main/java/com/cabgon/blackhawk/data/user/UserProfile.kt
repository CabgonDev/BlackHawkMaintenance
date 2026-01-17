package com.cabgon.blackhawk.data.user

data class UserProfile(
    val uid: String,
    val email: String,
    val grado: String,
    val nombre: String,
    val matricula: String,
    val especialidad: String,
    val role: String = "user"   // ✅ nuevo
) {
    val iniciales: String
        get() {
            val partes = nombre.trim().split(" ")
            return partes
                .filter { it.isNotBlank() }
                .map { it.first().uppercaseChar() }
                .joinToString("")
        }
}
