package com.cabgon.blackhawk.ui.enruta

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.data.db.AppDbProvider
import com.cabgon.blackhawk.data.enruta.EnRutaRepository
import com.cabgon.blackhawk.data.user.UserSessionStore
import com.cabgon.blackhawk.databinding.FragmentEnRutaListBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class EnRutaListFragment : Fragment() {

    private var _binding: FragmentEnRutaListBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: EnRutaListAdapter

    // Matrículas fijas de la FAM para En Ruta
    private val matriculasFijas = arrayOf("1091", "1092", "1093", "1094", "1097", "1098")

    private val viewModel: EnRutaViewModel by viewModels {
        EnRutaViewModelFactory(
            repo = EnRutaRepository(
                dao = AppDbProvider.get(requireContext()).enRutaDao(),
                firestore = FirebaseFirestore.getInstance()
            ),
            currentUserIdProvider = {
                // Tomamos el UID del perfil guardado (si existe)
                UserSessionStore(requireContext()).getProfile()?.uid
            }
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEnRutaListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        adapter = EnRutaListAdapter(
            items = emptyList(),
            onClick = { item ->
                openDetalle(item.matAeronave)
            },
            onRemoveClick = { item ->
                confirmRemoveFromRuta(item)
            }
        )

        binding.recyclerEnRuta.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerEnRuta.adapter = adapter
        binding.fabAgregarEnRuta.visibility = View.GONE

        binding.fabAgregarEnRuta.setOnClickListener {
            showAgregarDialog()
        }

        // Observar la lista desde el ViewModel
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.enRutaListUi.collectLatest { list ->
                adapter.submitList(list)
            }
        }
    }

    private fun confirmRemoveFromRuta(item: EnRutaViewModel.EnRutaListItemUi) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Quitar de En Ruta")
            .setMessage("¿Deseas quitar la aeronave UH-60L Mat. ${item.matAeronave} de En Ruta?")
            .setPositiveButton("Quitar") { dialog, _ ->
                viewModel.quitarDeRuta(item.matAeronave)
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showAgregarDialog() {
        var selectedIndex = 0

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Agregar aeronave a En Ruta")
            .setSingleChoiceItems(matriculasFijas, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton("Aceptar") { dialog, _ ->
                val mat = matriculasFijas[selectedIndex]
                viewModel.agregarARuta(mat)
                openDetalle(mat)
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun openDetalle(matAeronave: String) {
        val fragment = EnRutaDetailFragment.newInstance(matAeronave)
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .addToBackStack(null)
            .commit()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
