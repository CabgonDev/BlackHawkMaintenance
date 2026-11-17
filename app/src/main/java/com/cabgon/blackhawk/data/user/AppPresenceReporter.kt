package com.cabgon.blackhawk.data.user

import android.os.SystemClock
import android.util.Log
import com.cabgon.blackhawk.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions

/** Reports the installed build for the authenticated user's own profile. */
object AppPresenceReporter {

    private const val FUNCTIONS_REGION = "us-central1"
    private const val REPORT_INTERVAL_MS = 60_000L
    private const val TAG = "AppPresenceReporter"

    private val lock = Any()
    private var lastReportElapsedMs = 0L
    private var lastReportedUid: String? = null

    fun report(force: Boolean = false) {
        val firebaseApp = runCatching { FirebaseApp.getInstance() }.getOrNull() ?: return
        val user = FirebaseAuth.getInstance(firebaseApp).currentUser ?: return
        val now = SystemClock.elapsedRealtime()

        synchronized(lock) {
            val sameUser = lastReportedUid == user.uid
            if (!force && sameUser && now - lastReportElapsedMs < REPORT_INTERVAL_MS) return
            lastReportedUid = user.uid
            lastReportElapsedMs = now
        }

        val payload = mapOf(
            "appVersionCode" to BuildConfig.VERSION_CODE,
            "appVersionName" to BuildConfig.VERSION_NAME
        )

        FirebaseFunctions.getInstance(firebaseApp, FUNCTIONS_REGION)
            .getHttpsCallable("reportAppPresence")
            .call(payload)
            .addOnFailureListener { error ->
                Log.w(TAG, "No se pudo registrar la presencia de la aplicación", error)
            }
    }
}
