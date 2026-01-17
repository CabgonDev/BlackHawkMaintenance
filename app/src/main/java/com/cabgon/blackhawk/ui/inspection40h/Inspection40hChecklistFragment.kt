package com.cabgon.blackhawk.ui.inspection40h

import android.graphics.Bitmap
import android.os.Bundle
import android.view.*
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.data.inspection40h.Inspection40hRepository
import com.cabgon.blackhawk.databinding.FragmentInspection40hChecklistBinding
import com.cabgon.blackhawk.ui.preflight.SignatureView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.cabgon.blackhawk.data.user.UserSessionStore


class Inspection40hChecklistFragment : Fragment() {

    private var _b: FragmentInspection40hChecklistBinding? = null
    private val b get() = _b!!

    private lateinit var repo: Inspection40hRepository
    private lateinit var adapter: Inspection40hChecklistAdapter

    private lateinit var userSession: UserSessionStore

    private var inspectionId: Long = -1L
    private var header: Inspection40hRepository.Header? = null
    private var items: List<Inspection40hRepository.Item> = emptyList()

    private var signatureBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inspectionId = requireArguments().getLong(ARG_INSPECTION_ID)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentInspection40hChecklistBinding.inflate(inflater, container, false)
        repo = Inspection40hRepository(requireContext())
        userSession = UserSessionStore(requireContext())
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = Inspection40hChecklistAdapter { itemId, checked ->
            viewLifecycleOwner.lifecycleScope.launch {
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

                repo.setItemChecked(
                    itemId = itemId,
                    checked = checked,
                    uid = uid ?: profile?.uid,
                    firstLastName = firstLastName
                )
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

        // Observar inspección (header + items)
        viewLifecycleOwner.lifecycleScope.launch {
            repo.observeInspection(inspectionId).collectLatest { data ->
                if (data == null) {
                    header = null
                    items = emptyList()
                    bindHeaderUI(null)
                    adapter.submitList(items)
                    b.btnGuardar.isEnabled = false
                    updateCompletionUI()
                    updateProgressUI()
                } else {
                    header = data.header
                    items = data.items
                    bindHeaderUI(header)
                    adapter.submitList(items)
                    b.btnGuardar.isEnabled = true
                    updateCompletionUI()
                    updateProgressUI()
                }
            }
        }

        // Botón guardar / finalizar
        b.btnGuardar.setOnClickListener {
            val total = items.size
            val done = items.count { it.checked }
            val allDone = (total > 0 && done == total)

            if (allDone) {
                Toast.makeText(
                    requireContext(),
                    "Inspección 40H finalizada (lógica de cierre pendiente).",
                    Toast.LENGTH_SHORT
                ).show()
                parentFragmentManager.popBackStack()
            } else {
                Toast.makeText(
                    requireContext(),
                    "Borrador de inspección 40H guardado.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // AppBar: Exportar PDF (igual que Preflight)
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_preflight_checklist, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_export_pdf -> { exportarPdf40h(); true }
                    else -> false
                }
            }
        }, viewLifecycleOwner)
    }

    // ---------- Encabezado (tarjeta) ----------

    private fun bindHeaderUI(h: Inspection40hRepository.Header?) {
        if (h == null) {
            b.cardHeader.visibility = View.GONE
            b.txtHeader.text = "Inspección 40 horas"
            return
        }

        b.cardHeader.visibility = View.VISIBLE
        b.txtHeader.text = "Inspección 40H · ${h.matAeronave}"

        b.tvMatAeronave.text = h.matAeronave.ifBlank { "—" }

        val fechaStr = formatDateTimeUi(h.fechaEpochMillis)
        b.tvFechaHora.text = fechaStr

        val grado = h.supervisorGrade.ifBlank { "" }
        val esp = h.supervisorSpecialty.ifBlank { "" }
        val nombre = h.supervisorFullName.ifBlank { "" }

        val supervisorLinea = listOf(grado, esp, nombre)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "—" }

        b.tvSupervisor.text = supervisorLinea
        b.tvHsTotales.text = h.hsTotales.toString()
        b.tvMatSupervisor.text = h.supervisorMatricula.ifBlank { "—" }
    }

    // ---------- Animación de completado ----------

    private fun updateCompletionUI() {
        val isAllChecked = items.isNotEmpty() && items.all { it.checked }
        val anim = b.lottieDone

        if (isAllChecked) {
            if (anim.visibility != View.VISIBLE) {
                anim.setMinAndMaxProgress(0f, 1f)
                anim.alpha = 1f
                anim.visibility = View.VISIBLE
                anim.progress = 0f
                anim.bringToFront()
                anim.playAnimation()
            }

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

    // ---------- Porcentaje de avance ----------

    private fun updateProgressUI() {
        val total = items.size
        val done = items.count { it.checked }
        val pct = if (total == 0) 0 else (done * 100 / total)

        b.tvProgress.text = "Avance: $pct%  ($done / $total)"

        val colorRes = if (pct == 100) R.color.green else R.color.red
        b.tvProgress.setTextColor(
            ContextCompat.getColor(requireContext(), colorRes)
        )

        b.progressChecklist.apply {
            max = 100
            progress = pct
            isVisible = total > 0
        }

        val allDone = (pct == 100 && total > 0)
        b.btnGuardar.text = if (allDone) "Finalizar inspección" else "Guardar borrador"
    }

    // ---------- Firma dibujable (igual que Preflight) ----------

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

    // ---------- Exportar PDF (flujo con firma) ----------

    private fun exportarPdf40h() {
        val h = header
        if (h == null) {
            Toast.makeText(requireContext(), "No hay datos de inspección", Toast.LENGTH_SHORT).show()
            return
        }

        // Si no hay firma todavía → preguntar como en Preflight
        if (signatureBitmap == null) {
            AlertDialog.Builder(requireContext())
                .setTitle("Firma requerida")
                .setMessage("¿Deseas agregar la firma del supervisor antes de exportar?")
                .setNegativeButton("Exportar sin firma") { _, _ ->
                    doExportPdf40h()
                }
                .setPositiveButton("Agregar firma") { _, _ ->
                    showSignatureDialog { bmp ->
                        signatureBitmap = bmp
                        doExportPdf40h()
                    }
                }
                .show()
        } else {
            doExportPdf40h()
        }
    }

    // ---------- Export real PDF + barra + Lottie (clonado de Preflight) ----------
    /** Mensaje “bonito” según el porcentaje de avance visual. */
    private fun progressStageMessage(pct: Int): String =
        when {
            pct < 20  -> "Preparando encabezado…"
            pct < 50  -> "Generando checklist…"
            pct < 80  -> "Aplicando firma…"
            pct < 98  -> "Guardando en Documentos…"
            else      -> "Completando…"
        }
    private fun doExportPdf40h() {
        val h = header ?: run {
            Toast.makeText(requireContext(), "No hay datos de inspección", Toast.LENGTH_SHORT).show()
            return
        }
        val currentItems = items

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
                    else     -> 1
                }

                pct = (pct + step).coerceAtMost(98)

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
            val uri = withContext(Dispatchers.Default) {
                val fechaStr = formatDateTimeUi(h.fechaEpochMillis).take(10)
                val horaStr = formatDateTimeUi(h.fechaEpochMillis).takeLast(5)

                val headerPdf = Inspection40hPdfExporter.Header(
                    title = "Inspección 40 horas UH-60L",
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
                val profile = userSession.getProfile()
                val participantLabelBase = listOf(
                    profile?.grado.orEmpty(),
                    profile?.especialidad.orEmpty(),
                    profile?.nombre.orEmpty()
                )
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                val rows = currentItems.mapIndexed { index, item ->
                    val fh = item.checkedAt?.let { sdf.format(Date(it)) }
                    Inspection40hPdfExporter.ItemRow(
                        index = index + 1,
                        code = item.code,
                        description = item.shortText,
                        checked = item.checked,
                        responsable = item.checkedByFirstLastName,  // sigue siendo el apellido corto
                        fechaHoraCheck = fh,
                        participantLabel = participantLabelBase.takeIf { it.isNotBlank() }
                    )
                }

                Inspection40hPdfExporter.export40h(
                    context = requireContext(),
                    fileNameHint = "40h_${h.matAeronave}_${formatDateFile(h.fechaEpochMillis)}",
                    header = headerPdf,
                    items = rows,
                    signatureLabel = "Firma del supervisor",
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
                    Inspection40hPdfExporter.openPdf(requireContext(), uri)
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

    // ---------- Diálogo de progreso (copiado de Preflight) ----------

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

        val lottie = v.findViewById<com.airbnb.lottie.LottieAnimationView>(R.id.lottieLoading)
        lottie?.playAnimation()

        return PdfProgressUI(dlg, txt, bar)
    }

    // ---------- Utils ----------

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
            // Asumiendo formato típico: Nombre(s) ApellidoPaterno ApellidoMaterno
            parts[parts.size - 2]
        } else {
            // Solo una palabra, usamos esa
            parts.last()
        }
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    companion object {
        private const val ARG_INSPECTION_ID = "inspection_id"

        fun newInstance(id: Long): Inspection40hChecklistFragment {
            val f = Inspection40hChecklistFragment()
            f.arguments = Bundle().apply {
                putLong(ARG_INSPECTION_ID, id)
            }
            return f
        }
    }
}
