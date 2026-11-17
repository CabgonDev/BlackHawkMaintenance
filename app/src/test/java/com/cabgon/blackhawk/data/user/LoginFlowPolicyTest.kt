package com.cabgon.blackhawk.data.user

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginFlowPolicyTest {
    @Test fun coldProcessOffersSavedBiometrics() {
        assertTrue(LoginFlowPolicy.shouldPromptBiometrics(true, false, true, true))
    }

    @Test fun authenticatedProcessDoesNotPromptAgain() {
        assertFalse(LoginFlowPolicy.shouldPromptBiometrics(false, false, true, true))
    }

    @Test fun explicitLogoutShowsPasswordInsteadOfOpeningFingerprint() {
        assertFalse(LoginFlowPolicy.shouldPromptBiometrics(
            true, false, true, true, autoPromptSuppressed = true
        ))
    }

    @Test fun newProcessWithoutLogoutSuppressionOffersSavedFingerprint() {
        assertTrue(LoginFlowPolicy.shouldPromptBiometrics(
            true, false, true, true, autoPromptSuppressed = false
        ))
    }

    @Test fun passwordMigrationDoesNotOpenBiometrics() {
        assertFalse(LoginFlowPolicy.shouldPromptBiometrics(true, true, true, true))
    }

    @Test fun unavailableOrMissingCredentialsUsePassword() {
        assertFalse(LoginFlowPolicy.shouldPromptBiometrics(true, false, false, true))
        assertFalse(LoginFlowPolicy.shouldPromptBiometrics(true, false, true, false))
    }

    @Test fun passwordLoginOffersEnrollmentEvenInColdProcess() {
        assertTrue(LoginFlowPolicy.shouldOfferBiometricEnrollment(true, "user@test.invalid", "secret"))
    }

    @Test fun uncheckedOrMissingPasswordNeverEnrols() {
        assertFalse(LoginFlowPolicy.shouldOfferBiometricEnrollment(false, "user@test.invalid", "secret"))
        assertFalse(LoginFlowPolicy.shouldOfferBiometricEnrollment(true, "user@test.invalid", null))
        assertFalse(LoginFlowPolicy.shouldOfferBiometricEnrollment(true, "", "secret"))
    }

    @Test fun canceledFingerprintCanUsePasswordWithoutReenrolling() {
        assertFalse(LoginFlowPolicy.shouldOfferBiometricEnrollment(
            true, "user@test.invalid", "secret", hasSavedCredentials = true
        ))
    }

    @Test fun rejectedSavedPasswordCanBeRefreshed() {
        assertTrue(LoginFlowPolicy.shouldOfferBiometricEnrollment(
            true, "user@test.invalid", "new-secret", hasSavedCredentials = true,
            credentialsNeedRefresh = true
        ))
    }

    @Test fun offlineRequiresValidationWithin24Hours() {
        val last = 1_000_000L
        val day = 24L * 60L * 60L * 1000L
        assertTrue(LoginFlowPolicy.hasFreshOfflineValidation(last, last + day - 1L))
        assertFalse(LoginFlowPolicy.hasFreshOfflineValidation(last, last + day))
        assertFalse(LoginFlowPolicy.hasFreshOfflineValidation(0L, last))
        assertFalse(LoginFlowPolicy.hasFreshOfflineValidation(last, last - 1L))
    }
}
