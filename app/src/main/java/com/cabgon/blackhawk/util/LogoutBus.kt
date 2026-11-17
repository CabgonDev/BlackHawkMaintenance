package com.cabgon.blackhawk.util

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bandera global para evitar ruido de Firestore cuando se hace signOut.
 * Útil para no mostrar PERMISSION_DENIED en UI al cerrar sesión.
 */
object LogoutBus {
    private val loggingOut = AtomicBoolean(false)

    fun beginLogout() {
        loggingOut.set(true)

        // ✅ Importante: al hacer logout queremos que el siguiente arranque NO brinque Startup.
        // (vive solo en RAM, así que es exactamente "hasta matar proceso")
        SessionFlags.startupCompleted = false
    }

    fun endLogout() {
        loggingOut.set(false)
    }

    fun isLoggingOut(): Boolean = loggingOut.get()
}