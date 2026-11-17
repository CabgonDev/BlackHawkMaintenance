package com.cabgon.blackhawk.admin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.cabgon.blackhawk.admin.frequencies.AdminFrequenciesFragment
import com.cabgon.blackhawk.databinding.FragmentAdminPanelBinding
import com.cabgon.blackhawk.util.navigateRoot

class AdminPanelFragment : Fragment() {

    private var _b: FragmentAdminPanelBinding? = null
    private val b get() = _b!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentAdminPanelBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.cardFrequencies.setOnClickListener {
            // ✅ Frecuencias correcto (NO generalidades)
            navigateRoot(AdminFrequenciesFragment())
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
