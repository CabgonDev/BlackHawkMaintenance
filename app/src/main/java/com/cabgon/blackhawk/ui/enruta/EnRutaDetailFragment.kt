package com.cabgon.blackhawk.ui.enruta

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.airbnb.lottie.LottieAnimationView
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.data.db.AppDbProvider
import com.cabgon.blackhawk.data.enruta.EnRutaRepository
import com.cabgon.blackhawk.data.user.UserSessionStore
import com.cabgon.blackhawk.databinding.FragmentEnRutaDetailBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class EnRutaDetailFragment : Fragment() {

    companion object {
        private const val ARG_MAT = "matAeronave"

        fun newInstance(mat: String) = EnRutaDetailFragment().apply {
            arguments = bundleOf(ARG_MAT to mat)
        }
    }

    private var _binding: FragmentEnRutaDetailBinding? = null
    private val binding get() = _binding!!

    private lateinit var recargasAdapter: EnRutaRecargaAdapter

    // Para controlar transiciones de guardado
    private var lastIsSaving: Boolean = false
    private var lastSaveSuccess: Boolean? = null

    private val viewModel: EnRutaViewModel by viewModels {
        EnRutaViewModelFactory(
            repo = EnRutaRepository(
                dao = AppDbProvider.get(requireContext()).enRutaDao(),
                firestore = FirebaseFirestore.getInstance()
            ),
            currentUserIdProvider = {
                UserSessionStore(requireContext()).getProfile()?.uid
            }
        )
    }

    private val matAeronave: String by lazy {
        requireArguments().getString(ARG_MAT) ?: ""
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEnRutaDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        // Back en toolbar
        binding.toolbarEnRutaDetail.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        // --------- DROPDOWNS MANUALES (DIÁLOGOS) ---------

        // Categoría A / B
        val categorias = arrayOf("A", "B")
        binding.edtCategoria.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Categoría")
                .setItems(categorias) { _, which ->
                    binding.edtCategoria.setText(categorias[which])
                    // TextWatcher ya avisa al ViewModel
                }
                .show()
        }

        // Próx inspección 40 / 80 / 120 / 480
        val proxOpcionesNumericas = arrayOf(40, 80, 120, 480)
        val proxOpcionesTexto = proxOpcionesNumericas.map { "$it hrs" }.toTypedArray()

        binding.edtProxInspeccion.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Próx. inspección")
                .setItems(proxOpcionesTexto) { _, which ->
                    val value = proxOpcionesNumericas[which].toString()
                    binding.edtProxInspeccion.setText(value)
                    // TextWatcher ya avisa al ViewModel
                }
                .show()
        }

        // ---------------- Tabs Visualizar / Actualizar ----------------
        binding.tabLayoutEnRuta.addTab(
            binding.tabLayoutEnRuta.newTab().setText("Visualizar")
        )
        binding.tabLayoutEnRuta.addTab(
            binding.tabLayoutEnRuta.newTab().setText("Actualizar")
        )

        binding.tabLayoutEnRuta.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                when (tab.position) {
                    0 -> {
                        binding.scrollVisualizar.visibility = View.VISIBLE
                        binding.layoutActualizarRoot.visibility = View.GONE
                    }

                    1 -> {
                        binding.scrollVisualizar.visibility = View.GONE
                        binding.layoutActualizarRoot.visibility = View.VISIBLE
                    }
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
        binding.tabLayoutEnRuta.getTabAt(0)?.select()

        // -------- Filtro: máximo un separador decimal y 1 decimal --------
        val oneDecimalFilter = InputFilter { source, start, end, dest, dstart, dend ->
            val newText = buildString {
                append(dest.substring(0, dstart))
                append(source.subSequence(start, end))
                append(dest.substring(dend))
            }

            if (newText.isEmpty()) return@InputFilter null

            val normalized = newText.replace(',', '.')
            if (normalized.count { it == '.' } > 1) return@InputFilter ""

            val parts = normalized.split('.')
            if (parts.size == 2 && parts[1].length > 1) return@InputFilter ""

            null
        }

        // aplicar filtro a horas con decimal
        binding.edtHorasVuelo.filters = arrayOf(oneDecimalFilter)
        binding.edtHorasTotales.filters = arrayOf(oneDecimalFilter)
        binding.edtHorasDisponibles.filters = arrayOf(oneDecimalFilter)

        // ---------------- TextWatchers ----------------
        fun watcher(onAfter: (String) -> Unit) = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                onAfter(s?.toString() ?: "")
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }

        // Campos básicos
        binding.edtCategoria.addTextChangedListener(watcher {
            viewModel.onCategoriaChange(it)
        })
        binding.edtUbicacion.addTextChangedListener(watcher {
            viewModel.onUbicacionChange(it)
        })
        binding.edtTipoOps.addTextChangedListener(watcher {
            viewModel.onTipoOpsChange(it)
        })

        // Horas
        binding.edtHorasVuelo.addTextChangedListener(watcher {
            viewModel.onHorasVueloChange(it)
        })
        binding.edtHorasTotales.addTextChangedListener(watcher {
            viewModel.onHorasTotalesChange(it)
        })
        binding.edtHorasDisponibles.addTextChangedListener(watcher {
            viewModel.onHorasDisponiblesChange(it)
        })
        binding.edtProxInspeccion.addTextChangedListener(watcher {
            viewModel.onProxInspeccionChange(it)
        })

        // Motores
        binding.edtMotor1Lcf1.addTextChangedListener(watcher {
            viewModel.onMotor1Lcf1Change(it)
        })
        binding.edtMotor1Lcf2.addTextChangedListener(watcher {
            viewModel.onMotor1Lcf2Change(it)
        })
        binding.edtMotor1Index.addTextChangedListener(watcher {
            viewModel.onMotor1IndexChange(it)
        })
        binding.edtMotor1Horas.addTextChangedListener(watcher {
            viewModel.onMotor1HorasChange(it)
        })

        binding.edtMotor2Lcf1.addTextChangedListener(watcher {
            viewModel.onMotor2Lcf1Change(it)
        })
        binding.edtMotor2Lcf2.addTextChangedListener(watcher {
            viewModel.onMotor2Lcf2Change(it)
        })
        binding.edtMotor2Index.addTextChangedListener(watcher {
            viewModel.onMotor2IndexChange(it)
        })
        binding.edtMotor2Horas.addTextChangedListener(watcher {
            viewModel.onMotor2HorasChange(it)
        })

        // APU
        binding.edtApuHoras.addTextChangedListener(watcher {
            viewModel.onApuHorasChange(it)
        })
        binding.edtApuEventos.addTextChangedListener(watcher {
            viewModel.onApuEventosChange(it)
        })

        // Reportes
        binding.edtReportes.addTextChangedListener(watcher {
            viewModel.onReportesChange(it)
        })

        // -------- Botón Guardar --------
        binding.btnGuardarCambios.setOnClickListener {
            viewModel.guardarCambios()
        }

        // -------- Botón Agregar recarga --------
        binding.btnAgregarRecarga.setOnClickListener {
            val dialogView = layoutInflater.inflate(
                R.layout.dialog_en_ruta_recarga,
                null
            )

            val edtFolio =
                dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(
                    R.id.edtFolioRecarga
                )
            val edtLitros =
                dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(
                    R.id.edtLitrosRecarga
                )
            val edtUbic =
                dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(
                    R.id.edtUbicacionRecarga
                )

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Agregar recarga")
                .setView(dialogView)
                .setPositiveButton("Guardar") { _, _ ->
                    val folio = edtFolio.text?.toString()?.toIntOrNull()
                    val litros = edtLitros.text?.toString()?.toIntOrNull()
                    val ubic = edtUbic.text?.toString()?.trim().orEmpty()

                    if (folio != null && litros != null) {
                        viewModel.agregarRecarga(folio, litros, ubic)
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        // ----- RecyclerView de recargas (Actualizar) -----
        recargasAdapter = EnRutaRecargaAdapter(
            onFolioChange = { index, value -> viewModel.onRecargaFolioChange(index, value) },
            onLitrosChange = { index, value -> viewModel.onRecargaLitrosChange(index, value) },
            onUbicacionChange = { index, value -> viewModel.onRecargaUbicacionChange(index, value) },
            onEliminar = { index -> viewModel.eliminarRecarga(index) }
        )

        binding.recyclerRecargas.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = recargasAdapter
        }

        // ---------------- Observador de estado ----------------
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.detailState.collectLatest { state ->
                if (state.isLoading) return@collectLatest

                // ===== VISUALIZAR =====
                val fecha = state.lastEditDate.ifBlank { "--/--/----" }
                binding.txtFechaDetalle.text = fecha

                binding.txtMatriculaDetalle.text = "UH-60L Mat. ${state.matAeronave}"
                binding.txtCategoriaDetalle.text =
                    "Categoría: ${state.categoria.ifBlank { "—" }}"

                binding.txtUbicacionDetalle.text =
                    "Ubicación: ${state.ubicacion.ifBlank { "Sin ubicación" }}"

                binding.txtTipoOpsDetalle.text =
                    "Tipo de Operación: ${state.tipoOps.ifBlank { "—" }}"

                // ----- HORAS -----
                val hv = state.horasVuelo.replace(",", ".").toDoubleOrNull() ?: 0.0
                val ht = state.horasTotales.replace(",", ".").toDoubleOrNull() ?: 0.0
                val hd = state.horasDisponibles.replace(",", ".").toDoubleOrNull() ?: 0.0

                val hvStr = String.format(Locale.US, "%.1f", hv)
                val htStr = String.format(Locale.US, "%.1f", ht)
                val hdStr = String.format(Locale.US, "%.1f", hd)

                binding.txtHorasDetalle.text =
                    "Horas de vuelo: $hvStr hs\n" +
                            "Horas totales: $htStr hs\n" +
                            "Horas disponibles: $hdStr hs"

                val proxLabel = when (state.proxInspeccion.toIntOrNull()) {
                    40 -> "40 hrs"
                    80 -> "80 hrs"
                    120 -> "120 hrs"
                    480 -> "480 hrs"
                    else -> "${state.proxInspeccion} hrs"
                }
                binding.txtProxInspeccionDetalle.text = "Para inspección: $proxLabel"

                // ----- MOTOR 1 -----
                val m1Lcf1 = state.motor1Lcf1.toIntOrNull() ?: 0
                val m1Lcf2 = state.motor1Lcf2.toIntOrNull() ?: 0
                val m1Index = state.motor1Index.toIntOrNull() ?: 0
                val m1Horas = state.motor1Horas.toIntOrNull() ?: 0

                binding.txtMotor1Detalle.text = String.format(
                    Locale.US,
                    "LCF1: %05d\nLCF2: %05d\nIndex: %05d\nHRS: %05d",
                    m1Lcf1,
                    m1Lcf2,
                    m1Index,
                    m1Horas
                )

                // ----- MOTOR 2 -----
                val m2Lcf1 = state.motor2Lcf1.toIntOrNull() ?: 0
                val m2Lcf2 = state.motor2Lcf2.toIntOrNull() ?: 0
                val m2Index = state.motor2Index.toIntOrNull() ?: 0
                val m2Horas = state.motor2Horas.toIntOrNull() ?: 0

                binding.txtMotor2Detalle.text = String.format(
                    Locale.US,
                    "LCF1: %05d\nLCF2: %05d\nIndex: %05d\nHoras: %05d",
                    m2Lcf1,
                    m2Lcf2,
                    m2Index,
                    m2Horas
                )

                // ----- APU -----
                val apuHoras = state.apuHoras.toIntOrNull() ?: 0
                val apuEventos = state.apuEventos.toIntOrNull() ?: 0

                binding.txtApuDetalle.text = String.format(
                    Locale.US,
                    "Horas: %d\nEventos: %d",
                    apuHoras,
                    apuEventos
                )

                // ----- RECARGAS (VISUALIZAR) -----
                if (state.recargas.isEmpty()) {
                    binding.txtRecargasDetalle.text = "Sin recargas"
                } else {
                    val recargasTexto = state.recargas.joinToString(separator = "\n\n") { r ->
                        val folio = r.folio.ifBlank { "—" }
                        val litros = r.litros.ifBlank { "—" }
                        val ubic = r.ubicacion.ifBlank { "—" }

                        "Folio: $folio\nRecarga: $litros lts.\nUbicación: $ubic"
                    }
                    binding.txtRecargasDetalle.text = recargasTexto
                }

                binding.txtReportesDetalle.text =
                    if (state.reportes.isBlank()) "Sin reportes" else state.reportes

                // Recargas en ACTUALIZAR
                recargasAdapter.submitList(state.recargas)

                // ===== FEEDBACK DE GUARDADO (botón + Lottie + compartir) =====

                // Transición: empezó a guardar
                if (!lastIsSaving && state.isSaving) {
                    binding.btnGuardarCambios.isEnabled = false
                    binding.btnGuardarCambios.text = "Guardando..."
                }

                // Transición: terminó de guardar
                if (lastIsSaving && !state.isSaving) {
                    binding.btnGuardarCambios.isEnabled = true
                    binding.btnGuardarCambios.text = "Guardar cambios"

                    if (state.saveSuccess == true && lastSaveSuccess != true) {
                        // Mostrar Lottie y luego preguntar si quieres compartir
                        showSuccessLottie {
                            showShareDialog(state)
                        }
                        // Regresar a pestaña "Visualizar"
                        binding.tabLayoutEnRuta.getTabAt(0)?.select()
                        binding.scrollVisualizar.visibility = View.VISIBLE
                        binding.layoutActualizarRoot.visibility = View.GONE
                    }
                }

                lastIsSaving = state.isSaving
                lastSaveSuccess = state.saveSuccess

                // ===== ACTUALIZAR CAMPOS (Editar) =====
                fun setIfDifferent(current: CharSequence?, new: String, apply: (String) -> Unit) {
                    if (current?.toString() != new) {
                        apply(new)
                    }
                }

                setIfDifferent(binding.edtCategoria.text, state.categoria) {
                    binding.edtCategoria.setText(it)
                }
                setIfDifferent(binding.edtUbicacion.text, state.ubicacion) {
                    binding.edtUbicacion.setText(it)
                }
                setIfDifferent(binding.edtTipoOps.text, state.tipoOps) {
                    binding.edtTipoOps.setText(it)
                }

                setIfDifferent(binding.edtHorasVuelo.text, state.horasVuelo) {
                    binding.edtHorasVuelo.setText(it)
                }
                setIfDifferent(binding.edtHorasTotales.text, state.horasTotales) {
                    binding.edtHorasTotales.setText(it)
                }
                setIfDifferent(binding.edtHorasDisponibles.text, state.horasDisponibles) {
                    binding.edtHorasDisponibles.setText(it)
                }
                setIfDifferent(binding.edtProxInspeccion.text, state.proxInspeccion) {
                    binding.edtProxInspeccion.setText(it)
                }

                setIfDifferent(binding.edtMotor1Lcf1.text, state.motor1Lcf1) {
                    binding.edtMotor1Lcf1.setText(it)
                }
                setIfDifferent(binding.edtMotor1Lcf2.text, state.motor1Lcf2) {
                    binding.edtMotor1Lcf2.setText(it)
                }
                setIfDifferent(binding.edtMotor1Index.text, state.motor1Index) {
                    binding.edtMotor1Index.setText(it)
                }
                setIfDifferent(binding.edtMotor1Horas.text, state.motor1Horas) {
                    binding.edtMotor1Horas.setText(it)
                }

                setIfDifferent(binding.edtMotor2Lcf1.text, state.motor2Lcf1) {
                    binding.edtMotor2Lcf1.setText(it)
                }
                setIfDifferent(binding.edtMotor2Lcf2.text, state.motor2Lcf2) {
                    binding.edtMotor2Lcf2.setText(it)
                }
                setIfDifferent(binding.edtMotor2Index.text, state.motor2Index) {
                    binding.edtMotor2Index.setText(it)
                }
                setIfDifferent(binding.edtMotor2Horas.text, state.motor2Horas) {
                    binding.edtMotor2Horas.setText(it)
                }

                setIfDifferent(binding.edtApuHoras.text, state.apuHoras) {
                    binding.edtApuHoras.setText(it)
                }
                setIfDifferent(binding.edtApuEventos.text, state.apuEventos) {
                    binding.edtApuEventos.setText(it)
                }

                setIfDifferent(binding.edtReportes.text, state.reportes) {
                    binding.edtReportes.setText(it)
                }
            }
        }

        // Cargar datos al entrar
        viewModel.cargarDetalle(matAeronave)
    }

    // ------- LOTTIE centrado --------
    private fun showSuccessLottie(onFinished: () -> Unit = {}) {
        val ctx = context ?: return

        val dialog = Dialog(ctx)
        dialog.setCancelable(false)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val lottie = LottieAnimationView(ctx).apply {
            setAnimation(R.raw.check_success)
            repeatCount = 0
            layoutParams = ViewGroup.LayoutParams(300, 300)
        }

        val container = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = Gravity.CENTER
            }
            alpha = 0f
            addView(lottie)
        }

        dialog.setContentView(container)
        dialog.show()

        container.animate()
            .alpha(1f)
            .setDuration(180L)
            .start()

        lottie.addAnimatorListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                container.animate()
                    .alpha(0f)
                    .setDuration(180L)
                    .withEndAction {
                        dialog.dismiss()
                        onFinished()
                    }
                    .start()
            }
        })

        lottie.playAnimation()
    }

    // ------- Diálogo para compartir + Intent -------

    private fun showShareDialog(state: EnRutaViewModel.EnRutaDetailUiState) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Compartir información")
            .setMessage("¿Quieres compartir la información de En Ruta?")
            .setPositiveButton("Compartir") { _, _ ->
                shareStatusAsText(state)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun shareStatusAsText(state: EnRutaViewModel.EnRutaDetailUiState) {
        val hv = state.horasVuelo.ifBlank { "0.0" }
        val ht = state.horasTotales.ifBlank { "0.0" }
        val hd = state.horasDisponibles.ifBlank { "0.0" }

        val recargasTexto = if (state.recargas.isEmpty()) {
            "Sin recargas"
        } else {
            state.recargas.joinToString(separator = "\n\n") { r ->
                val folio = r.folio.ifBlank { "—" }
                val litros = r.litros.ifBlank { "—" }
                val ubic = r.ubicacion.ifBlank { "—" }
                "Folio: $folio\nRecarga: $litros lts.\nUbicación: $ubic"
            }
        }

        val reportesTexto = state.reportes.ifBlank { "Sin reportes" }

        val shareText = buildString {
            appendLine("UH-60L Mat. ${state.matAeronave}")
            appendLine("Fecha: ${state.lastEditDate}")
            appendLine()
            appendLine("Categoría: ${state.categoria}")
            appendLine("Ubicación: ${state.ubicacion.ifBlank { "Sin ubicación" }}")
            appendLine("Tipo de operación: ${state.tipoOps.ifBlank { "—" }}")
            appendLine()
            appendLine("Horas de vuelo: $hv hs")
            appendLine("Horas totales: $ht hs")
            appendLine("Horas disponibles: $hd hs")
            appendLine("Para inspección: ${state.proxInspeccion} hrs")
            appendLine()
            appendLine("Motor 1:")
            appendLine("  LCF1: ${state.motor1Lcf1.ifBlank { "0" }}")
            appendLine("  LCF2: ${state.motor1Lcf2.ifBlank { "0" }}")
            appendLine("  Index: ${state.motor1Index.ifBlank { "0" }}")
            appendLine("  Horas: ${state.motor1Horas.ifBlank { "0" }}")
            appendLine()
            appendLine("Motor 2:")
            appendLine("  LCF1: ${state.motor2Lcf1.ifBlank { "0" }}")
            appendLine("  LCF2: ${state.motor2Lcf2.ifBlank { "0" }}")
            appendLine("  Index: ${state.motor2Index.ifBlank { "0" }}")
            appendLine("  Horas: ${state.motor2Horas.ifBlank { "0" }}")
            appendLine()
            appendLine("APU:")
            appendLine("  Horas: ${state.apuHoras.ifBlank { "0" }}")
            appendLine("  Eventos: ${state.apuEventos.ifBlank { "0" }}")
            appendLine()
            appendLine("Recargas:")
            appendLine(recargasTexto)
            appendLine()
            appendLine("Reportes:")
            appendLine(reportesTexto)
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "En Ruta UH-60L Mat. ${state.matAeronave}")
            putExtra(Intent.EXTRA_TEXT, shareText)
        }

        startActivity(Intent.createChooser(intent, "Compartir En Ruta"))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
