package com.cabgon.blackhawk.ui

import com.cabgon.blackhawk.data.user.UserProfile
import org.junit.Assert.*
import org.junit.Test

class RegisterViewModelTest {
    private val draft = RegistrationDraft("technician@example.com", "Cabo", "Test User", "A123", "F.A.E.M.A.")
    private val profile = UserProfile("test-uid", draft.email, draft.grado, draft.nombre,
        draft.matricula, draft.especialidad, role = "user", status = "pending")

    @Test fun `password whitespace is preserved and confirmation must match exactly`() {
        assertTrue(draft.validate(" pass123 ", " pass123 ", true).isEmpty())
        assertEquals(RegistrationFieldError.PASSWORD_MISMATCH,
            draft.validate(" pass123 ", "pass123", true)[RegistrationField.CONFIRMATION])
        val gateway = FakeGateway()
        RegisterViewModel(gateway).submit(draft, " pass123 ")
        assertEquals(" pass123 ", gateway.receivedPassword)
    }

    @Test fun `required fields and email format are checked`() {
        val errors = draft.copy(email = "", nombre = " ", matricula = "").validate("", "", false)
        assertEquals(5, errors.size)
        assertEquals(RegistrationFieldError.INVALID_EMAIL,
            draft.copy(email = "invalid").validate("123456", "123456", false)[RegistrationField.EMAIL])
        assertEquals(RegistrationFieldError.SHORT_PASSWORD,
            draft.validate("12345", "12345", true)[RegistrationField.PASSWORD])
    }

    @Test fun `repeated taps create only one account and cannot abandon in flight`() {
        val gateway = FakeGateway()
        val model = RegisterViewModel(gateway)
        model.submit(draft, "123456")
        model.submit(draft, "123456")
        model.abandon()
        assertEquals(1, gateway.creates)
        assertNull(gateway.abandonedUid)
        assertEquals(RegistrationStage.CREATING_ACCOUNT, model.state.value.stage)
    }

    @Test fun `profile failure retries only the original pending profile`() {
        val gateway = FakeGateway()
        val model = RegisterViewModel(gateway)
        model.submit(draft, "123456")
        gateway.createCallback(Result.success(profile))
        assertEquals(RegistrationStage.SAVING_PROFILE, model.state.value.stage)
        gateway.saveCallback(Result.failure(Exception("offline")))
        assertEquals(RegistrationError.PROFILE_SAVE, model.state.value.error)
        model.submit(draft.copy(email = "another@example.com"), "changed")
        assertEquals(1, gateway.creates)
        assertEquals(2, gateway.saves)
        assertEquals(profile, gateway.savedProfile)
        gateway.saveCallback(Result.success(Unit))
        assertEquals(RegistrationStage.COMPLETE, model.state.value.stage)
        assertEquals("pending", model.state.value.profile?.status)
        assertEquals("user", model.state.value.profile?.role)
        model.retryProfile()
        assertEquals(2, gateway.saves)
    }

    @Test fun `account failure allows retry and incomplete profile abandons only its uid`() {
        val gateway = FakeGateway()
        val model = RegisterViewModel(gateway)
        model.submit(draft, "123456")
        gateway.createCallback(Result.failure(Exception("failed")))
        assertFalse(model.state.value.busy)
        model.submit(draft, "123456")
        assertEquals(2, gateway.creates)
        gateway.createCallback(Result.success(profile))
        gateway.saveCallback(Result.failure(Exception("failed")))
        model.abandon()
        assertEquals(profile.uid, gateway.abandonedUid)
    }

    private class FakeGateway : RegistrationGateway {
        var creates = 0
        var saves = 0
        var receivedPassword: String? = null
        var savedProfile: UserProfile? = null
        var abandonedUid: String? = null
        lateinit var createCallback: (Result<UserProfile>) -> Unit
        lateinit var saveCallback: (Result<Unit>) -> Unit

        override fun create(draft: RegistrationDraft, password: String, callback: (Result<UserProfile>) -> Unit) {
            creates++
            receivedPassword = password
            createCallback = callback
        }
        override fun saveProfile(profile: UserProfile, callback: (Result<Unit>) -> Unit) {
            saves++
            savedProfile = profile
            saveCallback = callback
        }
        override fun abandon(uid: String) { abandonedUid = uid }
    }
}
