package com.cabgon.blackhawk.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
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
                            // 1) Intentar borrar en remoto (si tenía syncId)
                            remoteSync.deleteRemoteByLocalId(id)
                            // 2) Borrar en local
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
            }
        }

        b.fab.setOnClickListener { showCreateDialog() }
    }

    override fun onResume() {
        super.onResume()

        // Sincroniza con Firestore para el usuario actual:
        // primero baja inspecciones remotas, luego sube las locales "dirty".
        val profile = UserSessionStore(requireContext()).getProfile()
        val uid = profile?.uid ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            remoteSync.pullFromRemote(uid)
            remoteSync.syncDirtyInspections(uid)
        }
    }

    private fun showCreateDialog() {
        com.cabgon.blackhawk.ui.preflight.PreflightNewDialog { fechaMillis, hora24, matAeronave, grado, esp, nombre, hsTot, hsDisp, matTec ->
            viewLifecycleOwner.lifecycleScope.launch {
                val checklist = ChecklistStore.loadPreflight(requireContext())
                val templateTitles = checklist.sections
                    .flatMap { it.items }
                    .map { it.title }

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
