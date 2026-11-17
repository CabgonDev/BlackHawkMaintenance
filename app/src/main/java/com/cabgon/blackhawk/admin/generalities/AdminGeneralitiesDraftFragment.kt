package com.cabgon.blackhawk.admin.generalities

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cabgon.blackhawk.data.user.UserSessionStore
import com.cabgon.blackhawk.databinding.FragmentAdminGeneralitiesDraftBinding
import com.cabgon.blackhawk.util.Roles
import com.cabgon.blackhawk.util.navigateClean
import kotlinx.coroutines.launch

class AdminGeneralitiesDraftFragment : Fragment() {

    private var _b: FragmentAdminGeneralitiesDraftBinding? = null
    private val b get() = _b!!

    private val repo = AdminGeneralitiesRepository()
    private val importService = AdminGeneralitiesImportService()
    private val publishService = AdminGeneralitiesPublishService()
    private val statusService = AdminGeneralitiesStatusService()

    private lateinit var adapter: AdminGeneralitiesDraftAdapter
    private var role: String = Roles.USER
    private var isBusy: Boolean = false

    private val canEdit: Boolean
        get() = Roles.normalize(role) == Roles.DEVELOPER

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentAdminGeneralitiesDraftBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        role = Roles.normalize(UserSessionStore(requireContext()).getProfile()?.role)
        Log.d(TAG, "onViewCreated role='$role' canEdit=$canEdit")

        b.txtRole.text = "Rol: $role"
        b.txtStatus.text = "Cargando estado OTA…"

        adapter = AdminGeneralitiesDraftAdapter(
            canEdit = canEdit,
            onOpen = { doc -> openEditor(doc) },
            onToggleDeleted = { doc ->
                if (!canEdit || isBusy) return@AdminGeneralitiesDraftAdapter
                lifecycleScope.launch {
                    setBusy(true, "Actualizando estado…")
                    val ok = repo.setDeleted(doc.id, !doc.isDeleted)
                    setBusy(false, if (ok) "Listo." else "No se pudo actualizar.")
                    if (!ok) toast("No se pudo actualizar.")
                }
            }
        )

        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.recycler.adapter = adapter

        b.btnAdd.setOnClickListener {
            if (!canEdit) return@setOnClickListener toast("Solo Developer puede editar.")
            if (isBusy) return@setOnClickListener
            openEditor(null)
        }

        b.btnImportStable.setOnClickListener {
            if (!canEdit) return@setOnClickListener toast("Solo Developer puede editar.")
            if (isBusy) return@setOnClickListener

            lifecycleScope.launch {
                setBusy(true, "Importando desde STABLE…")
                val res = importService.importFromStableToDraft(requireContext().applicationContext)
                setBusy(false, res.message)
                if (!res.ok) toast(res.message)
                refreshOtaStatus()
            }
        }

        b.btnPublishStable.setOnClickListener {
            if (!canEdit) return@setOnClickListener toast("Solo Developer puede editar.")
            if (isBusy) return@setOnClickListener

            lifecycleScope.launch {
                setBusy(true, "Publicando STABLE…")
                val res = publishService.publishStable(requireContext().applicationContext)
                setBusy(false, res.message)
                if (!res.ok) toast(res.message)
                refreshOtaStatus()
            }
        }

        b.btnRollback.setOnClickListener {
            if (!canEdit) return@setOnClickListener toast("Solo Developer puede editar.")
            if (isBusy) return@setOnClickListener
            navigateClean(AdminGeneralitiesRollbackFragment(), addToBackStack = true)
        }

        repo.observeDraft { list ->
            adapter.submitList(list)
            b.txtCount.text = "Secciones: ${list.count { !it.isDeleted }} · (Incluye eliminadas: ${list.size})"
        }

        // Cargar estado OTA del canal stable
        refreshOtaStatus()
        applyEnabledState()
    }

    private fun openEditor(doc: AdminGeneralitySectionDoc?) {
        navigateClean(AdminGeneralitiesEditSectionFragment.newInstance(doc?.id.orEmpty()), addToBackStack = true)
    }

    private fun setBusy(busy: Boolean, status: String) {
        isBusy = busy
        b.txtStatus.text = status
        applyEnabledState()
    }

    private fun applyEnabledState() {
        val enabled = canEdit && !isBusy
        val alpha = if (enabled) 1f else 0.35f

        b.btnAdd.isEnabled = enabled
        b.btnImportStable.isEnabled = enabled
        b.btnPublishStable.isEnabled = enabled
        b.btnRollback.isEnabled = enabled

        b.btnAdd.alpha = alpha
        b.btnImportStable.alpha = alpha
        b.btnPublishStable.alpha = alpha
        b.btnRollback.alpha = alpha
    }

    private fun refreshOtaStatus() {
        lifecycleScope.launch {
            val status = statusService.getStableStatus()
            if (status == null) {
                b.txtStatus.text = "OTA: Sin spec (stable.generalities no configurado)"
                return@launch
            }
            b.txtStatus.text = "OTA: stable · v${status.version} · ${status.storagePath}"
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        repo.close()
        _b = null
    }

    companion object {
        private const val TAG = "AdminGenUI"
    }
}
