package com.cabgon.blackhawk.ui.fragments

import android.os.Bundle
import android.view.*
import android.widget.Button
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.data.preflight.ChecklistStore
import com.cabgon.blackhawk.data.preflight.ChecklistItem
import com.cabgon.blackhawk.data.preflight.PreflightChecklist
import com.cabgon.blackhawk.data.preflight.PreflightRepository
import com.cabgon.blackhawk.databinding.FragmentPreflightChecklistBinding
import com.cabgon.blackhawk.ui.preflight.PdfExporter
import com.cabgon.blackhawk.ui.preflight.PreflightChecklistAdapter
import com.cabgon.blackhawk.ui.preflight.SignatureView
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PreflightChecklistFragment : Fragment() {

    private var _b: FragmentPreflightChecklistBinding? = null
    private val b get() = _b!!

    private lateinit var repo: PreflightRepository
    private var inspectionId: Long = -1L
    private lateinit var adapter: PreflightChecklistAdapter

    private lateinit var checklist: PreflightChecklist
    private lateinit var specByTitle: Map<String, ChecklistItem>

    private var header: PreflightRepository.Header? = null
    private var items: List<PreflightRepository.Item> = emptyList()
    private var signatureBitmap: android.graphics.Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inspectionId = requireArguments().getLong(ARG_ID)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentPreflightChecklistBinding.inflate(inflater, container, false)
        repo = PreflightRepository(requireContext())
        checklist = ChecklistStore.loadPreflight(requireContext())

        // Aplanar items + subitems para poder mapear title -> spec (y usar short en UI)
        specByTitle = checklist.sections
            .flatMap { section ->
                section.items.flatMap { item ->
                    if (item.subitems.isNullOrEmpty()) {
                        listOf(item)
                    } else {
                        listOf(item) + item.subitems
                    }
                }
            }
            .associateBy { it.title }

        return b.root
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        // Siempre empezamos sin firma en memoria (opción A)
        signatureBitmap = null

        adapter = PreflightChecklistAdapter(
            specByTitle = specByTitle
        ) { itemId, checked ->
            viewLifecycleOwner.lifecycleScope.launch {
                repo.toggleItem(inspectionId, itemId, checked)
                items = repo.getItems(inspectionId)
                adapter.submitList(items)
                updateCompletionUI()
                updateProgressUI()
            }
        }
        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.recycler.adapter = adapter

        b.recycler.itemAnimator?.apply {
            addDuration = 180
            removeDuration = 180
            moveDuration = 120
            changeDuration = 150
        }

        // Lottie centrado y encima de todo
        b.lottieDone.apply {
            isClickable = false
            isFocusable = false
            bringToFront()
            elevation = 16f
        }

        // Carga inicial
        viewLifecycleOwner.lifecycleScope.launch {
            header = repo.getHeader(inspectionId)
            items = repo.getItems(inspectionId)
            bindHeaderUI(header)
            adapter.submitList(items)
            updateCompletionUI()
            updateProgressUI()
        }

        // Guardar con validación de requeridos
        b.btnGuardar.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                header = repo.getHeader(inspectionId)
                items = repo.getItems(inspectionId)

                val faltantes = missingRequiredTitles(items, checklist)
                if (faltantes.isNotEmpty()) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Faltan pasos requeridos")
                        .setMessage(
                            buildString {
                                append("Aún faltan ")
                                append(faltantes.size)
                                append(" paso(s) requerido(s) por completar.\n\nEjemplos:\n")
                                faltantes.take(3).forEachIndexed { i, t ->
                                    append("• ").append(t)
                                    if (i < 2 && i < faltantes.size - 1) append("\n")
                                }
                            }
                        )
                        .setPositiveButton("Aceptar") { _, _ ->
                            // 👉 Después de mostrar el mensaje, regresar a la lista de inspecciones
                            parentFragmentManager.popBackStack()
                        }
                        .show()
                    return@launch
                }

                repo.saveInspectionCompletion(inspectionId)
                Toast.makeText(requireContext(), "Inspección finalizada.", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            }
        }

        // AppBar: Exportar PDF (con confirmaciones)
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_preflight_checklist, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_export_pdf -> { exportarPdf(); true }
                    else -> false
                }
            }
        }, viewLifecycleOwner)
    }

    /** Mensaje “bonito” según el porcentaje de avance visual. */
    private fun progressStageMessage(pct: Int): String =
        when {
            pct < 20  -> "Preparando encabezado…"
            pct < 50  -> "Generando checklist…"
            pct < 80  -> "Aplicando firma…"
            pct < 98  -> "Guardando en Documentos…"
            else      -> "Completando…"
        }

    // ---------- Encabezado en UI ----------
    private fun bindHeaderUI(h: PreflightRepository.Header?) {
        if (h == null) {
            b.txtHeader.text = "Inspección Pre-vuelo"
            b.cardHeader.visibility = View.GONE
            return
        }
        b.cardHeader.visibility = View.VISIBLE

        // Título arriba
        b.txtHeader.text = "Inspección · ${h.matAeronave}"

        val fechaStr = formatDateUi(h.fechaEpochMillis)
        b.tvMatAeronave.text = h.matAeronave
        b.tvFechaHora.text = "$fechaStr · ${h.hora24.ifBlank { "--:--" }}"

        // Técnico → "Grado Especialidad Nombre"
        val grado = h.tecnicoGrado.ifBlank { "" }
        val esp = h.tecnicoEspecialidad.ifBlank { "" }
        val nombre = h.tecnicoNombre.ifBlank { "" }

        val tecnicoLinea = listOf(grado, esp, nombre)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "—" }

        b.tvTecnico.text = tecnicoLinea

        // Hs
        val hsTot = h.hsTotales?.takeIf { it.isNotBlank() } ?: "—"
        val hsDisp = h.hsDisponibles?.takeIf { it.isNotBlank() } ?: "—"
        b.tvHsTot.text = hsTot
        b.tvHsDisp.text = hsDisp

        // Matrícula técnico
        val matTec = h.tecnicoMatricula?.takeIf { it.isNotBlank() } ?: "—"
        b.tvMatTec.text = matTec
    }

    // ---------- Firma dibujable ----------
    private fun showSignatureDialog(onSaved: (android.graphics.Bitmap) -> Unit) {
        val v = layoutInflater.inflate(R.layout.dialog_signature, null, false)
        val sig = v.findViewById<SignatureView>(R.id.signatureView)

        val dlg = AlertDialog.Builder(requireContext())
            .setView(v)
            .setCancelable(false)
            .create()

        v.findViewById<Button>(R.id.btnClear).setOnClickListener { sig.clear() }
        v.findViewById<Button>(R.id.btnCancel).setOnClickListener { dlg.dismiss() }
        v.findViewById<Button>(R.id.btnSave).setOnClickListener {
            if (!sig.hasSignature()) {
                Toast.makeText(requireContext(), "Dibuja tu firma primero.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val bmp = sig.exportBitmap()
            dlg.dismiss()
            onSaved(bmp)
        }

        dlg.show()
    }

    // ---------- Exportar PDF (validando faltantes + firma) ----------
    private fun exportarPdf() {
        viewLifecycleOwner.lifecycleScope.launch {
            header = repo.getHeader(inspectionId)
            items = repo.getItems(inspectionId)

            val faltantes = missingRequiredTitles(items, checklist)
            if (faltantes.isNotEmpty()) {
                val msg = buildString {
                    append("Hay ")
                    append(faltantes.size)
                    append(" paso(s) requerido(s) sin completar.\n\n¿Deseas exportar el PDF de todos modos?\n\nEjemplos:\n")
                    faltantes.take(3).forEachIndexed { i, t ->
                        append("• ").append(t)
                        if (i < 2 && i < faltantes.size - 1) append("\n")
                    }
                }
                AlertDialog.Builder(requireContext())
                    .setTitle("Exportar con pasos faltantes")
                    .setMessage(msg)
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Continuar y exportar") { _, _ ->
                        exportarPdfSinValidar()
                    }
                    .show()
                return@launch
            }

            // Sin faltantes: pasamos directo al flujo que valida firma
            exportarPdfSinValidar()
        }
    }

    // ---------- Exportar PDF (solo validación de firma) ----------
    private fun exportarPdfSinValidar() {
        viewLifecycleOwner.lifecycleScope.launch {
            header = repo.getHeader(inspectionId)
            items = repo.getItems(inspectionId)

            // Verificar firma (opción A)
            if (signatureBitmap == null) {
                AlertDialog.Builder(requireContext())
                    .setTitle("Firma requerida")
                    .setMessage("¿Deseas agregar la firma antes de exportar?")
                    .setNegativeButton("Exportar sin firma") { _, _ ->
                        doExportPdf()
                    }
                    .setPositiveButton("Agregar firma") { _, _ ->
                        showSignatureDialog { bmp ->
                            signatureBitmap = bmp
                            doExportPdf()
                        }
                    }
                    .show()
                return@launch
            } else {
                doExportPdf()
            }
        }
    }

    // ---------- Export real PDF + barra + Lottie ----------
    private fun doExportPdf() {
        val h = header ?: run {
            Toast.makeText(requireContext(), "No hay datos de inspección", Toast.LENGTH_SHORT).show()
            return
        }

        val ui = showPdfProgress()
        ui.progress.isIndeterminate = false
        ui.progress.max = 100
        ui.progress.progress = 0
        ui.txtMsg.text = "Preparando encabezado…\n0%"

        // Flags de control para el hilo de animación
        val running = AtomicBoolean(true)
        val finalized = AtomicBoolean(false)

        //  Hilo que actualiza progreso visual mientras se genera el PDF
        val updater = Thread {
            var pct = 0
            while (running.get()) {
                try {
                    Thread.sleep(120L) // velocidad de animación
                } catch (_: InterruptedException) {
                    break
                }

                // Avance adaptativo según el tramo:
                val step = when {
                    pct < 40 -> 4   // arranca más rápido
                    pct < 75 -> 3   // fase media
                    pct < 90 -> 2   // se empieza a calmar
                    else     -> 1   // tramo final, suave
                }

                pct = (pct + step).coerceAtMost(98) // el 100 lo ponemos al final

                ui.txtMsg.post {
                    // Si ya finalizamos, NO volvemos a bajar a 98
                    if (!finalized.get()) {
                        val msg = progressStageMessage(pct)
                        ui.progress.progress = pct
                        ui.txtMsg.text = "$msg\n$pct%"
                    }
                }
            }
        }
        updater.start()

        viewLifecycleOwner.lifecycleScope.launch {
            val checkedByTitle = items.associate { it.title to it.checked }

            val uri = withContext(Dispatchers.Default) {
                PdfExporter.exportPreflight(
                    context = requireContext(),
                    fileNameHint = "preflight_${h.matAeronave}_${formatDateFile(h.fechaEpochMillis)}",
                    header = PdfExporter.Header(
                        title = checklist.title,
                        fecha = formatDateUi(h.fechaEpochMillis),
                        hora24 = h.hora24.ifBlank { "00:00" },
                        matAeronave = h.matAeronave,
                        tecnicoGrado = h.tecnicoGrado,
                        tecnicoEspecialidad = h.tecnicoEspecialidad,
                        tecnicoNombre = h.tecnicoNombre,
                        hsTotales = h.hsTotales,
                        hsDisponibles = h.hsDisponibles,
                        tecnicoMatricula = h.tecnicoMatricula
                    ),
                    checklist = checklist,
                    checkedByTitle = checkedByTitle,
                    signatureLabel = "Firma del técnico",
                    watermarkResId = R.drawable.watermark_logo,
                    watermarkAlpha = 60,
                    headerLogoResId = R.drawable.uh60_header,
                    signatureBitmap = signatureBitmap,
                    progress = null // ya no usamos pasos internos
                )
            }

            // Detenemos animación y marcamos finalizado ANTES de tocar la UI
            running.set(false)
            finalized.set(true)

            if (uri != null) {
                // 💯 Aquí el PDF YA está generado.
                ui.txtMsg.post {
                    ui.progress.progress = 100
                    ui.txtMsg.text = "Completado\n100%"

                    // Detener y ocultar Lottie
                    val lottie = ui.dialog.findViewById<com.airbnb.lottie.LottieAnimationView>(R.id.lottieLoading)
                    lottie?.cancelAnimation()
                    lottie?.visibility = View.GONE
                }

                // Abrimos el PDF de inmediato
                PdfExporter.openPdf(requireContext(), uri)

                // Cerramos el diálogo un poco después para que se vea el 100% (motion sutil)
                ui.txtMsg.postDelayed({
                    if (ui.dialog.isShowing) {
                        ui.dialog.dismiss()
                    }
                }, 450L)
            } else {
                ui.txtMsg.post {
                    ui.dialog.dismiss()
                }
                Toast.makeText(requireContext(), "No se pudo exportar el PDF", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- Diálogo de progreso ----------
    private data class PdfProgressUI(
        val dialog: AlertDialog,
        val txtMsg: TextView,
        val progress: LinearProgressIndicator,
    ) {
        fun post(block: () -> Unit) { txtMsg.post { block() } }
    }

    private fun showPdfProgress(): PdfProgressUI {
        val v = layoutInflater.inflate(R.layout.dialog_pdf_progress, null, false)
        val dlg = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setView(v)
            .setCancelable(false)
            .create()
            .also { it.show() }

        val txt = v.findViewById<TextView>(R.id.txtMsg)
        val bar = v.findViewById<LinearProgressIndicator>(R.id.progressLinear)

        // Lottie
        val lottie = v.findViewById<com.airbnb.lottie.LottieAnimationView>(R.id.lottieLoading)
        lottie?.playAnimation()

        return PdfProgressUI(dlg, txt, bar)
    }

    // ---------- Helpers ----------
    private fun missingRequiredTitles(
        items: List<PreflightRepository.Item>,
        checklist: PreflightChecklist,
    ): List<String> {
        // Todos los ítems definidos en el checklist (solo nivel superior por ahora)
        val specItems = checklist.sections.flatMap { it.items }

        val faltantes = mutableListOf<String>()

        // Recorremos en paralelo: definición (spec) vs estado en DB (items)
        val commonSize = minOf(specItems.size, items.size)
        for (i in 0 until commonSize) {
            val spec = specItems[i]
            val item = items[i]

            // Solo revisamos los que son requeridos
            if (spec.required == true && !item.checked) {
                faltantes += spec.title
            }
        }
        // Si el checklist tiene más elementos que la lista persistida,
        // cualquier requerido sin item correspondiente también se considera faltante
        if (specItems.size > items.size) {
            for (i in items.size until specItems.size) {
                val spec = specItems[i]
                if (spec.required == true) {
                    faltantes += spec.title
                }
            }
        }

        return faltantes
    }

    private fun formatDateUi(millis: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        return "%02d/%02d/%04d".format(
            c.get(java.util.Calendar.DAY_OF_MONTH),
            c.get(java.util.Calendar.MONTH) + 1,
            c.get(java.util.Calendar.YEAR)
        )
    }

    private fun formatDateFile(millis: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        return "%04d-%02d-%02d".format(
            c.get(java.util.Calendar.YEAR),
            c.get(java.util.Calendar.MONTH) + 1,
            c.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    private fun updateCompletionUI() {
        val isAllChecked = items.isNotEmpty() && items.all { it.checked }
        val anim = b.lottieDone

        if (isAllChecked) {
            // Si ya está visible, no reiniciar animación
            if (anim.visibility != View.VISIBLE) {
                anim.setMinAndMaxProgress(0f, 1f)
                anim.alpha = 1f
                anim.visibility = View.VISIBLE
                anim.progress = 0f
                anim.playAnimation()
            }

            // se desvanece suavemente después de 1 s
            anim.removeCallbacks(null)
            anim.postDelayed({
                if (isAdded && anim.isShown) {
                    anim.animate()
                        .alpha(0f)
                        .setDuration(400)
                        .withEndAction {
                            anim.visibility = View.GONE
                            anim.cancelAnimation()
                        }
                        .start()
                }
            }, 1000L)
        } else if (!isAllChecked && anim.isVisible) {
            // Oculta instantáneo si se desmarca algo
            anim.animate().alpha(0f).setDuration(150).withEndAction {
                anim.visibility = View.GONE
                anim.cancelAnimation()
            }.start()
        }
    }

    /** Actualiza el porcentaje de avance visible bajo el encabezado */
    private fun updateProgressUI() {
        val total = items.size
        val done = items.count { it.checked }
        val pct = if (total == 0) 0 else (done * 100 / total)

        // Texto
        b.tvProgress.text = "Avance: $pct%  ($done / $total)"

        // Color del texto
        val colorRes = if (pct == 100) {
            R.color.green
        } else {
            R.color.red
        }
        b.tvProgress.setTextColor(
            ContextCompat.getColor(requireContext(), colorRes)
        )

        // Barra Material
        b.progressChecklist.apply {
            max = 100
            progress = pct
            isVisible = total > 0
        }

        val allDone = (pct == 100 && total > 0)
        b.btnGuardar.text = if (allDone) {
            "Finalizar inspección"
        } else {
            "Guardar borrador"
        }
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    companion object {
        private const val ARG_ID = "id"
        fun newInstance(id: Long) = PreflightChecklistFragment().apply {
            arguments = bundleOf(ARG_ID to id)
        }
    }
}
