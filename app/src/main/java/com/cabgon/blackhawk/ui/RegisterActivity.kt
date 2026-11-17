package com.cabgon.blackhawk.ui

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.util.Patterns
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.data.user.UserSessionStore
import com.cabgon.blackhawk.databinding.ActivityRegisterBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private lateinit var model: RegisterViewModel
    private var navigating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        model = ViewModelProvider(this)[RegisterViewModel::class.java]
        setupInsets()
        setupFields()
        binding.btnRegisterConfirm.setOnClickListener { submit() }
        binding.tvGoLogin.setOnClickListener { returnToLogin() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = returnToLogin()
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { render(it) }
            }
        }
    }

    private fun setupInsets() {
        val scroll = binding.registerScroll
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            scroll.updateLayoutParams<FrameLayout.LayoutParams> {
                leftMargin = bars.left
                topMargin = bars.top
                rightMargin = bars.right
                bottomMargin = maxOf(bars.bottom, keyboard.bottom)
            }
            if (keyboard.bottom > 0) {
                scroll.doOnLayout {
                    val field = fieldLayouts().firstOrNull { it.editText == currentFocus }
                    if (field != null) {
                        val rectangle = Rect(0, 0, field.width, field.height)
                        scroll.offsetDescendantRectToMyCoords(field, rectangle)
                        val visibleBottom = scroll.scrollY + scroll.height - scroll.paddingBottom
                        val visibleTop = scroll.scrollY + scroll.paddingTop
                        val target = when {
                            rectangle.bottom > visibleBottom -> rectangle.bottom - scroll.height + scroll.paddingBottom
                            rectangle.top < visibleTop -> rectangle.top - scroll.paddingTop
                            else -> scroll.scrollY
                        }
                        scroll.smoothScrollTo(0, target.coerceAtLeast(0))
                    }
                }
            }
            insets
        }
        ViewCompat.requestApplyInsets(scroll)
    }

    private fun setupFields() {
        binding.spGradoReg.adapter = ArrayAdapter(
            this, R.layout.register_spinner_item, resources.getStringArray(R.array.registro_grados)
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spEspecialidadReg.adapter = ArrayAdapter(
            this, R.layout.register_spinner_item, resources.getStringArray(R.array.registro_especialidades)
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        fieldLayouts().forEach { layout ->
            layout.editText?.doAfterTextChanged { layout.error = null }
        }
        binding.etMatriculaReg.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else false
        }
    }

    private fun submit() {
        if (model.state.value.busy) return
        if (model.state.value.profile != null) {
            model.retryProfile()
            return
        }
        val draft = RegistrationDraft(
            email = binding.etEmailReg.text.toString().trim(),
            grado = binding.spGradoReg.selectedItem?.toString().orEmpty(),
            nombre = binding.etNombreReg.text.toString().trim(),
            matricula = binding.etMatriculaReg.text.toString().trim(),
            especialidad = binding.spEspecialidadReg.selectedItem?.toString().orEmpty()
        )
        val password = binding.etPasswordReg.text.toString()
        val errors = draft.validate(
            password, binding.etPasswordConfirm.text.toString(),
            Patterns.EMAIL_ADDRESS.matcher(draft.email).matches()
        )
        fieldLayouts().forEach { it.error = null }
        errors.forEach { (field, error) ->
            inputLayout(field).error = getString(when (error) {
                RegistrationFieldError.REQUIRED -> R.string.registro_error_obligatorio
                RegistrationFieldError.INVALID_EMAIL -> R.string.registro_error_correo
                RegistrationFieldError.SHORT_PASSWORD -> R.string.registro_error_password_corto
                RegistrationFieldError.PASSWORD_MISMATCH -> R.string.registro_error_password_coincidencia
            })
        }
        if (errors.isNotEmpty()) {
            inputLayout(errors.keys.first()).editText?.requestFocus()
            return
        }
        WindowCompat.getInsetsController(window, binding.root).hide(WindowInsetsCompat.Type.ime())
        model.submit(draft, password)
    }

    private fun render(state: RegistrationState) {
        val editable = !state.busy && state.profile == null
        fieldLayouts().forEach { it.isEnabled = editable }
        binding.spGradoReg.isEnabled = editable
        binding.spEspecialidadReg.isEnabled = editable
        binding.btnRegisterConfirm.isEnabled = !state.busy
        binding.btnRegisterConfirm.setText(if (state.profile == null) R.string.solicitar_registro else R.string.registro_reintentar)
        binding.registerProgress.isVisible = state.busy
        binding.txtRegisterStatus.setText(if (state.stage == RegistrationStage.CREATING_ACCOUNT)
            R.string.registro_creando_cuenta else R.string.enviando_solicitud)
        binding.txtRegisterError.isVisible = state.error != null
        state.error?.let {
            binding.txtRegisterError.setText(when (it) {
                RegistrationError.EMAIL_IN_USE -> R.string.registro_error_correo_existente
                RegistrationError.INVALID_EMAIL -> R.string.registro_error_correo
                RegistrationError.WEAK_PASSWORD -> R.string.registro_error_password_corto
                RegistrationError.NETWORK -> R.string.registro_error_red
                RegistrationError.PROFILE_SAVE -> R.string.registro_error_perfil
                RegistrationError.GENERIC -> R.string.registro_error_general
            })
        }
        if (state.stage == RegistrationStage.COMPLETE && !navigating) {
            val profile = state.profile ?: return
            navigating = true
            UserSessionStore(this).saveProfile(profile)
            startActivity(Intent(this, PendingApprovalActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
        }
    }

    private fun returnToLogin() {
        if (model.state.value.busy) {
            Snackbar.make(binding.root, R.string.registro_espera, Snackbar.LENGTH_SHORT).show()
            return
        }
        if (model.state.value.profile != null) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.registro_incompleto_titulo)
                .setMessage(R.string.registro_incompleto_mensaje)
                .setPositiveButton(R.string.registro_reintentar) { _, _ -> model.retryProfile() }
                .setNegativeButton(R.string.registro_salir) { _, _ ->
                    model.abandon()
                    finish()
                }.show()
        } else finish()
    }

    private fun fieldLayouts() = listOf(binding.tilEmailReg, binding.tilPasswordReg,
        binding.tilPasswordConfirmReg, binding.tilNombreReg, binding.tilMatriculaReg)

    private fun inputLayout(field: RegistrationField) = when (field) {
        RegistrationField.EMAIL -> binding.tilEmailReg
        RegistrationField.PASSWORD -> binding.tilPasswordReg
        RegistrationField.CONFIRMATION -> binding.tilPasswordConfirmReg
        RegistrationField.NAME -> binding.tilNombreReg
        RegistrationField.REGISTRATION -> binding.tilMatriculaReg
    }
}
