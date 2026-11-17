package com.cabgon.blackhawk.ui.admin.users

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.cabgon.blackhawk.databinding.FragmentUsersAdminBinding
import com.google.android.material.tabs.TabLayoutMediator

class UsersAdminFragment : Fragment() {

    private var _b: FragmentUsersAdminBinding? = null
    private val b get() = _b!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentUsersAdminBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 5
            override fun createFragment(position: Int): Fragment {
                return UsersListFragment.newInstance(
                    when (position) {
                        0 -> UsersListFragment.Mode.REGISTRADOS
                        1 -> UsersListFragment.Mode.DEVELOPERS
                        2 -> UsersListFragment.Mode.ADMINISTRADORES
                        3 -> UsersListFragment.Mode.MODERADORES
                        else -> UsersListFragment.Mode.SOLICITUDES
                    }
                )
            }
        }

        TabLayoutMediator(b.tabs, b.pager) { tab, pos ->
            tab.text = when (pos) {
                0 -> "Registrados"
                1 -> "Developers"
                2 -> "Administradores"
                3 -> "Moderadores"
                else -> "Solicitudes"
            }
        }.attach()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
