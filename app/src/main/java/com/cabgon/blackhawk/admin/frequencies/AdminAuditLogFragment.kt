package com.cabgon.blackhawk.admin.frequencies

import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cabgon.blackhawk.data.user.UserSessionStore
import com.cabgon.blackhawk.databinding.FragmentAdminAuditLogBinding
import com.cabgon.blackhawk.util.Roles
import kotlinx.coroutines.launch
import java.util.Date

class AdminAuditLogFragment : Fragment() {

    private var _b: FragmentAdminAuditLogBinding? = null
    private val b get() = _b!!

    private lateinit var repo: AdminFrequenciesRepository
    private lateinit var adapter: AdminAuditLogAdapter

    private var canRollback: Boolean = false
    private var reg: com.google.firebase.firestore.ListenerRegistration? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentAdminAuditLogBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        repo = AdminFrequenciesRepository()

        val role = UserSessionStore(requireContext()).getProfile()?.role ?: Roles.USER
        canRollback = Roles.normalize(role) == Roles.DEVELOPER

        b.txtRole.text = "Rol: ${Roles.normalize(role)}  |  Rollback: ${if (canRollback) "SI" else "NO"}"

        adapter = AdminAuditLogAdapter(
            canRollback = canRollback,
            onDetails = { showDetails(it) },
            onRollback = { e -> confirmRollback(e) }
        )

        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.recycler.adapter = adapter

        b.progress.visibility = View.VISIBLE
        b.txtStatus.text = "Cargando audit log…"

        reg = repo.observeAuditLog(limit = 50) { list ->
            b.progress.visibility = View.GONE
            b.txtStatus.text = "Audit: ${list.size} eventos"
            adapter.submit(list)
        }
    }

    private fun showDetails(e: AdminFrequenciesRepository.AuditEntry) {
        val ts = DateFormat.format("yyyy-MM-dd HH:mm:ss", Date(e.ts))
        val msg = buildString {
            appendLine("Acción: ${e.action}")
            appendLine("Canal: ${e.channel}")
            appendLine("Versión: v${e.version}")
            appendLine("Fecha: $ts")
            appendLine("Bytes: ${e.bytes}")
            appendLine("SHA: ${e.sha256}")
            if (e.stablePath != null) appendLine("Stable: ${e.stablePath}")
            if (e.releasePath != null) appendLine("Release: ${e.releasePath}")
            if (e.itemsCount != null) appendLine("Total: ${e.itemsCount}")
            if (e.baseCount != null) appendLine("Base: ${e.baseCount}")
            if (e.draftCount != null) appendLine("Draft: ${e.draftCount}")
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Detalle")
            .setMessage(msg)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun confirmRollback(e: AdminFrequenciesRepository.AuditEntry) {
        if (!canRollback) return

        val releasePath = e.releasePath
        if (releasePath.isNullOrBlank()) {
            Toast.makeText(requireContext(), "Este evento no tiene releasePath (no es publicable/rollback).", Toast.LENGTH_LONG).show()
            return
        }
        if (e.action != "publish_frequencies") {
            Toast.makeText(requireContext(), "Rollback solo aplica a eventos publish_frequencies.", Toast.LENGTH_LONG).show()
            return
        }

        val msg = buildString {
            appendLine("Vas a apuntar STABLE a esta release:")
            appendLine("v${e.version}")
            appendLine(releasePath)
            appendLine()
            appendLine("Esto cambia lo que descarga la app para Frecuencias.")
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Confirmar Rollback")
            .setMessage(msg)
            .setPositiveButton("Rollback") { _, _ ->
                lifecycleScope.launch {
                    b.progress.visibility = View.VISIBLE
                    val ok = repo.rollbackStableToRelease(
                        version = e.version,
                        releasePath = releasePath,
                        bytes = e.bytes,
                        sha256 = e.sha256
                    )
                    b.progress.visibility = View.GONE

                    if (ok) Toast.makeText(requireContext(), "Rollback aplicado: v${e.version}", Toast.LENGTH_LONG).show()
                    else Toast.makeText(requireContext(), "No se pudo aplicar rollback", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        reg?.remove()
        reg = null
        repo.close()
        _b = null
    }
}
