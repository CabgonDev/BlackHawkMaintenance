package com.cabgon.blackhawk.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.data.preflight.ChecklistItem
import com.cabgon.blackhawk.data.preflight.ChecklistStore
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
import java.util.concurrent.atomic.AtomicBoolean

class PreflightChecklistFragment : Fragment() {

    private var _b: FragmentPreflightChecklistBinding? = null
    private val b get() = _b!!

    private lateinit var repo: PreflightRepository
    private var inspectionId: Long = -1L
    private lateinit var adapter: PreflightChecklistAdapter

    private lateinit var checklist: PreflightChecklist
    private lateinit var flatEntries: List<ChecklistStore.FlatChecklistEntry>
    private lateinit var specByOrder: Map<Int, ChecklistItem>
    private lateinit var metaByOrder: Map<Int, ChecklistStore.FlatChecklistEntry>
    private lateinit var childOrderIndexesByParent: Map<Int, List<Int>>
    private lateinit var sectionOrder: List<String>

    private var header: PreflightRepository.Header? = null
    private var items: List<PreflightRepository.Item> = emptyList()
    private var signatureBitmap: android.graphics.Bitmap? = null
    private var isMissionPanelExpanded = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inspectionId = requireArguments().getLong(ARG_ID)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        s: Bundle?
    ): View {
        _b = FragmentPreflightChecklistBinding.inflate(inflater, container, false)
        repo = PreflightRepository(requireContext())
        checklist = ChecklistStore.loadPreflight(requireContext())

        flatEntries = ChecklistStore.flattenItems(checklist)
        specByOrder = flatEntries.associate { it.orderIndex to it.item }
        metaByOrder = flatEntries.associateBy { it.orderIndex }
        childOrderIndexesByParent = flatEntries
            .filter { it.parentOrderIndex != null }
            .groupBy(
                keySelector = { it.parentOrderIndex!! },
                valueTransform = { it.orderIndex }
            )

        sectionOrder = checklist.sections.map { section ->
            section.title.ifBlank { "Área" }
        }

        return b.root
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        signatureBitmap = null

        adapter = PreflightChecklistAdapter(
            specByOrder = specByOrder,
            metaByOrder = metaByOrder,
            sectionOrder = sectionOrder
        ) { itemId, checked ->
            viewLifecycleOwner.lifecycleScope.launch {
                handleItemToggle(itemId, checked)
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

        b.lottieDone.apply {
            isClickable = false
            isFocusable = false
            bringToFront()
            elevation = 16f
        }

        setupMissionPanelToggle()

        viewLifecycleOwner.lifecycleScope.launch {
            header = repo.getHeader(inspectionId)
            items = repo.getItems(inspectionId)

            if (syncParentStates(items)) {
                items = repo.getItems(inspectionId)
            }

            bindHeaderUI(header)
            adapter.submitInspectionItems(items)
            updateCompletionUI()
            updateProgressUI()
        }

        b.btnGuardar.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                saveInspectionAndAskForPdf()
            }
        }

        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_preflight_checklist, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_export_pdf -> {
                        exportarPdf()
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner)
    }

    private suspend fun saveInspectionAndAskForPdf() {
        header = repo.getHeader(inspectionId)
        items = repo.getItems(inspectionId)

        val faltantes = missingRequiredTitles(items, checklist)
        val allDone = items.isNotEmpty() && items.all { it.checked }

        repo.saveInspectionCompletion(inspectionId)

        val dialogTitle: String
        val dialogMessage: String

        if (allDone) {
            dialogTitle = "Inspección finalizada"
            dialogMessage = "La inspección quedó finalizada correctamente.\n\n¿Deseas generar el PDF ahora?"
        } else {
            dialogTitle = "Borrador guardado"
            dialogMessage = if (faltantes.isNotEmpty()) {
                buildString {
                    append("El borrador se guardó correctamente.\n")
                    append("Aún faltan ")
                    append(faltantes.size)
                    append(" paso(s) requerido(s).\n\n")
                    append("¿Deseas generar el PDF ahora?")
                }
            } else {
                "El borrador se guardó correctamente.\n\n¿Deseas generar el PDF ahora?"
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle(dialogTitle)
            .setMessage(dialogMessage)
            .setNegativeButton("No") { _, _ ->
                parentFragmentManager.popBackStack()
            }
            .setPositiveButton("Sí, generar PDF") { _, _ ->
                exportarPdf {
                    if (isAdded) {
                        parentFragmentManager.popBackStack()
                    }
                }
            }
            .show()
    }

    private suspend fun handleItemToggle(itemId: Long, checked: Boolean) {
        repo.toggleItem(inspectionId, itemId, checked)

        var latestItems = repo.getItems(inspectionId)
        val changedParents = syncParentStates(latestItems)
        if (changedParents) {
            latestItems = repo.getItems(inspectionId)
        }

        items = latestItems
        adapter.submitInspectionItems(items)
        updateCompletionUI()
        updateProgressUI()
    }

    private suspend fun syncParentStates(currentItems: List<PreflightRepository.Item>): Boolean {
        val itemsByOrder = currentItems.associateBy { it.orderIndex }
        var changed = false

        val parentsByDepth = flatEntries
            .filter { it.hasChildren }
            .sortedByDescending { it.depth }

        parentsByDepth.forEach { parentEntry ->
            val parentItem = itemsByOrder[parentEntry.orderIndex] ?: return@forEach
            val childItems = childOrderIndexesByParent[parentEntry.orderIndex]
                .orEmpty()
                .mapNotNull(itemsByOrder::get)

            if (childItems.isEmpty()) return@forEach

            val shouldBeChecked = childItems.all { it.checked }
            if (parentItem.checked != shouldBeChecked) {
                repo.toggleItem(inspectionId, parentItem.id, shouldBeChecked)
                changed = true
            }
        }

        return changed
    }

    private fun progressStageMessage(pct: Int): String =
        when {
            pct < 20 -> "Preparando encabezado…"
            pct < 50 -> "Generando checklist…"
            pct < 80 -> "Aplicando firma…"
            pct < 98 -> "Guardando en Documentos…"
            else -> "Completando…"
        }

    private fun bindHeaderUI(h: PreflightRepository.Header?) {
        if (h == null) {
            b.txtHeader.text = "Inspección Pre-vuelo"
            b.tvMissionState.text = "Pendiente"
            b.tvMissionState.setBackgroundResource(R.drawable.bg_preflight_status_pending)
            b.tvMissionState.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.preflight_pending_text)
            )
            b.cardHeader.visibility = View.GONE
            return
        }
        b.cardHeader.visibility = View.VISIBLE

        b.txtHeader.text = "Inspección · ${h.matAeronave}"

        val fechaStr = formatDateUi(h.fechaEpochMillis)
        b.tvMatAeronave.text = h.matAeronave
        b.tvFechaHora.text = "$fechaStr · ${h.hora24.ifBlank { "--:--" }}"

        val grado = h.tecnicoGrado.ifBlank { "" }
        val esp = h.tecnicoEspecialidad.ifBlank { "" }
        val nombre = h.tecnicoNombre.ifBlank { "" }

        val tecnicoLinea = listOf(grado, esp, nombre)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "—" }

        b.tvTecnico.text = tecnicoLinea

        val hsTot = h.hsTotales?.takeIf { it.isNotBlank() } ?: "—"
        val hsDisp = h.hsDisponibles?.takeIf { it.isNotBlank() } ?: "—"
        b.tvHsTot.text = hsTot
        b.tvHsDisp.text = hsDisp

        val matTec = h.tecnicoMatricula?.takeIf { it.isNotBlank() } ?: "—"
        b.tvMatTec.text = matTec
    }

    private fun setupMissionPanelToggle() {
        updateMissionPanelVisibility()
        b.tvToggleMissionPanel.setOnClickListener {
            isMissionPanelExpanded = !isMissionPanelExpanded
            updateMissionPanelVisibility()
        }
    }

    private fun updateMissionPanelVisibility() {
        b.panelMissionContent.visibility = if (isMissionPanelExpanded) View.VISIBLE else View.GONE
        b.tvToggleMissionPanel.text = if (isMissionPanelExpanded) "Ocultar" else "Mostrar"
    }

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

    private fun exportarPdf(onSuccess: (() -> Unit)? = null) {
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
                        exportarPdfSinValidar(onSuccess)
                    }
                    .show()
                return@launch
            }

            exportarPdfSinValidar(onSuccess)
        }
    }

    private fun exportarPdfSinValidar(onSuccess: (() -> Unit)? = null) {
        viewLifecycleOwner.lifecycleScope.launch {
            header = repo.getHeader(inspectionId)
            items = repo.getItems(inspectionId)

            if (signatureBitmap == null) {
                AlertDialog.Builder(requireContext())
                    .setTitle("Firma requerida")
                    .setMessage("¿Deseas agregar la firma antes de exportar?")
                    .setNegativeButton("Exportar sin firma") { _, _ ->
                        doExportPdf(onSuccess)
                    }
                    .setPositiveButton("Agregar firma") { _, _ ->
                        showSignatureDialog { bmp ->
                            signatureBitmap = bmp
                            doExportPdf(onSuccess)
                        }
                    }
                    .show()
                return@launch
            } else {
                doExportPdf(onSuccess)
            }
        }
    }

    private fun doExportPdf(onSuccess: (() -> Unit)? = null) {
        val h = header ?: run {
            Toast.makeText(requireContext(), "No hay datos de inspección", Toast.LENGTH_SHORT).show()
            return
        }

        val ui = showPdfProgress()
        ui.progress.isIndeterminate = false
        ui.progress.max = 100
        ui.progress.progress = 0
        ui.txtMsg.text = "Preparando encabezado…\n0%"

        val running = AtomicBoolean(true)
        val finalized = AtomicBoolean(false)

        val updater = Thread {
            var pct = 0
            while (running.get()) {
                try {
                    Thread.sleep(120L)
                } catch (_: InterruptedException) {
                    break
                }

                val step = when {
                    pct < 40 -> 4
                    pct < 75 -> 3
                    pct < 90 -> 2
                    else -> 1
                }

                pct = (pct + step).coerceAtMost(98)

                ui.txtMsg.post {
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
            val checkedByOrderIndex = items.associate { it.orderIndex to it.checked }

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
                    checkedByOrderIndex = checkedByOrderIndex,
                    signatureLabel = "Firma del técnico",
                    watermarkResId = R.drawable.watermark_logo,
                    watermarkAlpha = 60,
                    headerLogoResId = R.drawable.uh60_header,
                    signatureBitmap = signatureBitmap,
                    progress = null
                )
            }

            running.set(false)
            finalized.set(true)

            if (uri != null) {
                ui.txtMsg.post {
                    ui.progress.progress = 100
                    ui.txtMsg.text = "Completado\n100%"

                    val lottie = ui.dialog.findViewById<com.airbnb.lottie.LottieAnimationView>(R.id.lottieLoading)
                    lottie?.cancelAnimation()
                    lottie?.visibility = View.GONE
                }

                PdfExporter.openPdf(requireContext(), uri)

                ui.txtMsg.postDelayed({
                    if (ui.dialog.isShowing) {
                        ui.dialog.dismiss()
                    }
                    onSuccess?.invoke()
                }, 450L)
            } else {
                ui.txtMsg.post {
                    ui.dialog.dismiss()
                }
                Toast.makeText(requireContext(), "No se pudo exportar el PDF", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private data class PdfProgressUI(
        val dialog: AlertDialog,
        val txtMsg: TextView,
        val progress: LinearProgressIndicator,
    ) {
        fun post(block: () -> Unit) {
            txtMsg.post { block() }
        }
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

        val lottie = v.findViewById<com.airbnb.lottie.LottieAnimationView>(R.id.lottieLoading)
        lottie?.playAnimation()

        return PdfProgressUI(dlg, txt, bar)
    }

    private fun missingRequiredTitles(
        items: List<PreflightRepository.Item>,
        checklist: PreflightChecklist,
    ): List<String> {
        val checkedByOrder = items.associate { it.orderIndex to it.checked }

        return ChecklistStore.flattenItems(checklist)
            .filter { entry -> entry.item.required == true }
            .mapNotNull { entry ->
                if (checkedByOrder[entry.orderIndex] == true) null else entry.item.title
            }
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
            if (anim.visibility != View.VISIBLE) {
                anim.setMinAndMaxProgress(0f, 1f)
                anim.alpha = 1f
                anim.visibility = View.VISIBLE
                anim.progress = 0f
                anim.playAnimation()
            }

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
            anim.animate().alpha(0f).setDuration(150).withEndAction {
                anim.visibility = View.GONE
                anim.cancelAnimation()
            }.start()
        }
    }

    private fun updateProgressUI() {
        val total = items.size
        val done = items.count { it.checked }
        val pct = if (total == 0) 0 else (done * 100 / total)

        b.tvProgress.text = "Avance: $pct% · $done / $total pasos"

        when {
            pct == 100 && total > 0 -> {
                b.tvProgress.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.preflight_success_text)
                )
                b.tvMissionState.text = "Lista"
                b.tvMissionState.setBackgroundResource(R.drawable.bg_preflight_status_success)
                b.tvMissionState.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.preflight_success_text)
                )
                b.progressChecklist.setIndicatorColor(
                    ContextCompat.getColor(requireContext(), R.color.preflight_success_text)
                )
                b.progressChecklist.trackColor =
                    ContextCompat.getColor(requireContext(), R.color.preflight_active_bg)
            }

            done > 0 -> {
                b.tvProgress.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.preflight_active_text)
                )
                b.tvMissionState.text = "En curso"
                b.tvMissionState.setBackgroundResource(R.drawable.bg_preflight_status_active)
                b.tvMissionState.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.preflight_active_text)
                )
                b.progressChecklist.setIndicatorColor(
                    ContextCompat.getColor(requireContext(), R.color.preflight_active_text)
                )
                b.progressChecklist.trackColor =
                    ContextCompat.getColor(requireContext(), R.color.preflight_pending_bg)
            }

            else -> {
                b.tvProgress.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.preflight_text_primary)
                )
                b.tvMissionState.text = "Pendiente"
                b.tvMissionState.setBackgroundResource(R.drawable.bg_preflight_status_pending)
                b.tvMissionState.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.preflight_pending_text)
                )
                b.progressChecklist.setIndicatorColor(
                    ContextCompat.getColor(requireContext(), R.color.preflight_command_tint)
                )
                b.progressChecklist.trackColor =
                    ContextCompat.getColor(requireContext(), R.color.preflight_pending_bg)
            }
        }

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
            arguments = Bundle().apply {
                putLong(ARG_ID, id)
            }
        }
    }
}