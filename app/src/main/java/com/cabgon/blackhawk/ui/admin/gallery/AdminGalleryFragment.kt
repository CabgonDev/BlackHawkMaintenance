package com.cabgon.blackhawk.ui.admin.gallery

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cabgon.blackhawk.data.user.UserProfile
import com.cabgon.blackhawk.data.user.UserSessionStore
import com.cabgon.blackhawk.databinding.FragmentAdminGalleryBinding
import com.cabgon.blackhawk.ui.gallery.GalleryItem
import com.cabgon.blackhawk.ui.gallery.GalleryRepository
import com.cabgon.blackhawk.ui.gallery.GalleryViewerFragment
import com.cabgon.blackhawk.util.Roles
import com.cabgon.blackhawk.util.navigateClean
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.launch

class AdminGalleryFragment : Fragment() {

    private var _b: FragmentAdminGalleryBinding? = null
    private val b get() = _b!!
    private val repository = GalleryRepository()
    private val regs = mutableListOf<ListenerRegistration>()
    private lateinit var adapter: AdminGalleryAdapter
    private lateinit var profile: UserProfile
    private var currentStatus: String = GalleryItem.STATUS_PENDING_MODERATOR

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentAdminGalleryBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        profile = UserSessionStore(requireContext()).getProfile() ?: run {
            Toast.makeText(requireContext(), "Sin sesión.", Toast.LENGTH_LONG).show()
            parentFragmentManager.popBackStack()
            return
        }

        if (!Roles.isAtLeast(profile.role, Roles.MODERATOR)) {
            Toast.makeText(requireContext(), "Sin permisos.", Toast.LENGTH_LONG).show()
            parentFragmentManager.popBackStack()
            return
        }

        adapter = AdminGalleryAdapter(
            canDelete = Roles.isAtLeast(profile.role, Roles.ADMIN),
            onOpen = { navigateClean(GalleryViewerFragment.newInstance(it), addToBackStack = true) },
            onApprove = { approve(it) },
            onReject = { reject(it) },
            onDelete = { deleteItem(it) }
        )

        b.recyclerAdminGallery.layoutManager = LinearLayoutManager(requireContext())
        b.recyclerAdminGallery.adapter = adapter
        b.btnPending.text = pendingButtonTextForRole(profile.role)
        b.btnPending.setOnClickListener { switchTo(GalleryItem.STATUS_PENDING_MODERATOR) }
        b.btnApproved.setOnClickListener { switchTo(GalleryItem.STATUS_APPROVED) }
        b.btnRejected.setOnClickListener { switchTo(GalleryItem.STATUS_REJECTED) }
        b.swipeRefresh.setOnRefreshListener { subscribe(force = true) }

        switchTo(GalleryItem.STATUS_PENDING_MODERATOR)
    }

    private fun pendingButtonTextForRole(role: String?): String {
        return if (Roles.isAtLeast(role, Roles.ADMIN)) "Pendientes" else "Pendientes mod"
    }

    private fun switchTo(status: String) {
        currentStatus = status
        b.btnPending.isEnabled = status != GalleryItem.STATUS_PENDING_MODERATOR
        b.btnApproved.isEnabled = status != GalleryItem.STATUS_APPROVED
        b.btnRejected.isEnabled = status != GalleryItem.STATUS_REJECTED
        subscribe(force = true)
    }

    private fun subscribe(force: Boolean) {
        if (force) {
            regs.forEach { it.remove() }
            regs.clear()
        }
        b.swipeRefresh.isRefreshing = true

        if (currentStatus == GalleryItem.STATUS_PENDING_MODERATOR) {
            subscribePendingQueues()
            return
        }

        val reg = repository.listenByStatus(
            status = currentStatus,
            onResult = { list ->
                if (!isAdded || _b == null) return@listenByStatus
                b.swipeRefresh.isRefreshing = false
                adapter.submit(list)
                b.txtEmpty.isVisible = list.isEmpty()
            },
            onError = { e ->
                if (!isAdded || _b == null) return@listenByStatus
                b.swipeRefresh.isRefreshing = false
                Toast.makeText(requireContext(), e.localizedMessage ?: "No se pudo cargar la moderación.", Toast.LENGTH_LONG).show()
            }
        )
        regs += reg
    }

    private fun subscribePendingQueues() {
        val all = linkedMapOf<String, GalleryItem>()
        var finished = 0
        val total = if (Roles.isAtLeast(profile.role, Roles.ADMIN)) 2 else 1

        fun publishIfReady() {
            finished++
            if (finished < total) return
            if (!isAdded || _b == null) return
            val sorted = all.values.sortedByDescending { it.createdAt?.toDate()?.time ?: 0L }
            b.swipeRefresh.isRefreshing = false
            adapter.submit(sorted)
            b.txtEmpty.isVisible = sorted.isEmpty()
        }

        fun onError(e: Throwable) {
            if (!isAdded || _b == null) return
            b.swipeRefresh.isRefreshing = false
            Toast.makeText(requireContext(), e.localizedMessage ?: "No se pudo cargar la moderación.", Toast.LENGTH_LONG).show()
        }

        regs += repository.listenByStatus(
            status = GalleryItem.STATUS_PENDING_MODERATOR,
            onResult = { list ->
                list.forEach { all[it.id] = it }
                publishIfReady()
            },
            onError = ::onError
        )

        if (Roles.isAtLeast(profile.role, Roles.ADMIN)) {
            regs += repository.listenByStatus(
                status = GalleryItem.STATUS_PENDING_ADMIN,
                onResult = { list ->
                    list.forEach { all[it.id] = it }
                    publishIfReady()
                },
                onError = ::onError
            )
        }
    }

    private fun approve(item: GalleryItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                repository.approve(item, profile)
            }.onSuccess {
                val msg = when {
                    item.status == GalleryItem.STATUS_PENDING_MODERATOR && Roles.isAtLeast(profile.role, Roles.ADMIN) -> "Elemento aprobado y publicado."
                    item.status == GalleryItem.STATUS_PENDING_MODERATOR -> "Elemento escalado a aprobación de admin o superior."
                    else -> "Elemento aprobado."
                }
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(requireContext(), it.localizedMessage ?: "No se pudo aprobar.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun reject(item: GalleryItem) {
        val input = android.widget.EditText(requireContext()).apply { hint = "Motivo (opcional)" }
        AlertDialog.Builder(requireContext())
            .setTitle("Rechazar contenido")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Rechazar") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    runCatching {
                        repository.reject(item, profile.uid, listOf(profile.grado, profile.especialidad, profile.nombre).filter { it.isNotBlank() }.joinToString(" "), input.text?.toString().orEmpty())
                    }.onSuccess {
                        Toast.makeText(requireContext(), "Elemento rechazado.", Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(requireContext(), it.localizedMessage ?: "No se pudo rechazar.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    private fun deleteItem(item: GalleryItem) {
        if (!Roles.isAtLeast(profile.role, Roles.ADMIN)) {
            Toast.makeText(requireContext(), "Solo admin o developer pueden borrar.", Toast.LENGTH_LONG).show()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Borrar contenido")
            .setMessage("Se borrará la publicación y el archivo del storage. Esta acción no se puede deshacer. ¿Continuar?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Borrar") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    runCatching {
                        repository.delete(item)
                    }.onSuccess {
                        Toast.makeText(requireContext(), "Elemento borrado.", Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(requireContext(), it.localizedMessage ?: "No se pudo borrar.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        regs.forEach { it.remove() }
        regs.clear()
        _b = null
    }
}