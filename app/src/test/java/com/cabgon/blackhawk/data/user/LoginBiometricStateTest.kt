package com.cabgon.blackhawk.data.user

import com.google.android.gms.tasks.TaskCompletionSource
import com.google.firebase.auth.AuthResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginBiometricStateTest {
    @Test fun newStateHasNoReleaseInProgress() {
        assertFalse(LoginBiometricState().releaseInProgress)
    }

    @Test fun releaseAuthenticationSurvivesLoginAttemptCleanup() {
        val state = LoginBiometricState()
        val task = TaskCompletionSource<AuthResult>().task
        state.releaseSignInTask = task

        state.clearLoginAttempt()
        state.clearPrompt()

        assertSame(task, state.releaseSignInTask)
        assertTrue(state.releaseInProgress)
    }

    @Test fun completedReleaseRemainsAvailableUntilScreenConsumesIt() {
        val state = LoginBiometricState()
        val completion = TaskCompletionSource<Unit>()
        state.releaseTask = completion.task
        completion.setResult(Unit)

        state.clearPrompt()
        state.clearLoginAttempt()

        assertSame(completion.task, state.releaseTask)
        assertTrue(state.releaseTask!!.isSuccessful)
        assertTrue(state.releaseInProgress)
        state.releaseTask = null
        assertFalse(state.releaseInProgress)
    }

    @Test fun failedReleaseIsRetainedForTheRecreatedScreen() {
        val state = LoginBiometricState()
        val completion = TaskCompletionSource<Unit>()
        state.releaseTask = completion.task
        val error = IllegalStateException("Release unavailable")
        completion.setException(error)

        state.clearLoginAttempt()

        assertSame(error, state.releaseTask!!.exception)
        assertTrue(state.releaseInProgress)
    }
}
