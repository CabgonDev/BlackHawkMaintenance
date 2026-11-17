package com.cabgon.blackhawk.ui.admin

import android.annotation.SuppressLint
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentAdminHomeBinding.inflate(inflater, container, false)
        return b.root
    }

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val profile = UserSessionStore(requireContext()).getProfile()
        val role = Roles.normalize(profile?.role)
        val modules = buildModulesForRole(role)

        b.txtAdminRole.text = "Rol operativo: $role"
        b.txtAccessLevel.text = role
        b.txtModuleCount.text = modules.size.toString()
        b.txtAdminHint.text = if (modules.isEmpty()) {
            "No hay módulos disponibles para este rol."
        } else {
            "Solo se muestran módulos permitidos por rol. Toca una tarjeta para entrar."
        }

        val adapter = AdminModulesAdapter(modules) { module ->
            openModule(module)
        }

        b.recyclerModules.layoutManager = LinearLayoutManager(requireContext())
        b.recyclerModules.setHasFixedSize(false)
        b.recyclerModules.adapter = adapter
    }

    private fun buildModulesForRole(role: String): List<AdminModule> {
        if (!Roles.isAtLeast(role, Roles.MODERATOR)) return emptyList()

        val list = mutableListOf<AdminModule>()

        list += AdminModule(
            key = "users_admin",
            titleRes = R.string.admin_users_module,
            desc = "Altas, aprobaciones y control de roles operativos.",
            iconRes = R.drawable.ic_admin_panel,
            badge = "Usuarios",
            fragmentClassCandidates = listOf(
                "com.cabgon.blackhawk.ui.admin.users.UsersAdminFragment"
            )
        )

        list += AdminModule(
            key = "frequencies_admin",
            titleRes = R.string.admin_frequencies_module,
            desc = "Draft, publicación OTA, bloqueo operativo, auditoría y rollback.",
            iconRes = R.drawable.ic_radiofreq,
            badge = "OTA",
            fragmentClassCandidates = listOf(
                "com.cabgon.blackhawk.admin.frequencies.AdminFrequenciesFragment"
            )
        )

        list += AdminModule(
            key = "generalities_admin",
            titleRes = R.string.admin_generalities_module,
            desc = "Secciones y tablas técnicas con edición controlada por rol.",
            iconRes = R.drawable.ic_generalities,
            badge = "Contenido",
            fragmentClassCandidates = listOf(
                "com.cabgon.blackhawk.admin.generalities.AdminGeneralitiesDraftFragment"
            )
        )

        list += AdminModule(
            key = "enruta_admin",
            titleRes = R.string.admin_enruta_module,
            desc = "Registros Firestore usados por la sección En Ruta.",
            iconRes = R.drawable.ic_ruta,
            badge = "Operación",
            fragmentClassCandidates = listOf(
                "com.cabgon.blackhawk.ui.admin.enruta.AdminEnRutaFragment"
            )
        )

        list += AdminModule(
            key = "gallery_admin",
            titleRes = R.string.admin_gallery_module,
            desc = "Aprobar o rechazar material multimedia enviado por usuarios.",
            iconRes = R.drawable.ic_gallery_image,
            badge = "Media",
            fragmentClassCandidates = listOf(
                "com.cabgon.blackhawk.ui.admin.gallery.AdminGalleryFragment"
            )
        )

        list += AdminModule(
            key = "audit_admin",
            titleRes = R.string.admin_audit_module,
            desc = "Trazabilidad de publicaciones y acciones administrativas.",
            iconRes = R.drawable.ic_toc,
            badge = "Auditoría",
            fragmentClassCandidates = listOf(
                "com.cabgon.blackhawk.admin.frequencies.AdminAuditLogFragment"
            )
        )

        return list
    }

    private fun openModule(module: AdminModule) {
        val f = instantiateFirstAvailableFragment(module.fragmentClassCandidates)
        if (f == null) {
            Toast.makeText(
                requireContext(),
                "Módulo no disponible en el build actual.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        navigateRoot(f)
    }

    private fun instantiateFirstAvailableFragment(candidates: List<String>): Fragment? {
        for (name in candidates) {
            try {
                val clazz = Class.forName(name).asSubclass(Fragment::class.java)
                val ctor = clazz.getDeclaredConstructor()
                ctor.isAccessible = true
                return ctor.newInstance()
            } catch (_: Throwable) {
                // Intentamos con el siguiente candidato
            }
        }
        return null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
