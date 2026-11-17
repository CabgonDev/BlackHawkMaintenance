package com.cabgon.blackhawk.data.user

internal object LoginFlowPolicy {
    private const val OFFLINE_MAX_AGE_MS = 24L * 60L * 60L * 1000L

    fun hasFreshOfflineValidation(lastValidatedAt: Long, now: Long): Boolean =
        lastValidatedAt > 0L && now >= lastValidatedAt &&
            now - lastValidatedAt < OFFLINE_MAX_AGE_MS

    fun shouldPromptBiometrics(
        processRequiresLogin: Boolean,
        requiresPassword: Boolean,
        biometricAvailable: Boolean,
        hasSavedCredentials: Boolean,
        autoPromptSuppressed: Boolean = false
    ): Boolean = processRequiresLogin && !requiresPassword && !autoPromptSuppressed &&
        biometricAvailable && hasSavedCredentials

    // Enrollment is independent of whether this is the first login of a process.
    fun shouldOfferBiometricEnrollment(
        enabled: Boolean,
        email: String?,
        password: String?,
        hasSavedCredentials: Boolean = false,
        credentialsNeedRefresh: Boolean = false
    ): Boolean = enabled && !email.isNullOrBlank() && !password.isNullOrBlank() &&
        (!hasSavedCredentials || credentialsNeedRefresh)
}
