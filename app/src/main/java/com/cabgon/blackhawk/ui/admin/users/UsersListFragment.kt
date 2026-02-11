package com.cabgon.blackhawk.ui.admin.users

import android.app.Dialog
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.data.user.UserSessionStore
import com.cabgon.blackhawk.databinding.FragmentUsersListBinding
import com.cabgon.blackhawk.util.Roles
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query

class UsersListFragment : Fragment() {

    enum class Mode { REGISTRADOS, DEVELOPERS, ADMINISTRADORES, MODERADORES, SOLICITUDES }

    private var _b: FragmentUsersListBinding? = null
    private val b get() = _b!!

    private val db by lazy { FirebaseFirestore.getInstance() }
    private var reg: ListenerRegistration? = null

    private lateinit var adapter: UsersAdapter
    private lateinit var mode: Mode

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = Mode.valueOf(requireArguments().getString(ARG_MODE)!!)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentUsersListBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = UsersAdapter(
            mode = mode,
            onUserClick = { u -> onUserClicked(u) },
            onApprove = { u -> setStatus(u.uid, "approved") },
            onReject = { u -> setStatus(u.uid, "rejected") }
        )

        b.recyclerUsers.layoutManager = LinearLayoutManager(requireContext())
        b.recyclerUsers.adapter = adapter

        listenUsers()
    }

    private fun listenUsers() {
        reg?.remove()

        var q: Query = db.collection("users")

        q = when (mode) {
            Mode.SOLICITUDES ->
                q.whereEqualTo("status", "pending")

            Mode.REGISTRADOS ->
                q.whereEqualTo("status", "approved")
                    .whereEqualTo("role", "user")

            Mode.MODERADORES ->
                q.whereEqualTo("status", "approved")
                    .whereEqualTo("role", "moderator")

            Mode.ADMINISTRADORES ->
                q.whereEqualTo("status", "approved")
                    .whereEqualTo("role", "admin")

            Mode.DEVELOPERS ->
                q.whereEqualTo("status", "approved")
                    .whereEqualTo("role", "developer")
        }

        // ✅ SIN orderBy Firestore => sin índices compuestos
        reg = q.addSnapshotListener { snap, e ->
            if (e != null) {
                Toast.makeText(requireContext(), "Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                return@addSnapshotListener
            }

            val list = snap?.documents?.map { d ->
                AdminUserItem(
                    uid = d.getString("uid") ?: d.id,
                    nombre = d.getString("nombre") ?: "(sin nombre)",
                    email = d.getString("email") ?: "",
                    role = d.getString("role") ?: "user",
                    status = d.getString("status") ?: "approved"
                )
            }.orEmpty()
                .sortedBy { it.nombre.lowercase() } // ✅ orden local

            adapter.submitList(list)
            b.txtEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun onUserClicked(u: AdminUserItem) {
        if (mode == Mode.SOLICITUDES) return

        val myRole = Roles.normalize(UserSessionStore(requireContext()).getProfile()?.role)
        if (!Roles.isAtLeast(myRole, Roles.ADMIN)) {
            Toast.makeText(requireContext(), "Sin permisos.", Toast.LENGTH_SHORT).show()
            return
        }

        showRolePickerDialog(u)
    }

    /**
     * Dialog 100% independiente del tema:
     * - Layout propio con RadioButtons + botones internos.
     * - Colores forzados (texto/botones) para que SIEMPRE se vea.
     */
    private fun showRolePickerDialog(u: AdminUserItem) {
        val roleLabels = listOf("Usuario", "Moderador", "Administrador", "Developer")
        val roleValues = listOf("user", "moderator", "admin", "developer")

        var selectedIndex = roleValues.indexOf(u.role).coerceAtLeast(0)

        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

        val bg = if (isNight) Color.parseColor("#1E1E1E") else Color.WHITE
        val fg = if (isNight) Color.WHITE else Color.BLACK
        val btnAccent = if (isNight) Color.parseColor("#90CAF9") else Color.parseColor("#1565C0")
        val btnDanger = if (isNight) Color.parseColor("#FF8A80") else Color.parseColor("#C62828")

        val v = layoutInflater.inflate(R.layout.dialog_role_picker, null, false)

        val tvTitle = v.findViewById<TextView>(R.id.tvRoleTitle)
        val tvMsg = v.findViewById<TextView>(R.id.tvRoleMsg)
        val rg = v.findViewById<RadioGroup>(R.id.rgRoles)

        val btnApply = v.findViewById<TextView>(R.id.btnApply)
        val btnCancel = v.findViewById<TextView>(R.id.btnCancel)
        val btnDelete = v.findViewById<TextView>(R.id.btnDelete)

        // Fondo general
        v.setBackgroundColor(bg)

        // Textos
        tvTitle.text = u.nombre
        tvTitle.setTextColor(fg)
        tvMsg.setTextColor(fg)

        // Botones (son TextViews estilo botón, con color forzado)
        btnApply.setTextColor(btnAccent)
        btnCancel.setTextColor(btnAccent)
        btnDelete.setTextColor(btnDanger)

        // Crear radios a mano (con colores forzados)
        rg.removeAllViews()
        roleLabels.forEachIndexed { idx, label ->
            val rb = RadioButton(requireContext())
            rb.text = label
            rb.setTextColor(fg)
            rb.id = View.generateViewId()
            rb.isChecked = idx == selectedIndex
            rb.setPadding(6, 18, 6, 18)
            rg.addView(rb)
        }

        rg.setOnCheckedChangeListener { group, checkedId ->
            val index = (0 until group.childCount).indexOfFirst { group.getChildAt(it).id == checkedId }
            if (index >= 0) selectedIndex = index
        }

        val dialog: Dialog = AlertDialog.Builder(requireContext())
            .setView(v)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnDelete.setOnClickListener {
            dialog.dismiss()
            deleteUserDoc(u.uid)
        }

        btnApply.setOnClickListener {
            val newRole = roleValues.getOrNull(selectedIndex) ?: return@setOnClickListener
            dialog.dismiss()
            setRole(u.uid, newRole)
        }

        dialog.show()
    }

    private fun setStatus(uid: String, status: String) {
        db.collection("users").document(uid)
            .update(
                mapOf(
                    "status" to status,
                    "reviewedAt" to FieldValue.serverTimestamp(),
                    "reviewedBy" to (FirebaseAuth.getInstance().currentUser?.uid ?: "")
                )
            )
            .addOnSuccessListener {
                Toast.makeText(requireContext(), "Actualizado: $status", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(requireContext(), "Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
    }

    private fun setRole(uid: String, role: String) {
        db.collection("users").document(uid)
            .update("role", role)
            .addOnSuccessListener {
                Toast.makeText(requireContext(), "Rol actualizado: $role", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(requireContext(), "Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
    }

    private fun deleteUserDoc(uid: String) {
        db.collection("users").document(uid)
            .delete()
            .addOnSuccessListener {
                Toast.makeText(requireContext(), "Documento eliminado.", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(requireContext(), "Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        reg?.remove()
        reg = null
        _b = null
    }

    companion object {
        private const val ARG_MODE = "mode"
        fun newInstance(mode: Mode) = UsersListFragment().apply {
            arguments = Bundle().apply { putString(ARG_MODE, mode.name) }
        }
    }
}
