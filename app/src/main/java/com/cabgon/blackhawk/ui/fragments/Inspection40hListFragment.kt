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
import com.cabgon.blackhawk.data.inspection40h.Inspection40hRepository
import com.cabgon.blackhawk.databinding.FragmentInspection40hListBinding
import com.cabgon.blackhawk.ui.adapters.Inspection40hListAdapter
import com.cabgon.blackhawk.ui.inspection40h.Inspection40hChecklistFragment
import com.cabgon.blackhawk.util.NetworkGate
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class Inspection40hListFragment : Fragment() {

    private var _b: FragmentInspection40hListBinding? = null
    private val b get() = _b!!

    private lateinit var repo: Inspection40hRepository
    private lateinit var adapter: Inspection40hListAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentInspection40hListBinding.inflate(inflater, container, false)
        repo = Inspection40hRepository(requireContext())
        return b.root
    }

    override fun onResume() {
        super.onResume()
        ensureOnlineListAccess()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!ensureOnlineListAccess()) return

        adapter = Inspection40hListAdapter(
            onClick = { id ->
                if (!NetworkGate.hasValidatedConnection(requireContext())) {
                    Toast.makeText(requireContext(), "Sin conexión. No se puede abrir el checklist.", Toast.LENGTH_SHORT).show()
                } else {
                    parentFragmentManager.beginTransaction()
                        .replace(
                            (requireView().parent as ViewGroup).id,
                            Inspection40hChecklistFragment.newInstance(id)
                        )
                        .addToBackStack(null)
                        .commit()
                }
            },
            onDelete = { id ->
                AlertDialog.Builder(requireContext())
                    .setTitle("Eliminar inspección 40H")
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

        // FAB → crea inspección 40H y navega directo al checklist
        b.fab.setOnClickListener {
            if (!NetworkGate.hasValidatedConnection(requireContext())) {
                Toast.makeText(requireContext(), "Sin conexión. No se puede crear una inspección nueva.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            CreateInspection40hDialogFragment.newInstance { createdAt,
                                                            matAeronave,
                                                            hsTotales,
                                                            supervisorGrade,
                                                            supervisorSpecialty,
                                                            supervisorFullName,
                                                            supervisorMatricula ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val id = runCatching { repo.createInspection40h(
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
                            Inspection40hChecklistFragment.newInstance(id)
                        )
                        .addToBackStack(null)
                        .commit()
                }
            }.show(parentFragmentManager, "create_inspection_40h")
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
