package com.cabgon.blackhawk.ui.fragments

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.cabgon.blackhawk.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CreateInspection40hDialogFragment(
    private val onCreateInspection: (
        createdAt: Long,
        matAeronave: String,
        hsTotales: Float,
        supervisorGrade: String,
        supervisorSpecialty: String,
        supervisorFullName: String,
        supervisorMatricula: String
    ) -> Unit
) : DialogFragment() {

    // referencias para poder usarlas en onStart()
    private var tvDateTime: TextView? = null
    private var spMatAeronave: Spinner? = null
    private var etHsTotales: EditText? = null
    private var spSupervisorGrade: Spinner? = null
    private var spSupervisorSpecialty: Spinner? = null
    private var etSupervisorName: EditText? = null
    private var etSupervisorMatricula: EditText? = null

    private var createdAt: Long = 0L

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val inflater = LayoutInflater.from(requireContext())
        val view = inflater.inflate(R.layout.dialog_create_inspection_40h, null, false)

        // Vistas
        tvDateTime = view.findViewById(R.id.tvDateTime)
        spMatAeronave = view.findViewById(R.id.spMatAeronave)
        etHsTotales = view.findViewById(R.id.etHsTotales)
        spSupervisorGrade = view.findViewById(R.id.spSupervisorGrade)
        spSupervisorSpecialty = view.findViewById(R.id.spSupervisorSpecialty)
        etSupervisorName = view.findViewById(R.id.etSupervisorName)
        etSupervisorMatricula = view.findViewById(R.id.etSupervisorMatricula)

        // 1) FECHA / HORA DEL DISPOSITIVO
        createdAt = System.currentTimeMillis()
        val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        tvDateTime?.text = "Fecha / Hora: ${formatter.format(Date(createdAt))}"

        // 2) MAT. AERONAVE (SPINNER FIJO CON 1091, 1092, 1093, 1094, 1097, 1098)
        val matList = listOf("1091", "1092", "1093", "1094", "1097", "1098")
        spMatAeronave?.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            matList
        )

        // 3) GRADOS
        val grados = listOf(
            "Sgto. 2/o.", "Sgto. 1/o.",
            "Sbtte.", "Tte.", "Cap. 2/o.", "Cap. 1/o."
        )
        spSupervisorGrade?.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            grados
        )

        // 4) ESPECIALIDAD
        val especialidades = listOf("F.A.E.E.A.", "F.A.E.M.A.")
        spSupervisorSpecialty?.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            especialidades
        )

        // IMPORTANTE:
        // - etSupervisorName y etSupervisorMatricula NO se autollenan.
        //   Siempre se escriben a mano al crear cada inspección.

        // Creamos el diálogo; la validación la haremos en onStart()
        return AlertDialog.Builder(requireContext())
            .setTitle("Nueva inspección 40H")
            .setView(view)
            .setPositiveButton("Crear", null) // listener se sobrescribe en onStart
            .setNegativeButton("Cancelar", null)
            .create()
    }

    override fun onStart() {
        super.onStart()

        val dialog = dialog as? AlertDialog ?: return
        val positiveButton: Button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)

        positiveButton.setOnClickListener {
            // Obtener datos
            val hsText = etHsTotales?.text?.toString()?.trim().orEmpty()
            val supervisorName = etSupervisorName?.text?.toString()?.trim().orEmpty()
            val supervisorMatricula = etSupervisorMatricula?.text?.toString()?.trim().orEmpty()

            var hasError = false

            // ---- VALIDAR HS TOTALES (> 0) ----
            if (hsText.isEmpty()) {
                etHsTotales?.error = "Requerido"
                hasError = true
            } else {
                val hsValue = hsText.replace(",", ".").toFloatOrNull()
                if (hsValue == null || hsValue <= 0f) {
                    etHsTotales?.error = "Debe ser un número mayor a 0"
                    hasError = true
                }
            }

            // ---- VALIDAR NOMBRE (mínimo 2 palabras) ----
            if (supervisorName.isEmpty()) {
                etSupervisorName?.error = "Requerido"
                hasError = true
            } else {
                val parts = supervisorName
                    .split(" ")
                    .map { it.trim() }
                    .filter { it.length >= 2 } // palabras reales

                if (parts.size < 2) {
                    etSupervisorName?.error = "Debe contener al menos dos nombres/apellidos"
                    hasError = true
                }
            }

            // ---- VALIDAR MATRÍCULA (FORMATO X-1766403 o X-176650) ----
            if (supervisorMatricula.isEmpty()) {
                etSupervisorMatricula?.error = "Requerido"
                hasError = true
            } else {
                val matriculaPattern = Regex("^[A-Z]-\\d{6,7}$")
                if (!matriculaPattern.matches(supervisorMatricula)) {
                    etSupervisorMatricula?.error = "Formato inválido. Ej: X-1766403"
                    hasError = true
                }
            }

            // Si hay errores NO cerramos el diálogo
            if (hasError) {
                Toast.makeText(
                    requireContext(),
                    "Verifica los campos marcados",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            // Sitodo está correcto → enviar datos al callback
            val matAeronave = spMatAeronave?.selectedItem?.toString().orEmpty()
            val hsTotales = hsText.replace(",", ".").toFloat()
            val supervisorGrade = spSupervisorGrade?.selectedItem?.toString().orEmpty()
            val supervisorSpecialty = spSupervisorSpecialty?.selectedItem?.toString().orEmpty()

            onCreateInspection(
                createdAt,
                matAeronave,
                hsTotales,
                supervisorGrade,
                supervisorSpecialty,
                supervisorName,
                supervisorMatricula
            )

            dialog.dismiss()
        }
    }

    companion object {
        fun newInstance(
            onCreateInspection: (
                createdAt: Long,
                matAeronave: String,
                hsTotales: Float,
                supervisorGrade: String,
                supervisorSpecialty: String,
                supervisorFullName: String,
                supervisorMatricula: String
            ) -> Unit
        ): CreateInspection40hDialogFragment = CreateInspection40hDialogFragment(onCreateInspection)
    }
}
