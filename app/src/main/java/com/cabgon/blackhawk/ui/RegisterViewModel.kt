package com.cabgon.blackhawk.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import com.cabgon.blackhawk.data.user.UserProfile
import com.cabgon.blackhawk.util.SessionFlags
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class RegistrationDraft(
    val email: String,
    val grado: String,
    val nombre: String,
    val matricula: String,
    val especialidad: String
) {
    fun validate(password: String, confirmation: String, emailIsValid: Boolean): Map<RegistrationField, RegistrationFieldError> {
        val errors = linkedMapOf<RegistrationField, RegistrationFieldError>()
        if (email.isBlank()) errors[RegistrationField.EMAIL] = RegistrationFieldError.REQUIRED
        else if (!emailIsValid) errors[RegistrationField.EMAIL] = RegistrationFieldError.INVALID_EMAIL
        if (password.isEmpty()) errors[RegistrationField.PASSWORD] = RegistrationFieldError.REQUIRED
        else if (password.length < 6) errors[RegistrationField.PASSWORD] = RegistrationFieldError.SHORT_PASSWORD
        if (confirmation.isEmpty()) errors[RegistrationField.CONFIRMATION] = RegistrationFieldError.REQUIRED
        else if (password != confirmation) errors[RegistrationField.CONFIRMATION] = RegistrationFieldError.PASSWORD_MISMATCH
        if (nombre.isBlank()) errors[RegistrationField.NAME] = RegistrationFieldError.REQUIRED
        if (matricula.isBlank()) errors[RegistrationField.REGISTRATION] = RegistrationFieldError.REQUIRED
        return errors
    }
}

internal enum class RegistrationField { EMAIL, PASSWORD, CONFIRMATION, NAME, REGISTRATION }
internal enum class RegistrationFieldError { REQUIRED, INVALID_EMAIL, SHORT_PASSWORD, PASSWORD_MISMATCH }
internal enum class RegistrationStage { IDLE, CREATING_ACCOUNT, SAVING_PROFILE, COMPLETE }
internal enum class RegistrationError { EMAIL_IN_USE, INVALID_EMAIL, WEAK_PASSWORD, NETWORK, PROFILE_SAVE, GENERIC }

internal data class RegistrationState(
    val stage: RegistrationStage = RegistrationStage.IDLE,
    val profile: UserProfile? = null,
    val error: RegistrationError? = null
) {
    val busy get() = stage == RegistrationStage.CREATING_ACCOUNT || stage == RegistrationStage.SAVING_PROFILE
}

internal interface RegistrationGateway {
    fun create(draft: RegistrationDraft, password: String, callback: (Result<UserProfile>) -> Unit)
    fun saveProfile(profile: UserProfile, callback: (Result<Unit>) -> Unit)
    fun abandon(uid: String)
}

internal class RegisterViewModel(
    private val gateway: RegistrationGateway = FirebaseRegistrationGateway()
) : ViewModel() {
    private val mutableState = MutableStateFlow(RegistrationState())
    val state = mutableState.asStateFlow()

    fun submit(draft: RegistrationDraft, password: String) {
        if (state.value.busy || state.value.stage == RegistrationStage.COMPLETE) return
        if (state.value.profile != null) {
            retryProfile()
            return
        }
        mutableState.value = RegistrationState(stage = RegistrationStage.CREATING_ACCOUNT)
        // Only non-secret draft data survive rotation. Passwords are passed directly to Auth.
        gateway.create(draft, password) { result ->
            result.fold(onSuccess = { profile ->
                mutableState.value = RegistrationState(profile = profile)
                retryProfile()
            }, onFailure = { error ->
                mutableState.value = RegistrationState(error = (error as? RegistrationException)?.error ?: RegistrationError.GENERIC)
            })
        }
    }

    fun retryProfile() {
        val current = state.value
        val profile = current.profile ?: return
        if (current.busy || current.stage == RegistrationStage.COMPLETE) return
        mutableState.value = current.copy(stage = RegistrationStage.SAVING_PROFILE, error = null)
        gateway.saveProfile(profile) { result ->
            mutableState.value = if (result.isSuccess) RegistrationState(RegistrationStage.COMPLETE, profile)
                else RegistrationState(profile = profile, error = RegistrationError.PROFILE_SAVE)
        }
    }

    fun abandon() {
        if (state.value.busy) return
        state.value.profile?.let { gateway.abandon(it.uid) }
    }
}

private class RegistrationException(val error: RegistrationError, cause: Throwable) : Exception(cause)

private class FirebaseRegistrationGateway : RegistrationGateway {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private var attemptedProfileUid: String? = null

    override fun create(draft: RegistrationDraft, password: String, callback: (Result<UserProfile>) -> Unit) {
        auth.createUserWithEmailAndPassword(draft.email, password)
            .addOnSuccessListener { result ->
                val user = result.user
                if (user == null) {
                    callback(Result.failure(IllegalStateException("Missing registration user")))
                    return@addOnSuccessListener
                }
                SessionFlags.loginCompleted = false
                SessionFlags.startupCompleted = false
                SessionFlags.biometricUnlockCompleted = false
                callback(Result.success(UserProfile(user.uid, user.email ?: draft.email, draft.grado,
                    draft.nombre, draft.matricula, draft.especialidad, role = "user", status = "pending")))
            }.addOnFailureListener { error ->
                Log.w("RegisterActivity", "Account creation failed", error)
                val code = when (error) {
                    is FirebaseAuthUserCollisionException -> RegistrationError.EMAIL_IN_USE
                    is FirebaseAuthWeakPasswordException -> RegistrationError.WEAK_PASSWORD
                    is FirebaseAuthInvalidCredentialsException -> RegistrationError.INVALID_EMAIL
                    is FirebaseNetworkException -> RegistrationError.NETWORK
                    else -> RegistrationError.GENERIC
                }
                callback(Result.failure(RegistrationException(code, error)))
            }
    }

    override fun saveProfile(profile: UserProfile, callback: (Result<Unit>) -> Unit) {
        if (auth.currentUser?.uid != profile.uid) {
            callback(Result.failure(IllegalStateException("Registration session changed")))
            return
        }
        val reference = db.collection("users").document(profile.uid)
        val data = mapOf(
            "uid" to profile.uid, "email" to profile.email,
            "grado" to profile.grado, "nombre" to profile.nombre,
            "matricula" to profile.matricula, "especialidad" to profile.especialidad,
            "role" to "user", "status" to "pending",
            "createdAt" to FieldValue.serverTimestamp(), "reviewedAt" to null,
            "reviewedBy" to null, "rejectionReason" to null
        )
        // Initial creation keeps the existing write-only registration rules contract.
        val saveTask = if (attemptedProfileUid != profile.uid) {
            attemptedProfileUid = profile.uid
            reference.set(data)
        } else db.runTransaction { transaction ->
            // A retry must not overwrite a profile already reviewed by an administrator.
            val existing = transaction.get(reference)
            if (existing.exists()) {
                check(existing.getString("uid") == profile.uid) { "Registration profile identity mismatch" }
                check(existing.getString("status") in setOf("pending", "approved", "rejected", "suspended")) {
                    "Invalid registration profile status"
                }
            } else {
                transaction.set(reference, data)
            }
            null
        }
        saveTask.addOnSuccessListener {
            if (auth.currentUser?.uid == profile.uid) callback(Result.success(Unit))
            else callback(Result.failure(IllegalStateException("Registration session changed")))
        }.addOnFailureListener { error ->
            Log.w("RegisterActivity", "Registration profile could not be saved", error)
            callback(Result.failure(error))
        }
    }

    override fun abandon(uid: String) {
        if (auth.currentUser?.uid == uid) auth.signOut()
    }
}
