package com.cabgon.blackhawk.ui.preflight

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.data.user.UserSessionStore
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Calendar

class PreflightNewDialog(
    private val onCreate: (
        Long,    // fechaMillis
        String,  // hora24
        String,  // matAeronave
        String,  // grado
        String,  // especialidad
        String,  // nombre
        String?, // hsTotales
        String?, // hsDisponibles
        String?  // matricula técnico
    ) -> Unit
) : DialogFragment() {

    @LayoutRes
    private val layoutId = R.layout.dialog_preflight_new

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val v = LayoutInflater.from(requireContext()).inflate(layoutId, null, false)

        // Opciones estáticas (aeronaves, grados, especialidades)
        val matOpts = listOf("1091", "1092", "1093", "1094", "1097", "1098")

        // Grados alineados con el registro
        val gradoOpts = listOf(
            "Cabo",
            "Sgto. 2/o.",
            "Sgto. 1/o.",
            "Sbtte.",
            "Tte",
            "Cap. 2/o.",
            "Cap. 1/o."
        )

        val espOpts = listOf("F.A.E.E.A.", "F.A.E.M.A.")

        fun setAdapter(actv: AutoCompleteTextView, data: List<String>) {
            actv.setAdapter(
                ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_list_item_1,
                    data
                )
            )
        }

        // TextInputLayouts
        val tilMatA = v.findViewById<TextInputLayout>(R.id.tilMatAeronave)
        val tilGrado = v.findViewById<TextInputLayout>(R.id.tilGrado)
        val tilEsp = v.findViewById<TextInputLayout>(R.id.tilEsp)
        val tilTecNom = v.findViewById<TextInputLayout>(R.id.tilTecnicoNombre)

        // Campos de entrada
        val etMatA = v.findViewById<AutoCompleteTextView>(R.id.etMatAeronave)
        val etGrado = v.findViewById<AutoCompleteTextView>(R.id.etGrado)
        val etEsp = v.findViewById<AutoCompleteTextView>(R.id.etEsp)
        val etTecNom = v.findViewById<TextInputEditText>(R.id.etTecnicoNombre)
        val etHsTot = v.findViewById<TextInputEditText>(R.id.etHsTot)
        val etHsDisp = v.findViewById<TextInputEditText>(R.id.etHsDisp)
        val etMatTec = v.findViewById<TextInputEditText>(R.id.etMatTec)

        // Adapters para desplegables
        setAdapter(etMatA, matOpts)
        setAdapter(etGrado, gradoOpts)
        setAdapter(etEsp, espOpts)

        // ---------- Autollenado con el perfil de usuario (login Firebase) ----------
        val session = UserSessionStore(requireContext())
        val profile = session.getProfile()

        if (profile != null) {
            // Grado: si coincide con la lista, lo seleccionamos; si no, lo ponemos como texto igual
            val gradoFromProfile = profile.grado
            if (gradoFromProfile.isNotBlank()) {
                if (gradoOpts.contains(gradoFromProfile)) {
                    etGrado.setText(gradoFromProfile, false)
                } else {
                    etGrado.setText(gradoFromProfile, false)
                }
            } else {
                etGrado.setText(gradoOpts.first(), false)
            }

            // Especialidad
            val espFromProfile = profile.especialidad
            if (espFromProfile.isNotBlank()) {
                if (espOpts.contains(espFromProfile)) {
                    etEsp.setText(espFromProfile, false)
                } else {
                    etEsp.setText(espFromProfile, false)
                }
            } else {
                etEsp.setText(espOpts.first(), false)
            }

            // Nombre completo
            if (profile.nombre.isNotBlank()) {
                etTecNom.setText(profile.nombre)
            }

            // Matrícula técnico
            if (profile.matricula.isNotBlank()) {
                etMatTec.setText(profile.matricula)
            }

            etGrado.isEnabled = false
            etEsp.isEnabled = false
            etTecNom.isEnabled = false
            etMatTec.isEnabled = false

        } else {
            // Si no hay perfil, dejamos defaults en los combos
            etGrado.setText(gradoOpts.first(), false)
            etEsp.setText(espOpts.first(), false)
        }

        // Fecha/hora auto
        val now = Calendar.getInstance()
        val fechaMillis = now.timeInMillis
        val hora24 = "%02d:%02d".format(
            now.get(Calendar.HOUR_OF_DAY),
            now.get(Calendar.MINUTE)
        )
        v.findViewById<TextView>(R.id.tvFechaHoraResumen)?.text =
            "Se registrará: ${formatDate(fechaMillis)} · $hora24"

        return AlertDialog.Builder(requireContext())
            .setView(v)
            .setCancelable(true)
            .setPositiveButton("Crear") { _, _ ->
                var ok = true
                val matAeronave = etMatA.text?.toString()?.trim().orEmpty()
                val grado = etGrado.text?.toString()?.trim().orEmpty()
                val esp = etEsp.text?.toString()?.trim().orEmpty()
                val nombre = etTecNom.text?.toString()?.trim().orEmpty()

                if (matAeronave.isEmpty()) {
                    tilMatA.error = "Requerido"; ok = false
                } else tilMatA.error = null

                if (grado.isEmpty()) {
                    tilGrado.error = "Requerido"; ok = false
                } else tilGrado.error = null

                if (esp.isEmpty()) {
                    tilEsp.error = "Requerido"; ok = false
                } else tilEsp.error = null

                if (nombre.isEmpty()) {
                    tilTecNom.error = "Requerido"; ok = false
                } else tilTecNom.error = null

                if (!ok) {
                    Toast.makeText(
                        requireContext(),
                        "Completa los campos requeridos",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }

                onCreate(
                    fechaMillis,
                    hora24,
                    matAeronave,
                    grado,
                    esp,
                    nombre,
                    etHsTot.text?.toString()?.trim(),
                    etHsDisp.text?.toString()?.trim(),
                    etMatTec.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() }
                )
            }
            .setNegativeButton("Cancelar", null)
            .create()
    }

    private fun formatDate(millis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return "%02d/%02d/%04d".format(
            c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.MONTH) + 1,
            c.get(Calendar.YEAR)
        )
    }
}
