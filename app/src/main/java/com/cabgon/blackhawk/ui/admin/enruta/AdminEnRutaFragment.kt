package com.cabgon.blackhawk.ui.admin.enruta

import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cabgon.blackhawk.data.user.UserSessionStore
import com.cabgon.blackhawk.databinding.FragmentAdminEnrutaBinding
import com.cabgon.blackhawk.ui.enruta.EnRutaDetailFragment
import com.cabgon.blackhawk.util.Roles
import com.cabgon.blackhawk.util.navigateClean
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import java.util.Date


class AdminEnRutaFragment : Fragment() {

    private var _b: FragmentAdminEnrutaBinding? = null
    private val b get() = _b!!

    private lateinit var repo: AdminEnRutaRepository
    private lateinit var adapter: AdminEnRutaAdapter

    private var role: String = Roles.USER
    private var canCreateDelete: Boolean = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentAdminEnrutaBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        role = Roles.normalize(UserSessionStore(requireContext()).getProfile()?.role)
        canCreateDelete = Roles.isAtLeast(role, Roles.MODERATOR)

        b.txtRole.text = "Rol: $role"
        b.btnCreate.isVisible = canCreateDelete

        repo = AdminEnRutaRepository()
        adapter = AdminEnRutaAdapter(
            canDelete = canCreateDelete,
            onOpen = { mat ->
                navigateClean(EnRutaDetailFragment.newInstance(mat), addToBackStack = true, backStackName = "enruta_detail_$mat")
            },
            onDelete = { item ->
                confirmDelete(item.matAeronave)
            }
        )

        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.recycler.adapter = adapter

        b.btnCreate.setOnClickListener { showCreateDialog() }

        b.progress.isVisible = true
        b.txtStatus.text = "Cargando…"

        repo.observe { list ->
            b.progress.isVisible = false
            b.txtStatus.text = "Registros: ${list.size}"
            adapter.submit(list)
        }
    }

    private fun showCreateDialog() {
        if (!canCreateDelete) {
            Toast.makeText(requireContext(), "No tienes permisos para crear.", Toast.LENGTH_SHORT).show()
            return
        }

        val input = EditText(requireContext()).apply {
            hint = "Matrícula (docId), ej: 1101"
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Crear En Ruta")
            .setMessage("Se creará el documento en Firestore en_ruta/{mat} con valores por defecto.\nAparecerá en la lista En Ruta al sincronizar.")
            .setView(input)
            .setPositiveButton("Crear") { _, _ ->
                val mat = input.text.toString().trim()
                if (mat.isBlank()) {
                    Toast.makeText(requireContext(), "Matrícula requerida.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val uid = FirebaseAuth.getInstance().currentUser?.uid ?: "unknown"
                val now = System.currentTimeMillis()
                val lastEditDate = DateFormat.format("dd/MM/yyyy HH:mm", Date(now)).toString()

                lifecycleScope.launch {
                    b.progress.isVisible = true
                    val ok = repo.createDefault(mat, uid, now, lastEditDate)
                    b.progress.isVisible = false
                    if (ok) Toast.makeText(requireContext(), "Creado: $mat", Toast.LENGTH_LONG).show()
                    else Toast.makeText(requireContext(), "No se pudo crear (revisa rules).", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmDelete(mat: String) {
        if (!canCreateDelete) return

        AlertDialog.Builder(requireContext())
            .setTitle("Borrar En Ruta")
            .setMessage("Esto borrará el documento en_ruta/$mat y su subcolección recargas.\n¿Continuar?")
            .setPositiveButton("Borrar") { _, _ ->
                lifecycleScope.launch {
                    b.progress.isVisible = true
                    val ok = repo.delete(mat)
                    b.progress.isVisible = false
                    if (ok) Toast.makeText(requireContext(), "Borrado: $mat", Toast.LENGTH_LONG).show()
                    else Toast.makeText(requireContext(), "No se pudo borrar (revisa rules).", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        repo.close()
        _b = null
    }
}
