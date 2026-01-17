package com.cabgon.blackhawk.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.databinding.FragmentInspectionsHomeBinding

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

        // Por ahora solo mostramos toasts. Luego aquí navegaremos
        // a cada lista de inspecciones (40h, 80h, 120h, 480h).

        b.card40h.setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, Inspection40hListFragment())
                .addToBackStack(null)
                .commit()
        }

        b.card80h.setOnClickListener {
            Toast.makeText(requireContext(), "Abrir lista de inspecciones 80 h", Toast.LENGTH_SHORT).show()
        }

        b.card120h.setOnClickListener {
            Toast.makeText(requireContext(), "Abrir lista de inspecciones 120 h", Toast.LENGTH_SHORT).show()
        }

        b.card480h.setOnClickListener {
            Toast.makeText(requireContext(), "Abrir lista de inspecciones 480 h", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
