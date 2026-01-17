package com.cabgon.blackhawk.admin.frequencies

import android.content.Context
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cabgon.blackhawk.data.user.UserSessionStore
import com.cabgon.blackhawk.databinding.DialogAdminFrequencyEditBinding
import com.cabgon.blackhawk.databinding.FragmentAdminFrequenciesBinding
import com.cabgon.blackhawk.util.Roles
import com.cabgon.blackhawk.util.navigateClean
import kotlinx.coroutines.launch
import java.util.Date


class AdminFrequenciesFragment : Fragment() {

    private var _b: FragmentAdminFrequenciesBinding? = null
    private val b get() = _b!!

    private lateinit var repo: AdminFrequenciesRepository
    private lateinit var adapter: AdminFrequenciesAdapter

    private var canEdit: Boolean = false
    private var actorUid: String = ""

    private var lockReg: com.google.firebase.firestore.ListenerRegistration? = null
    private var lastLock: AdminFrequenciesRepository.LockStatus? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentAdminFrequenciesBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        repo = AdminFrequenciesRepository()

        val session = UserSessionStore(requireContext()).getProfile()
        val role = session?.role ?: Roles.USER
        canEdit = Roles.normalize(role) == Roles.DEVELOPER

        // Si tu Profile no trae uid, cambia a FirebaseAuth.getInstance().currentUser?.uid
        actorUid = session?.uid ?: ""

        adapter = AdminFrequenciesAdapter(
            onEdit = { doc -> if (canEdit && !doc.isDeleted) openEditDialog(doc) else toastNoEdit() },
            onDeleteOrTombstone = { doc ->
                if (!canEdit) return@AdminFrequenciesAdapter
                confirmTombstone(doc)
            },
            onRestore = { doc ->
                if (!canEdit) return@AdminFrequenciesAdapter
                lifecycleScope.launch {
                    b.progress.isVisible = true
                    val ok = repo.setTombstone(doc.id, false)
                    val list = repo.fetchDraftOnce()
                    b.progress.isVisible = false
                    if (!ok) Toast.makeText(requireContext(), "No se pudo restaurar", Toast.LENGTH_SHORT).show()
                    adapter.submit(list)
                    b.btnPublish.isEnabled = canEdit && list.isNotEmpty()
                }
            }
        )

        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.recycler.adapter = adapter

        b.txtRole.text = "Rol: ${Roles.normalize(role)}  |  Editar: ${if (canEdit) "SI" else "NO"}"
        b.btnAdd.isEnabled = canEdit
        b.btnPublish.isEnabled = false
        b.btnAuditLog.isEnabled = Roles.isAtLeast(Roles.normalize(role), Roles.MODERATOR)
        b.btnImportPublished.isEnabled = canEdit

        // ✅ Lock UI
        b.btnForceUnlock.isVisible = false
        lockReg = repo.observePublishLock { st ->
            lastLock = st
            val now = System.currentTimeMillis()
            if (st.isActive(now)) {
                val until = DateFormat.format("yyyy-MM-dd HH:mm:ss", Date(st.lockedUntil)).toString()
                b.txtLock.text = "Lock: ACTIVO · by=${st.lockedBy} · until=$until"
                b.btnForceUnlock.isVisible = canEdit
            } else {
                b.txtLock.text = "Lock: libre"
                b.btnForceUnlock.isVisible = false
            }
        }

        b.btnForceUnlock.setOnClickListener {
            if (!canEdit) return@setOnClickListener
            confirmForceUnlock()
        }

        b.btnAuditLog.setOnClickListener {
            navigateClean(AdminAuditLogFragment(), addToBackStack = true)
        }

        b.btnImportPublished.setOnClickListener {
            if (!canEdit) return@setOnClickListener
            confirmImportPublished()
        }

        b.btnAdd.setOnClickListener { openEditDialog(null) }

        b.btnPublish.setOnClickListener {
            if (!canEdit) return@setOnClickListener

            lifecycleScope.launch {
                b.progress.isVisible = true
                b.txtStatus.text = "Preparando publicación…"

                val plan = runCatching { repo.prepareStablePublishPlan(requireContext().applicationContext) }
                    .getOrElse {
                        b.progress.isVisible = false
                        b.txtStatus.text = "Error: ${it.message}"
                        Toast.makeText(requireContext(), it.message ?: "Error", Toast.LENGTH_LONG).show()
                        return@launch
                    }

                b.progress.isVisible = false
                showPublishConfirm(plan)
            }
        }

        b.etFilter.addTextChangedListener { q ->
            adapter.setFilter(q?.toString().orEmpty())
        }

        b.spChannel.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, listOf("stable"))
        b.spChannel.setSelection(0)

        b.progress.isVisible = true
        b.txtStatus.text = "Cargando draft…"
        repo.observeDraft { list ->
            b.progress.isVisible = false
            b.txtStatus.text = "Draft: ${list.size} items${if (list.isNotEmpty()) "  ·  Pendiente de publicar" else ""}"
            adapter.submit(list)
            b.btnPublish.isEnabled = canEdit && list.isNotEmpty()
        }
    }

    private fun confirmTombstone(doc: AdminFrequencyDoc) {
        val msg = """
            Esto NO borra inmediatamente en producción.
            Marca el registro como "tombstone" y será removido del JSON final al publicar.
            
            ¿Marcar para eliminar?
        """.trimIndent()

        AlertDialog.Builder(requireContext())
            .setTitle("Eliminar (tombstone)")
            .setMessage(msg)
            .setPositiveButton("Marcar") { _, _ ->
                lifecycleScope.launch {
                    b.progress.isVisible = true
                    val ok = repo.setTombstone(doc.id, true)
                    val list = repo.fetchDraftOnce()
                    b.progress.isVisible = false
                    if (!ok) Toast.makeText(requireContext(), "No se pudo marcar", Toast.LENGTH_SHORT).show()
                    adapter.submit(list)
                    b.btnPublish.isEnabled = canEdit && list.isNotEmpty()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmForceUnlock() {
        val st = lastLock
        val msg = buildString {
            appendLine("Esto libera el lock manualmente.")
            appendLine("Úsalo solo si una publicación quedó atorada por crash/red.")
            if (st != null && st.isActive()) {
                appendLine()
                appendLine("Lock actual: by=${st.lockedBy} until=${Date(st.lockedUntil)}")
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Force Unlock")
            .setMessage(msg)
            .setPositiveButton("Liberar") { _, _ ->
                lifecycleScope.launch {
                    b.progress.isVisible = true
                    val ok = repo.forceUnlockWithAudit(actorUid.ifBlank { "unknown" })
                    b.progress.isVisible = false
                    if (ok) Toast.makeText(requireContext(), "Lock liberado", Toast.LENGTH_LONG).show()
                    else Toast.makeText(requireContext(), "No se pudo liberar", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showPublishConfirm(plan: AdminFrequenciesRepository.PublishPlan) {
        val diff = plan.diff
        val msg = buildString {
            appendLine("Canal: ${plan.channel}")
            appendLine("Versión actual: v${plan.currentVersion}")
            appendLine("Nueva versión: v${plan.newVersion}")
            appendLine()
            appendLine("Diff (vs publicado actual):")
            appendLine(" + Nuevos: ${diff.added}")
            appendLine(" ~ Modificados: ${diff.modified}")
            appendLine(" - Removidos (tombstone): ${diff.removed}")
            appendLine(" = Sin cambios: ${diff.unchanged}")
            appendLine()
            appendLine("Top estados ( + / ~ / - / = ):")
            appendLine(plan.diffPreviewTopStates())
            appendLine()
            appendLine("Tombstones en Draft: ${plan.tombstoneCount}")
            appendLine()
            appendLine("Stable pointer:")
            appendLine(plan.stablePath)
            appendLine()
            appendLine("Release archivada:")
            appendLine(plan.releasePath)
            appendLine()
            appendLine("Base: ${plan.baseCount}  |  Draft: ${plan.draftCount}  |  Total final: ${plan.totalCount}")
            appendLine()
            appendLine("Acción: Publicar (con lock), registrar audit y limpiar drafts.")
        }

        val dlg = AlertDialog.Builder(requireContext())
            .setTitle("Confirmar publicación OTA")
            .setMessage(msg)
            .setPositiveButton("Publicar", null)
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Preview JSON", null)
            .create()

        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("Preview JSON (stable)")
                    .setMessage(plan.preview())
                    .setPositiveButton("Cerrar", null)
                    .show()
            }

            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dlg.dismiss()
                doPublishWithLock()
            }
        }

        dlg.show()
    }

    private fun doPublishWithLock() {
        lifecycleScope.launch {
            b.progress.isVisible = true
            b.txtStatus.text = "Publicando OTA (lock)…"

            val uid = actorUid
            if (uid.isBlank()) {
                b.progress.isVisible = false
                b.txtStatus.text = "Error: actorUid vacío (perfil sin uid)."
                Toast.makeText(requireContext(), "Falta uid en sesión. Usa FirebaseAuth.currentUser.uid o agrega uid a Profile.", Toast.LENGTH_LONG).show()
                return@launch
            }

            val res = repo.publishStable(requireContext().applicationContext, uid)

            b.progress.isVisible = false

            if (res.ok) {
                b.txtStatus.text =
                    "Publicado ✅ v${res.newVersion} · total=${res.totalCount} · tombstones=${res.tombstoneCount} · sha=${res.shaShort} · bytes=${res.bytes} · drafts limpiados=${res.clearedDraftCount}"

                if (res.warning != null) Toast.makeText(requireContext(), res.warning, Toast.LENGTH_LONG).show()
                else Toast.makeText(requireContext(), "Publicado OTA stable v${res.newVersion}", Toast.LENGTH_LONG).show()

                val list = repo.fetchDraftOnce()
                adapter.submit(list)
                b.btnPublish.isEnabled = canEdit && list.isNotEmpty()
                if (list.isEmpty()) b.txtStatus.text = b.txtStatus.text.toString() + " · Draft vacío"
            } else {
                b.txtStatus.text = "Error: ${res.error}"
                Toast.makeText(requireContext(), res.error ?: "Error", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmImportPublished() {
        val msg = """
            Esto descargará el JSON publicado ACTUAL (stable) y lo cargará en Draft para editar.
            
            Importante:
            - El Draft actual se reemplaza (se limpia y se llena con lo publicado).
            - Tombstones del Draft actual se perderán al reemplazar.
            - Nada se cambia en producción hasta que publiques.
        """.trimIndent()

        AlertDialog.Builder(requireContext())
            .setTitle("Importar publicado a Draft")
            .setMessage(msg)
            .setPositiveButton("Importar") { _, _ -> doImportPublishedReplaceDraft() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun doImportPublishedReplaceDraft() {
        lifecycleScope.launch {
            b.progress.isVisible = true
            b.txtStatus.text = "Importando publicado a Draft…"

            val res = repo.importCurrentStableToDraftReplace(requireContext().applicationContext)

            b.progress.isVisible = false

            if (res.ok) {
                b.txtStatus.text = "Importado ✅  items=${res.itemsImported}  · Draft reemplazado"
                Toast.makeText(requireContext(), "Importado a Draft: ${res.itemsImported}", Toast.LENGTH_LONG).show()

                val list = repo.fetchDraftOnce()
                adapter.submit(list)
                b.btnPublish.isEnabled = canEdit && list.isNotEmpty()
            } else {
                b.txtStatus.text = "Error: ${res.error}"
                Toast.makeText(requireContext(), res.error ?: "Error", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun toastNoEdit() {
        Toast.makeText(requireContext(), "Solo Developer puede editar.", Toast.LENGTH_SHORT).show()
    }

    private fun openEditDialog(doc: AdminFrequencyDoc?) {
        val bind = DialogAdminFrequencyEditBinding.inflate(layoutInflater)

        doc?.let {
            bind.etState.setText(it.state)
            bind.etCity.setText(it.city)
            bind.etAirport.setText(it.airportName)
            bind.etIcao.setText(it.icao)
            bind.etIata.setText(it.iata ?: "")
            bind.etType.setText(it.type)
            bind.etCallsign.setText(it.callsign ?: "")
            bind.etFreqMhz.setText(it.freqMHz?.toString() ?: "")
            bind.etFreqKhz.setText(it.freqKhz?.toString() ?: "")
            bind.etIdent.setText(it.ident ?: "")
            bind.etRemarks.setText(it.remarks ?: "")
            bind.chkEmergency.isChecked = it.isEmergency
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(if (doc == null) "Nuevo Draft" else "Editar Draft")
            .setView(bind.root)
            .setPositiveButton("Guardar", null)
            .setNegativeButton("Cancelar", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val model = AdminFrequencyDoc(
                    id = doc?.id ?: "",
                    state = bind.etState.text.toString().trim(),
                    city = bind.etCity.text.toString().trim(),
                    airportName = bind.etAirport.text.toString().trim(),
                    icao = bind.etIcao.text.toString().trim(),
                    iata = bind.etIata.text.toString().trim().ifBlank { null },
                    type = bind.etType.text.toString().trim(),
                    callsign = bind.etCallsign.text.toString().trim().ifBlank { null },
                    freqMHz = bind.etFreqMhz.text.toString().trim().toDoubleOrNull(),
                    freqKhz = bind.etFreqKhz.text.toString().trim().toDoubleOrNull(),
                    ident = bind.etIdent.text.toString().trim().ifBlank { null },
                    remarks = bind.etRemarks.text.toString().trim().ifBlank { null },
                    isEmergency = bind.chkEmergency.isChecked,
                    isDeleted = doc?.isDeleted ?: false
                )

                val err = validate(model)
                if (err != null) {
                    Toast.makeText(requireContext(), err, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                hideKeyboard(bind.root)

                lifecycleScope.launch {
                    b.progress.isVisible = true
                    val ok = if (doc == null) repo.addDraftItem(model) else repo.updateDraftItem(model)
                    val list = repo.fetchDraftOnce()
                    b.progress.isVisible = false

                    if (!ok) Toast.makeText(requireContext(), "No se pudo guardar", Toast.LENGTH_SHORT).show()
                    adapter.submit(list)
                    b.btnPublish.isEnabled = canEdit && list.isNotEmpty()
                    b.txtStatus.text = "Draft: ${list.size} items${if (list.isNotEmpty()) "  ·  Pendiente de publicar" else ""}"
                    dialog.dismiss()
                }
            }
        }

        dialog.show()
    }

    private fun validate(m: AdminFrequencyDoc): String? {
        if (m.state.isBlank()) return "Estado requerido"
        if (m.city.isBlank()) return "Ciudad requerida"
        if (m.airportName.isBlank()) return "Aeropuerto requerido"
        if (m.icao.isBlank()) return "ICAO requerido"
        if (m.type.isBlank()) return "Tipo requerido"
        if ((m.freqMHz ?: 0.0) <= 0.0 && (m.freqKhz ?: 0.0) <= 0.0) return "Frecuencia MHz o kHz requerida"
        return null
    }

    private fun hideKeyboard(view: View) {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
        view.clearFocus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        lockReg?.remove()
        lockReg = null
        repo.close()
        _b = null
    }
}
