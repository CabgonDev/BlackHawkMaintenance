package com.cabgon.blackhawk.data.user

import android.content.Context
import com.cabgon.blackhawk.util.Prefs
import com.cabgon.blackhawk.util.SessionFlags
import com.google.firebase.auth.FirebaseAuth

/** Clears session state; biometric credentials may be retained for a normal logout. */
object AuthStateCleaner {
    fun clear(
        context: Context,
        clearSavedEmail: Boolean = false,
        clearBiometrics: Boolean = true
    ) {
        FirebaseAuth.getInstance().signOut()
        UserSessionStore(context).clear()
        if (clearBiometrics) BiometricCredentialStore(context).clear()
        SessionFlags.biometricUnlockCompleted = false
        SessionFlags.loginCompleted = false
        SessionFlags.startupCompleted = false
        if (clearSavedEmail) Prefs.clearLoginEmail(context)
    }
}
