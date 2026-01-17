package com.cabgon.blackhawk.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cabgon.blackhawk.data.inspection40h.Inspection40hRepository
import com.cabgon.blackhawk.databinding.FragmentInspection40hListBinding
import com.cabgon.blackhawk.ui.adapters.Inspection40hListAdapter
import com.cabgon.blackhawk.ui.inspection40h.Inspection40hChecklistFragment
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = Inspection40hListAdapter(
            onClick = { id ->
                // Abrir checklist 40H en el MISMO contenedor que usa Preflight
                parentFragmentManager.beginTransaction()
                    .replace(
                        (requireView().parent as ViewGroup).id,
                        Inspection40hChecklistFragment.newInstance(id)
                    )
                    .addToBackStack(null)
                    .commit()
            },
            onDelete = { id ->
                AlertDialog.Builder(requireContext())
                    .setTitle("Eliminar inspección 40H")
                    .setMessage("¿Seguro que deseas eliminar esta inspección? Esta acción no se puede deshacer.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Eliminar") { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            repo.deleteInspection(id)
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
            }
        }

        // FAB → crea inspección 40H y navega directo al checklist
        b.fab.setOnClickListener {
            CreateInspection40hDialogFragment.newInstance { createdAt,
                                                            matAeronave,
                                                            hsTotales,
                                                            supervisorGrade,
                                                            supervisorSpecialty,
                                                            supervisorFullName,
                                                            supervisorMatricula ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val id = repo.createInspection40h(
                        fechaEpochMillis = createdAt,
                        matAeronave = matAeronave,
                        hsTotales = hsTotales,
                        supervisorGrade = supervisorGrade,
                        supervisorSpecialty = supervisorSpecialty,
                        supervisorFullName = supervisorFullName,
                        supervisorMatricula = supervisorMatricula
                    )

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

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
