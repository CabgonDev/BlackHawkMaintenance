package com.cabgon.blackhawk.ui.fragments

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
import com.cabgon.blackhawk.data.inspection120h.Inspection120hRepository
import com.cabgon.blackhawk.databinding.FragmentInspection120hListBinding
import com.cabgon.blackhawk.ui.adapters.Inspection120hListAdapter
import com.cabgon.blackhawk.ui.inspection120h.Inspection120hChecklistFragment
import com.cabgon.blackhawk.util.NetworkGate
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class Inspection120hListFragment : Fragment() {

    private var _b: FragmentInspection120hListBinding? = null
    private val b get() = _b!!

    private lateinit var repo: Inspection120hRepository
    private lateinit var adapter: Inspection120hListAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentInspection120hListBinding.inflate(inflater, container, false)
        repo = Inspection120hRepository(requireContext())
        return b.root
    }

    override fun onResume() {
        super.onResume()
        ensureOnlineListAccess()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!ensureOnlineListAccess()) return

        adapter = Inspection120hListAdapter(
            onClick = { id ->
                if (!NetworkGate.hasValidatedConnection(requireContext())) {
                    Toast.makeText(requireContext(), "Sin conexión. No se puede abrir el checklist.", Toast.LENGTH_SHORT).show()
                } else {
                    parentFragmentManager.beginTransaction()
                        .replace(
                            (requireView().parent as ViewGroup).id,
                            Inspection120hChecklistFragment.newInstance(id)
                        )
                        .addToBackStack(null)
                        .commit()
                }
            },
            onDelete = { id ->
                AlertDialog.Builder(requireContext())
                    .setTitle("Eliminar inspección 120H")
                    .setMessage("¿Seguro que deseas eliminar esta inspección? Esta acción no se puede deshacer.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Eliminar") { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            if (!NetworkGate.hasValidatedConnection(requireContext())) {
                                Toast.makeText(requireContext(), "Sin conexión. No se puede eliminar en este momento.", Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            runCatching { repo.deleteInspection(id) }
                                .onFailure {
                                    Toast.makeText(requireContext(), it.message ?: "No se pudo eliminar la inspección.", Toast.LENGTH_SHORT).show()
                                }
                        }
                    }
                    .show()
            }
        )

        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.recycler.adapter = adapter

        // Observar inspecciones con items para calcular % (igual que Preflight)
        viewLifecycleOwner.lifecycleScope.launch {
            repo.observeWithItems().collectLatest { rows ->
                adapter.submit(rows)

                val completed = rows.count { row ->
                    row.header.state == "closed"
                }
                val inProgress = rows.count { row ->
                    row.header.state != "closed"
                }

                b.tvMetricTotal.text = rows.size.toString()
                b.tvMetricOpen.text = inProgress.toString()
                b.tvMetricDone.text = completed.toString()
                b.cardEmpty.isVisible = rows.isEmpty()
                b.recycler.isVisible = rows.isNotEmpty()
            }
        }

        // FAB → crea inspección 120H y navega directo al checklist
        b.fab.setOnClickListener {
            if (!NetworkGate.hasValidatedConnection(requireContext())) {
                Toast.makeText(requireContext(), "Sin conexión. No se puede crear una inspección nueva.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            CreateInspection120hDialogFragment.newInstance { createdAt,
                                                             matAeronave,
                                                             hsTotales,
                                                             supervisorGrade,
                                                             supervisorSpecialty,
                                                             supervisorFullName,
                                                             supervisorMatricula ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val id = runCatching { repo.createInspection120h(
                        fechaEpochMillis = createdAt,
                        matAeronave = matAeronave,
                        hsTotales = hsTotales,
                        supervisorGrade = supervisorGrade,
                        supervisorSpecialty = supervisorSpecialty,
                        supervisorFullName = supervisorFullName,
                        supervisorMatricula = supervisorMatricula
                    ) }.getOrElse {
                        Toast.makeText(requireContext(), it.message ?: "No se pudo crear la inspección.", Toast.LENGTH_SHORT).show()
                        return@launch
                    }

                    parentFragmentManager.beginTransaction()
                        .replace(
                            (requireView().parent as ViewGroup).id,
                            Inspection120hChecklistFragment.newInstance(id)
                        )
                        .addToBackStack(null)
                        .commit()
                }
            }.show(parentFragmentManager, "create_inspection_120h")
        }
    }

    private fun ensureOnlineListAccess(): Boolean {
        if (!NetworkGate.hasValidatedConnection(requireContext())) {
            Toast.makeText(requireContext(), "Necesitas conexión a internet para acceder a inspecciones.", Toast.LENGTH_SHORT).show()
            parentFragmentManager.popBackStack()
            return false
        }
        return true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
