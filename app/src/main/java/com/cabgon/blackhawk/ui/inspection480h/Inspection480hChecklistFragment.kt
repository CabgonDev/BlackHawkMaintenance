package com.cabgon.blackhawk.ui.inspection480h

import android.graphics.Bitmap
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
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.data.inspection480h.Inspection480hChecklist
import com.cabgon.blackhawk.data.inspection480h.Inspection480hChecklistItem
import com.cabgon.blackhawk.data.inspection480h.Inspection480hChecklistStore
import com.cabgon.blackhawk.data.inspection480h.Inspection480hRepository
import com.cabgon.blackhawk.data.user.UserSessionStore
import com.cabgon.blackhawk.databinding.FragmentInspection480hChecklistBinding
import com.cabgon.blackhawk.ui.pdf.BaseInspectionPdfExporter
import com.cabgon.blackhawk.ui.preflight.SignatureView
import com.cabgon.blackhawk.util.NetworkGate
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class Inspection480hChecklistFragment : Fragment() {

    private var _b: FragmentInspection480hChecklistBinding? = null
    private val b get() = _b!!

    private lateinit var repo: Inspection480hRepository
    private lateinit var adapter: Inspection480hChecklistAdapter
    private lateinit var userSession: UserSessionStore

    private lateinit var checklist: Inspection480hChecklist
    private lateinit var flatEntries: List<Inspection480hChecklistStore.FlatChecklistEntry>
    private lateinit var specByOrder: Map<Int, Inspection480hChecklistItem>
    private lateinit var metaByOrder: Map<Int, Inspection480hChecklistStore.FlatChecklistEntry>
    private lateinit var childOrderIndexesByParent: Map<Int, List<Int>>
    private lateinit var sectionOrder: List<String>

    private var inspectionId: String = ""
    private var header: Inspection480hRepository.Header? = null
    private var items: List<Inspection480hRepository.Item> = emptyList()
    private var signatureBitmap: Bitmap? = null
    private var isHeaderCardVisible = false
    private var hasShownCompletionAnimation = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inspectionId = requireArguments().getString(ARG_INSPECTION_ID).orEmpty()
    }

    override fun onResume() {
        super.onResume()
        if (::repo.isInitialized) {
            ensureOnlineChecklistAccess()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentInspection480hChecklistBinding.inflate(inflater, container, false)
        repo = Inspection480hRepository(requireContext())
        userSession = UserSessionStore(requireContext())

        checklist = Inspection480hChecklistStore.loadChecklist(requireContext())
        flatEntries = Inspection480hChecklistStore.flattenItems(checklist)
        specByOrder = flatEntries.associate { it.orderIndex to it.item }
        metaByOrder = flatEntries.associateBy { it.orderIndex }
        childOrderIndexesByParent = flatEntries
            .filter { it.parentOrderIndex != null }
            .groupBy(
                keySelector = { it.parentOrderIndex!! },
                valueTransform = { it.orderIndex }
            )
        sectionOrder = checklist.sections.map { it.title.ifBlank { "Área" } }

        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!ensureOnlineChecklistAccess()) return

        adapter = Inspection480hChecklistAdapter(
            specByOrder = specByOrder,
            metaByOrder = metaByOrder,
            sectionOrder = sectionOrder
        ) { itemId, checked ->
            viewLifecycleOwner.lifecycleScope.launch {
                if (!NetworkGate.hasValidatedConnection(requireContext())) {
                    Toast.makeText(requireContext(), "Sin conexión. No se puede sincronizar el check.", Toast.LENGTH_SHORT).show()
                    adapter.submitInspectionItems(items)
                    updateProgressUI()
                    return@launch
                }
                if (isInspectionClosed()) {
                    Toast.makeText(requireContext(), "La inspección ya fue finalizada y no se puede editar.", Toast.LENGTH_SHORT).show()
                    adapter.submitInspectionItems(items)
                    updateProgressUI()
                    return@launch
                }

                val previousItems = items
                val user = FirebaseAuth.getInstance().currentUser
                val uid = user?.uid
                val profile = userSession.getProfile()
                val techName = profile?.nombre.orEmpty()
                val displayName = user?.displayName.orEmpty()
                val supervisorName = header?.supervisorFullName.orEmpty()
                val rawName = when {
                    techName.isNotBlank() -> techName
                    displayName.isNotBlank() -> displayName
                    supervisorName.isNotBlank() -> supervisorName
                    else -> ""
                }

                val firstLastName = extractPaternalLastName(rawName)
                adapter.applyOptimisticToggle(itemId, checked, firstLastName.takeIf { checked })
                items = previousItems.map { item ->
                    if (item.id != itemId) item else item.copy(
                        checked = checked,
                        checkedByFirstLastName = if (checked) firstLastName else null,
                        checkedAt = if (checked) System.currentTimeMillis() else null
                    )
                }
                updateCompletionUI()
                updateProgressUI()

                runCatching {
                    repo.setItemChecked(
                        inspectionId = inspectionId,
                        itemId = itemId,
                        checked = checked,
                        uid = uid ?: profile?.uid,
                        firstLastName = firstLastName
                    )
                }.onFailure {
                    items = previousItems
                    adapter.submitInspectionItems(items)
                    updateCompletionUI()
                    updateProgressUI()
                    Toast.makeText(requireContext(), it.message ?: "No se pudo sincronizar el check.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.recycler.adapter = adapter

        b.recycler.itemAnimator = null

        b.cardHeader.setOnClickListener {
            if (header == null) return@setOnClickListener
            TransitionManager.beginDelayedTransition(b.root, AutoTransition().apply {
                duration = 180
            })
            isHeaderCardVisible = !isHeaderCardVisible
            applyHeaderCardVisibility()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repo.observeInspection(inspectionId).collectLatest { data ->
                if (data == null) {
                    header = null
                    items = emptyList()
                    bindHeaderUI(null)
                    adapter.submitInspectionItems(items)
                    adapter.setReadOnly(false)
                    b.btnGuardar.isEnabled = false
                    updateCompletionUI()
                    updateProgressUI()
                } else {
                    header = data.header
                    items = data.items

                    if (!isInspectionClosed() && syncParentStates(items)) {
                        val refreshed = repo.getInspection(inspectionId)
                        if (refreshed != null) {
                            header = refreshed.header
                            items = refreshed.items
                        }
                    }

                    bindHeaderUI(header)
                    adapter.submitInspectionItems(items)
                    adapter.setReadOnly(isInspectionClosed())
                    b.btnGuardar.isEnabled = true
                    updateCompletionUI()
                    updateProgressUI()
                }
            }
        }

        b.btnGuardar.setOnClickListener {
            if (!NetworkGate.hasValidatedConnection(requireContext())) {
                Toast.makeText(requireContext(), "Sin conexión. No se puede guardar en la nube.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            when {
                isInspectionClosed() -> askGeneratePdfAfterClose()
                isInspectionReadyToClose() -> confirmFinalizeInspection()
                else -> saveProgressAndAskForPdf()
            }
        }

        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_preflight_checklist, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_export_pdf -> {
                        exportarPdf480h()
                        true
                    }

                    else -> false
                }
            }
        }, viewLifecycleOwner)
    }

    private data class ActorInfo(
        val uid: String?,
        val firstLastName: String
    )

    private fun resolveActorInfo(): ActorInfo {
        val user = FirebaseAuth.getInstance().currentUser
        val profile = userSession.getProfile()
        val rawName = when {
            !profile?.nombre.isNullOrBlank() -> profile.nombre
            !user?.displayName.isNullOrBlank() -> user.displayName.orEmpty()
            else -> header?.supervisorFullName.orEmpty()
        }
        return ActorInfo(
            uid = user?.uid ?: profile?.uid,
            firstLastName = extractPaternalLastName(rawName)
        )
    }

    private fun isInspectionClosed(): Boolean = header?.state == "closed"

    private fun progressSnapshot(): Triple<Int, Int, Int> {
        val actionable = actionableItems(items)
        val total = actionable.size
        val done = actionable.count { it.checked }
        val pct = if (total == 0) 0 else (done * 100 / total)
        return Triple(total, done, pct)
    }

    private fun isInspectionReadyToClose(): Boolean {
        val (total, done, _) = progressSnapshot()
        return total > 0 && done == total && !isInspectionClosed()
    }

    private fun saveProgressAndAskForPdf() {
        val actor = resolveActorInfo()
        val (_, _, pct) = progressSnapshot()

        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                repo.saveProgress(
                    inspectionId = inspectionId,
                    uid = actor.uid,
                    firstLastName = actor.firstLastName.takeIf { it.isNotBlank() }
                )
            }.onSuccess {
                Toast.makeText(requireContext(), "Avance guardado.", Toast.LENGTH_SHORT).show()
                AlertDialog.Builder(requireContext())
                    .setTitle("Guardar avance")
                    .setMessage("¿Deseas generar el PDF con el ${pct}% de avance?")
                    .setNegativeButton("No", null)
                    .setPositiveButton("Generar PDF") { _, _ ->
                        exportarPdf480h()
                    }
                    .show()
            }.onFailure {
                Toast.makeText(requireContext(), it.message ?: "No se pudo guardar el avance.", Toast.LENGTH_SHORT).show()
                adapter.submitInspectionItems(items)
                updateProgressUI()
            }
        }
    }

    private fun confirmFinalizeInspection() {
        AlertDialog.Builder(requireContext())
            .setTitle("Finalizar inspección")
            .setMessage("¿Realmente deseas finalizar la inspección?\n\nUna vez finalizada no se puede volver a editar.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Finalizar") { _, _ ->
                finalizeInspectionAndAskForPdf()
            }
            .show()
    }

    private fun finalizeInspectionAndAskForPdf() {
        val actor = resolveActorInfo()

        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                repo.finalizeInspection(
                    inspectionId = inspectionId,
                    uid = actor.uid,
                    firstLastName = actor.firstLastName.takeIf { it.isNotBlank() }
                )
            }.onSuccess {
                val refreshed = repo.getInspection(inspectionId)
                if (refreshed != null) {
                    header = refreshed.header
                    items = refreshed.items
                    bindHeaderUI(header)
                    adapter.submitInspectionItems(items)
                    adapter.setReadOnly(isInspectionClosed())
                    updateCompletionUI()
                    updateProgressUI()
                }
                AlertDialog.Builder(requireContext())
                    .setTitle("Inspección finalizada")
                    .setMessage("¿Deseas generar el PDF?")
                    .setNegativeButton("No", null)
                    .setPositiveButton("Generar PDF") { _, _ ->
                        exportarPdf480h()
                    }
                    .show()
            }.onFailure {
                Toast.makeText(requireContext(), it.message ?: "No se pudo finalizar la inspección.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun askGeneratePdfAfterClose() {
        AlertDialog.Builder(requireContext())
            .setTitle("Generar PDF")
            .setMessage("La inspección ya está finalizada. ¿Deseas generar el PDF con los datos y fechas al cierre?")
            .setNegativeButton("No", null)
            .setPositiveButton("Generar PDF") { _, _ ->
                exportarPdf480h()
            }
            .show()
    }

    private fun ensureOnlineChecklistAccess(): Boolean {
        if (inspectionId.isBlank() || !NetworkGate.hasValidatedConnection(requireContext())) {
            Toast.makeText(requireContext(), "Sin conexión. No se puede acceder al checklist.", Toast.LENGTH_SHORT).show()
            parentFragmentManager.popBackStack()
            return false
        }
        return true
    }

    private suspend fun syncParentStates(currentItems: List<Inspection480hRepository.Item>): Boolean {
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
                repo.setItemChecked(
                    inspectionId = inspectionId,
                    itemId = parentItem.id,
                    checked = shouldBeChecked,
                    uid = null,
                    firstLastName = parentItem.checkedByFirstLastName
                )
                changed = true
            }
        }

        return changed
    }

    private fun bindHeaderUI(h: Inspection480hRepository.Header?) {
        if (h == null) {
            b.txtHeader.text = checklist.title
            applyHeaderCardVisibility()
            return
        }

        b.txtHeader.text = "Inspección 480H · ${h.matAeronave}"
        b.tvMatAeronave.text = h.matAeronave.ifBlank { "—" }
        b.tvFechaHora.text = formatDateTimeUi(h.fechaEpochMillis)

        val supervisorLinea = listOf(
            h.supervisorGrade.ifBlank { "" },
            h.supervisorSpecialty.ifBlank { "" },
            h.supervisorFullName.ifBlank { "" }
        )
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "—" }

        b.tvSupervisor.text = supervisorLinea
        b.tvHsTotales.text = h.hsTotales.toString()
        b.tvMatSupervisor.text = h.supervisorMatricula.ifBlank { "—" }
        applyHeaderCardVisibility()
    }

    private fun applyHeaderCardVisibility() {
        val hasHeader = header != null
        b.cardHeader.isVisible = hasHeader
        if (!hasHeader) return

        b.headerContent.isVisible = isHeaderCardVisible
        b.tvHeaderToggleLabel.text = if (isHeaderCardVisible) {
            "Ocultar datos de cabecera"
        } else {
            "Mostrar datos de cabecera"
        }
        b.tvHeaderToggleIcon.text = if (isHeaderCardVisible) "▲" else "▼"
        b.tvHeaderToggleIcon.alpha = if (isHeaderCardVisible) 1f else 0.92f
    }

    private fun updateCompletionUI() {
        val anim = b.lottieDone
        if (isInspectionClosed()) {
            hasShownCompletionAnimation = true
            if (anim.isVisible) {
                anim.cancelAnimation()
                anim.visibility = View.GONE
            }
            return
        }

        val actionable = actionableItems(items)
        val isAllChecked = actionable.isNotEmpty() && actionable.all { it.checked }

        if (isAllChecked && !hasShownCompletionAnimation) {
            hasShownCompletionAnimation = true
            anim.setMinAndMaxProgress(0f, 1f)
            anim.alpha = 1f
            anim.visibility = View.VISIBLE
            anim.progress = 0f
            anim.bringToFront()
            anim.playAnimation()

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
        } else if (!isAllChecked) {
            hasShownCompletionAnimation = false
            if (anim.isVisible) {
                anim.animate()
                    .alpha(0f)
                    .setDuration(150)
                    .withEndAction {
                        anim.visibility = View.GONE
                        anim.cancelAnimation()
                    }
                    .start()
            }
        }
    }

    private fun updateProgressUI() {
        val (total, done, pct) = progressSnapshot()
        val pending = (total - done).coerceAtLeast(0)
        val isClosed = isInspectionClosed()

        val state = when {
            isClosed -> Triple(
                "Cerrada",
                R.drawable.bg_inspection_status_success,
                R.color.inspection_success_text
            )
            pct == 100 && total > 0 -> Triple(
                "Lista para cierre",
                R.drawable.bg_inspection_status_success,
                R.color.inspection_success_text
            )
            done > 0 -> Triple(
                "En ejecución",
                R.drawable.bg_inspection_status_active,
                R.color.inspection_active_text
            )
            else -> Triple(
                "Pendiente",
                R.drawable.bg_inspection_status_pending,
                R.color.inspection_pending_text
            )
        }

        b.tvProgress.text = "Avance: $pct%  ($done / $total)"
        b.tvMetricDone.text = done.toString()
        b.tvMetricPending.text = pending.toString()
        b.tvMetricTotal.text = total.toString()

        b.tvProgressSummary.text = when {
            isClosed -> "Inspección finalizada. El checklist quedó en solo lectura."
            total == 0 -> "Sin tareas accionables registradas todavía"
            pct == 100 -> "Checklist completo y listo para finalizar"
            done > 0 -> "$pending tareas pendientes."
            else -> "Checklist pendiente de iniciar"
        }

        b.tvProgressState.text = state.first
        b.tvProgressState.setBackgroundResource(state.second)
        b.tvProgressState.setTextColor(ContextCompat.getColor(requireContext(), state.third))
        b.tvProgress.setTextColor(ContextCompat.getColor(requireContext(), R.color.inspection_text_primary))

        b.progressChecklist.apply {
            max = 100
            progress = pct
            isVisible = total > 0
            val indicatorRes = when {
                isClosed || (pct == 100 && total > 0) -> R.color.inspection_success_text
                done > 0 -> R.color.inspection_active_text
                else -> R.color.inspection_header_end
            }
            val trackRes = when {
                isClosed || (pct == 100 && total > 0) -> R.color.inspection_success_bg
                done > 0 -> R.color.inspection_active_bg
                else -> R.color.inspection_pending_bg
            }
            setIndicatorColor(ContextCompat.getColor(requireContext(), indicatorRes))
            trackColor = ContextCompat.getColor(requireContext(), trackRes)
        }

        b.btnGuardar.text = when {
            isClosed -> "Generar PDF"
            pct == 100 && total > 0 -> "Finalizar inspección"
            else -> "Guardar avance"
        }
    }

    private fun actionableItems(source: List<Inspection480hRepository.Item>): List<Inspection480hRepository.Item> {
        return source.filter { item ->
            val spec = specByOrder[item.orderIndex]
            val meta = metaByOrder[item.orderIndex]
            spec?.warning != true && spec?.info != true && meta?.hasChildren != true
        }
    }

    private fun resolveInspectionDisplayCode(specCode: String?, fallbackCode: String): String? {
        val normalizedSpecCode = specCode
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (normalizedSpecCode != null) return normalizedSpecCode
        return fallbackCode.takeIf { specCode == null && it.isNotBlank() }
    }

    private fun buildPdfRows(
        source: List<Inspection480hRepository.Item>,
        participantLabelsByUid: Map<String, String>,
        sdf: SimpleDateFormat
    ): List<Inspection480hPdfExporter.ItemRow> {
        val itemsByOrder = source.associateBy { it.orderIndex }
        val rows = mutableListOf<Inspection480hPdfExporter.ItemRow>()

        checklist.sections.forEach { section ->
            val sectionTitle = section.title.trim()
            if (sectionTitle.isNotEmpty()) {
                rows += Inspection480hPdfExporter.ItemRow(
                    index = 0,
                    code = "",
                    description = sectionTitle,
                    checked = false,
                    responsable = null,
                    fechaHoraCheck = null,
                    participantLabel = null,
                    rowKind = BaseInspectionPdfExporter.RowKind.SECTION,
                    indentLevel = 0
                )
            }

            flatEntries
                .filter { it.sectionTitle == section.title }
                .forEach { entry ->
                    val item = itemsByOrder[entry.orderIndex]
                    val spec = specByOrder[entry.orderIndex] ?: entry.item
                    val effectiveCode = resolveInspectionDisplayCode(
                        specCode = spec.code,
                        fallbackCode = item?.code.orEmpty()
                    )
                    val fullTitle = spec.title
                        .takeIf { it.isNotBlank() }
                        ?: item?.longText
                            .orEmpty()
                    val shortTitle = spec.short
                        ?.takeIf { it.isNotBlank() }
                        ?: item?.shortText
                            ?.takeIf { it.isNotBlank() }
                        ?: fullTitle
                    val hasChildren = entry.hasChildren
                    val isWarning = spec.warning == true
                    val isInfo = spec.info == true
                    val rowKind = when {
                        hasChildren -> BaseInspectionPdfExporter.RowKind.GROUP
                        isWarning || isInfo -> BaseInspectionPdfExporter.RowKind.NOTE
                        else -> BaseInspectionPdfExporter.RowKind.STANDARD
                    }
                    val noteDescription = if (isWarning || isInfo) {
                        buildPdfNoteDescription(
                            title = fullTitle,
                            text = spec.text,
                            fallback = item?.longText ?: item?.shortText.orEmpty()
                        )
                    } else {
                        ""
                    }
                    val description = when {
                        hasChildren -> fullTitle
                        isWarning || isInfo -> noteDescription
                        else -> shortTitle
                    }.ifBlank { item?.longText ?: item?.shortText.orEmpty() }
                    val participantLabel = item?.checkedByUid
                        ?.let { participantLabelsByUid[it] }
                        ?.takeIf { it.isNotBlank() }
                        ?: item?.checkedByFirstLastName?.takeIf { it.isNotBlank() }
                    val fechaHoraCheck = item?.checkedAt?.let { sdf.format(Date(it)) }
                    val showExecutionMeta = rowKind == BaseInspectionPdfExporter.RowKind.STANDARD

                    rows += Inspection480hPdfExporter.ItemRow(
                        index = entry.orderIndex + 1,
                        code = effectiveCode.orEmpty(),
                        description = description,
                        checked = showExecutionMeta && item?.checked == true,
                        responsable = if (showExecutionMeta) item?.checkedByFirstLastName else null,
                        fechaHoraCheck = if (showExecutionMeta) fechaHoraCheck else null,
                        participantLabel = if (showExecutionMeta && item?.checked == true) participantLabel else null,
                        rowKind = rowKind,
                        indentLevel = entry.depth.coerceAtLeast(0)
                    )
                }
        }

        return rows
    }

    private fun buildPdfNoteDescription(title: String, text: String?, fallback: String): String {
        val normalizedTitle = title.trim()
        val normalizedText = text?.trim().orEmpty()
        val normalizedFallback = fallback.trim()

        return when {
            normalizedTitle.isNotEmpty() && normalizedText.isNotEmpty() -> "$normalizedTitle: $normalizedText"
            normalizedTitle.isNotEmpty() -> normalizedTitle
            normalizedText.isNotEmpty() -> normalizedText
            else -> normalizedFallback
        }
    }

    private fun showSignatureDialog(onSaved: (Bitmap) -> Unit) {
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
                Toast.makeText(requireContext(), "Dibuja la firma primero.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val bmp = sig.exportBitmap()
            dlg.dismiss()
            onSaved(bmp)
        }

        dlg.show()
    }

    private fun exportarPdf480h() {
        val h = header
        if (h == null) {
            Toast.makeText(requireContext(), "No hay datos de inspección", Toast.LENGTH_SHORT).show()
            return
        }

        if (signatureBitmap == null) {
            AlertDialog.Builder(requireContext())
                .setTitle("Firma requerida")
                .setMessage("¿Deseas agregar la firma del supervisor antes de exportar?")
                .setNegativeButton("Exportar sin firma") { _, _ ->
                    doExportPdf480h()
                }
                .setPositiveButton("Agregar firma") { _, _ ->
                    showSignatureDialog { bmp ->
                        signatureBitmap = bmp
                        doExportPdf480h()
                    }
                }
                .show()
        } else {
            doExportPdf480h()
        }
    }

    private fun progressStageMessage(pct: Int): String =
        when {
            pct < 20 -> "Preparando encabezado…"
            pct < 50 -> "Generando checklist…"
            pct < 80 -> "Aplicando firma…"
            pct < 98 -> "Guardando en Documentos…"
            else -> "Completando…"
        }

    private fun doExportPdf480h() {
        val h = header ?: run {
            Toast.makeText(requireContext(), "No hay datos de inspección", Toast.LENGTH_SHORT).show()
            return
        }
        val currentItems = items.sortedBy { it.orderIndex }

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
            val uri = withContext(Dispatchers.Default) {
                val pdfEpoch = if (h.state == "closed") {
                    h.closedAt ?: h.lastSavedAt ?: h.fechaEpochMillis
                } else {
                    h.lastSavedAt ?: h.fechaEpochMillis
                }
                val fechaStr = formatDateTimeUi(pdfEpoch).take(10)
                val horaStr = formatDateTimeUi(pdfEpoch).takeLast(5)

                val headerPdf = Inspection480hPdfExporter.Header(
                    title = if (h.state == "closed") "${checklist.title} · CERRADA" else checklist.title,
                    fecha = fechaStr,
                    hora24 = horaStr,
                    matAeronave = h.matAeronave,
                    supervisorGrado = h.supervisorGrade,
                    supervisorEspecialidad = h.supervisorSpecialty,
                    supervisorNombre = h.supervisorFullName,
                    supervisorMatricula = h.supervisorMatricula,
                    hsTotales = h.hsTotales.toString()
                )

                val sdf = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
                val participantLabelsByUid = resolveParticipantLabels(currentItems)
                val rows = buildPdfRows(
                    source = currentItems,
                    participantLabelsByUid = participantLabelsByUid,
                    sdf = sdf
                )

                Inspection480hPdfExporter.export480h(
                    context = requireContext(),
                    fileNameHint = if (h.state == "closed") "480h_${h.matAeronave}_cierre_${formatDateFile(pdfEpoch)}" else "480h_${h.matAeronave}_${formatDateFile(pdfEpoch)}",
                    header = headerPdf,
                    items = rows,
                    signatureLabel = "",
                    watermarkResId = R.drawable.watermark_logo,
                    watermarkAlpha = 60,
                    headerLogoResId = R.drawable.uh60_header,
                    signatureBitmap = signatureBitmap
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

                try {
                    Inspection480hPdfExporter.openPdf(requireContext(), uri)
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "PDF generado: ${uri.path}", Toast.LENGTH_LONG).show()
                }

                ui.txtMsg.postDelayed({
                    ui.dialog.dismiss()
                }, 600L)
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

    private suspend fun resolveParticipantLabels(source: List<Inspection480hRepository.Item>): Map<String, String> {
        val uids = source.mapNotNull { it.checkedByUid?.takeIf(String::isNotBlank) }.distinct()
        if (uids.isEmpty()) return emptyMap()

        val currentProfile = userSession.getProfile()
        val usersCollection = FirebaseFirestore.getInstance().collection("users")
        val labels = linkedMapOf<String, String>()

        for (uid in uids) {
            val label = when {
                currentProfile?.uid == uid -> buildParticipantLabel(
                    grado = currentProfile.grado,
                    especialidad = currentProfile.especialidad,
                    nombre = currentProfile.nombre
                )
                else -> runCatching {
                    val doc = usersCollection.document(uid).get().await()
                    buildParticipantLabel(
                        grado = doc.getString("grado"),
                        especialidad = doc.getString("especialidad"),
                        nombre = doc.getString("nombre")
                    )
                }.getOrNull()
            }

            if (!label.isNullOrBlank()) {
                labels[uid] = label
            }
        }

        return labels
    }

    private fun buildParticipantLabel(
        grado: String?,
        especialidad: String?,
        nombre: String?
    ): String? {
        return listOf(
            grado.orEmpty().trim(),
            especialidad.orEmpty().trim(),
            nombre.orEmpty().trim()
        )
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .takeIf { it.isNotBlank() }
    }

    private fun formatDateFile(epochMillis: Long): String {
        val sdf = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        return sdf.format(Date(epochMillis))
    }

    private fun formatDateTimeUi(epochMillis: Long): String {
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        return sdf.format(Date(epochMillis))
    }

    private fun extractPaternalLastName(fullName: String): String {
        val parts = fullName
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        if (parts.isEmpty()) return ""

        return if (parts.size >= 2) {
            parts[parts.size - 2]
        } else {
            parts.last()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    companion object {
        private const val ARG_INSPECTION_ID = "inspection_id"

        fun newInstance(id: String): Inspection480hChecklistFragment {
            val f = Inspection480hChecklistFragment()
            f.arguments = Bundle().apply {
                putString(ARG_INSPECTION_ID, id)
            }
            return f
        }
    }
}
