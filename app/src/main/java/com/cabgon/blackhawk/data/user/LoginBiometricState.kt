package com.cabgon.blackhawk.data.user

import androidx.lifecycle.ViewModel
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.AuthResult

/** Keeps prompt callbacks reconnectable after rotation; secrets never enter a saved Bundle. */
internal class LoginBiometricState : ViewModel() {
    enum class Operation { LOGIN, ENROLL, RELEASE }

    var operation: Operation? = null
    var processRequiresLogin = false
    var credentialsNeedRefresh = false
    var email: String? = null
    var password: String? = null
    var signInTask: Task<AuthResult>? = null
    var validatingUid: String? = null
    var signedInWithBiometrics = false
    var offlineBiometricProof = false
    var releaseSignInTask: Task<AuthResult>? = null
    var releaseTask: Task<Unit>? = null
    val releaseInProgress: Boolean
        get() = releaseSignInTask != null || releaseTask != null

    fun clearLoginAttempt() {
        signInTask = null
        validatingUid = null
        signedInWithBiometrics = false
        offlineBiometricProof = false
        email = null
        password = null
    }

    fun clearPrompt() {
        operation = null
        email = null
        password = null
    }

    override fun onCleared() {
        clearPrompt()
        clearLoginAttempt()
        releaseSignInTask = null
        releaseTask = null
    }
}
