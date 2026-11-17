package com.cabgon.blackhawk.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.databinding.FragmentInspectionsHomeBinding
import com.cabgon.blackhawk.util.NetworkGate

class InspectionsHomeFragment : Fragment() {

    private var _b: FragmentInspectionsHomeBinding? = null
    private val b get() = _b!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentInspectionsHomeBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        b.card40h.setOnClickListener {
            if (!NetworkGate.hasValidatedConnection(requireContext())) {
                Toast.makeText(requireContext(), "Necesitas conexión a internet para acceder a inspecciones.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, Inspection40hListFragment())
                .addToBackStack(null)
                .commit()
        }

        //b.card80h.setOnClickListener {
        //    Toast.makeText(requireContext(), "Abrir lista de inspecciones 80 h", Toast.LENGTH_SHORT).show()
        //}

        b.card120h.setOnClickListener {
            if (!NetworkGate.hasValidatedConnection(requireContext())) {
                Toast.makeText(requireContext(), "Necesitas conexión a internet para acceder a inspecciones.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, Inspection120hListFragment())
                .addToBackStack(null)
                .commit()
        }

        b.card480h.setOnClickListener {
            if (!NetworkGate.hasValidatedConnection(requireContext())) {
                Toast.makeText(requireContext(), "Necesitas conexión a internet para acceder a inspecciones.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, Inspection480hListFragment())
                .addToBackStack(null)
                .commit()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
