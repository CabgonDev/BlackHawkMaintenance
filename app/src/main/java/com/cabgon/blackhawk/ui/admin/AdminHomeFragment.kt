package com.cabgon.blackhawk.ui.admin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.data.user.UserSessionStore
import com.cabgon.blackhawk.databinding.FragmentAdminHomeBinding
import com.cabgon.blackhawk.util.Roles
import com.cabgon.blackhawk.util.navigateRoot

class AdminHomeFragment : Fragment() {

    private var _b: FragmentAdminHomeBinding? = null
    private val b get() = _b!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentAdminHomeBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val profile = UserSessionStore(requireContext()).getProfile()
        val role = Roles.normalize(profile?.role)

        b.txtAdminRole.text = "Rol: $role"

        val modules = buildModulesForRole(role)

        val adapter = AdminModulesAdapter(modules) { module ->
            openModule(module)
        }

        b.recyclerModules.layoutManager = LinearLayoutManager(requireContext())
        b.recyclerModules.adapter = adapter
    }

    private fun buildModulesForRole(role: String): List<AdminModule> {
        if (!Roles.isAtLeast(role, Roles.MODERATOR)) return emptyList()

        val list = mutableListOf<AdminModule>()

        if (Roles.normalize(role) == Roles.DEVELOPER) {
            list += AdminModule(
                key = "ai_status_admin",
                titleRes = R.string.admin_ai_status_module,
                desc = "Diagnóstico IA/OTA (solo Developer). Abre la pantalla de Estado IA.",
                fragmentClassCandidates = listOf(
                    "com.cabgon.blackhawk.ui.admin.AiStatusLauncherFragment"
                )
            )
        }

        list += AdminModule(
            key = "frequencies_admin",
            titleRes = R.string.admin_frequencies_module,
            desc = "CRUD Draft + Publicar OTA + Lock + Audit + Rollback",
            fragmentClassCandidates = listOf(
                "com.cabgon.blackhawk.admin.frequencies.AdminFrequenciesFragment"
            )
        )

        list += AdminModule(
            key = "generalities_admin",
            titleRes = R.string.admin_generalities_module,
            desc = "CRUD Draft de Generalidades (Secciones → Tablas). Solo Developer edita; Admin/Moderator solo lectura.",
            fragmentClassCandidates = listOf(
                "com.cabgon.blackhawk.admin.generalities.AdminGeneralitiesDraftFragment"
            )
        )

        list += AdminModule(
            key = "enruta_admin",
            titleRes = R.string.admin_enruta_module,
            desc = "Ver/crear/borrar registros Firestore en_ruta (aparecen en En Ruta)",
            fragmentClassCandidates = listOf(
                "com.cabgon.blackhawk.ui.admin.enruta.AdminEnRutaFragment"
            )
        )

        list += AdminModule(
            key = "audit_admin",
            titleRes = R.string.admin_audit_module,
            desc = "Ver auditoría de publicaciones y acciones del panel",
            fragmentClassCandidates = listOf(
                "com.cabgon.blackhawk.admin.frequencies.AdminAuditLogFragment"
            )
        )

        return list
    }

    private fun openModule(module: AdminModule) {
        val f = instantiateFirstAvailableFragment(module.fragmentClassCandidates)
        if (f == null) {
            Toast.makeText(requireContext(), "Módulo no disponible en el build actual.", Toast.LENGTH_LONG).show()
            return
        }

        // ✅ Fix 3 correcto: navega al fragment seleccionado (NO hardcode)
        navigateRoot(f)
    }

    private fun instantiateFirstAvailableFragment(candidates: List<String>): Fragment? {
        for (name in candidates) {
            try {
                val clazz = Class.forName(name)
                val inst = clazz.newInstance()
                if (inst is Fragment) return inst
            } catch (_: Throwable) {
                // try next
            }
        }
        return null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
