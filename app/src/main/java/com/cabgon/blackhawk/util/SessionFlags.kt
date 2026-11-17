package com.cabgon.blackhawk.util

/**
 * Flags en memoria (RAM) que viven mientras el proceso esté vivo.
 * Se reinician cuando el proceso muere.
 */
object SessionFlags {
    @Volatile
    var startupCompleted: Boolean = false

    @Volatile
    var biometricUnlockCompleted: Boolean = false

    /** True after the current process has completed an approved login. */
    @Volatile
    var loginCompleted: Boolean = false

    /** Explicit logout shows the password form; a new process restores automatic biometrics. */
    @Volatile
    var suppressBiometricAutoPromptAfterLogout: Boolean = false
}
