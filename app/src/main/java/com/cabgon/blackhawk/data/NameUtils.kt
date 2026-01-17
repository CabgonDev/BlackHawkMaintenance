package com.cabgon.blackhawk.util

/**
 * Devuelve el "primer apellido" a partir de un nombre completo.
 *
 * Ej:
 *  "Juan Carlos Pérez López" -> "Pérez"
 *  "Pérez López" -> "Pérez"
 *  "Juan" -> "Juan"
 */
fun extractPaternalLastName(fullName: String): String {
    val parts = fullName.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
    if (parts.isEmpty()) return ""
    if (parts.size == 1) return parts[0]
    // En MX normalmente: [Nombres...] [ApellidoPaterno] [ApellidoMaterno]
    return parts[parts.size - 2]
}
