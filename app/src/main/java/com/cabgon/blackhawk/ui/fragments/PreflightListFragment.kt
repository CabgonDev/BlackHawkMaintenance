package com.cabgon.blackhawk.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cabgon.blackhawk.data.preflight.ChecklistStore
import com.cabgon.blackhawk.data.preflight.PreflightRemoteSync
import com.cabgon.blackhawk.data.preflight.PreflightRepository
import com.cabgon.blackhawk.data.user.UserSessionStore
import com.cabgon.blackhawk.databinding.FragmentPreflightListBinding
import com.cabgon.blackhawk.ui.fragments.PreflightChecklistFragment.Companion.newInstance
import com.cabgon.blackhawk.ui.preflight.PreflightAdapter
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PreflightListFragment : Fragment() {

    private var _b: FragmentPreflightListBinding? = null
    private val b get() = _b!!

    private lateinit var repo: PreflightRepository
    private lateinit var adapter: PreflightAdapter
    private lateinit var remoteSync: PreflightRemoteSync

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentPreflightListBinding.inflate(inflater, container, false)
        repo = PreflightRepository(requireContext())
        remoteSync = PreflightRemoteSync(
            context = requireContext(),
            firestore = FirebaseFirestore.getInstance()
        )
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = PreflightAdapter(
            onClick = { id ->
                parentFragmentManager.beginTransaction()
                    .replace((requireView().parent as ViewGroup).id, newInstance(id))
                    .addToBackStack(null)
                    .commit()
            },
            onDelete = { id ->
                AlertDialog.Builder(requireContext())
                    .setTitle("Eliminar inspección")
                    .setMessage("¿Seguro que deseas eliminar esta inspección? Esta acción no se puede deshacer.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Eliminar") { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            remoteSync.deleteRemoteByLocalId(id)
                            repo.delete(id)
                        }
                    }
                    .show()
            }
        )

        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.recycler.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            repo.observe().collectLatest { rows ->
                adapter.submit(rows)
                renderDashboard(rows)
            }
        }

        b.fab.setOnClickListener { showCreateDialog() }
        b.btnCreateFirst.setOnClickListener { showCreateDialog() }
    }

    override fun onResume() {
        super.onResume()

        val profile = UserSessionStore(requireContext()).getProfile()
        val uid = profile?.uid ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            remoteSync.pullFromRemote(uid)
            remoteSync.syncDirtyInspections(uid)
        }
    }

    private fun renderDashboard(rows: List<PreflightRepository.InspectionWithItems>) {
        val total = rows.size
        val completed = rows.count { inspection ->
            inspection.items.isNotEmpty() && inspection.items.all { it.checked }
        }
        val inProgress = rows.count { inspection ->
            val done = inspection.items.count { it.checked }
            done in 1 until inspection.items.size
        }

        b.tvTotalCount.text = total.toString()
        b.tvCompleteCount.text = completed.toString()
        b.tvProcessCount.text = inProgress.toString()

        val hasItems = total > 0
        b.emptyState.isVisible = !hasItems
        b.recycler.isVisible = hasItems
    }

    private fun showCreateDialog() {
        com.cabgon.blackhawk.ui.preflight.PreflightNewDialog { fechaMillis, hora24, matAeronave, grado, esp, nombre, hsTot, hsDisp, matTec ->
            viewLifecycleOwner.lifecycleScope.launch {
                val checklist = ChecklistStore.loadPreflight(requireContext())
                val templateTitles = ChecklistStore.flattenTitles(checklist)

                val id = repo.createInspection(
                    fechaMillis = fechaMillis,
                    hora24 = hora24,
                    matAeronave = matAeronave,
                    tecnicoGrado = grado,
                    tecnicoEspecialidad = esp,
                    tecnicoNombre = nombre,
                    hsTotales = hsTot,
                    hsDisponibles = hsDisp,
                    tecnicoMatricula = matTec,
                    templateTitles = templateTitles
                )

                parentFragmentManager.beginTransaction()
                    .replace((requireView().parent as ViewGroup).id, newInstance(id))
                    .addToBackStack(null)
                    .commit()
            }
        }.show(parentFragmentManager, "new_preflight")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
