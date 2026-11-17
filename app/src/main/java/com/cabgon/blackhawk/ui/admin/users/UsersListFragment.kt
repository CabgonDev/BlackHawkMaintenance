package com.cabgon.blackhawk.ui.admin.users

import android.app.Dialog
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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
import com.cabgon.blackhawk.util.LogoutBus
import com.cabgon.blackhawk.util.Roles
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class UsersListFragment : Fragment() {

    enum class Mode { REGISTRADOS, DEVELOPERS, ADMINISTRADORES, MODERADORES, SOLICITUDES }

    private var _b: FragmentUsersListBinding? = null
    private val b get() = _b!!

    private val db by lazy { FirebaseFirestore.getInstance() }
    private var reg: ListenerRegistration? = null

    private lateinit var adapter: UsersAdapter
    private lateinit var mode: Mode

    // ✅ HTTP directo (evita el bug del SDK callable sin auth)
    private val http by lazy { OkHttpClient() }
    private val jsonMedia by lazy { "application/json; charset=utf-8".toMediaType() }

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

        reg = q.addSnapshotListener { snap, e ->
            if (e != null) {
                if (!LogoutBus.isLoggingOut()) {
                    Toast.makeText(requireContext(), "Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
                return@addSnapshotListener
            }

            val list = snap?.documents?.map { d ->
                AdminUserItem(
                    uid = d.getString("uid") ?: d.id,
                    nombre = d.getString("nombre") ?: "(sin nombre)",
                    email = d.getString("email") ?: "",
                    role = d.getString("role") ?: "user",
                    status = d.getString("status") ?: "approved",
                    deviceLastSeenAt = d.getTimestamp("deviceLastSeenAt"),
                    appLastSeenAt = d.getTimestamp("appLastSeenAt"),
                    appVersionCode = d.getLong("appVersionCode"),
                    appVersionName = d.getString("appVersionName") ?: ""
                )
            }.orEmpty()
                .sortedBy { it.nombre.lowercase() }

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
        val btnSuspend = v.findViewById<TextView>(R.id.btnSuspend)

        v.setBackgroundColor(bg)

        tvTitle.text = u.nombre
        tvTitle.setTextColor(fg)
        tvMsg.setTextColor(fg)

        btnApply.setTextColor(btnAccent)
        btnCancel.setTextColor(btnAccent)
        btnDelete.setTextColor(btnDanger)
        btnSuspend.setTextColor(btnDanger)

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
            showDeleteConfirmDialog(u)
        }

        btnApply.setOnClickListener {
            val newRole = roleValues.getOrNull(selectedIndex) ?: return@setOnClickListener
            dialog.dismiss()
            setRole(u.uid, newRole)
        }

        btnSuspend.setOnClickListener {
            dialog.dismiss()
            showSuspendDialog(u)
        }

        dialog.show()
    }

    private fun showSuspendDialog(u: AdminUserItem) {
        val et = EditText(requireContext()).apply {
            hint = "Motivo de suspensión (opcional)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }

        val dlg = AlertDialog.Builder(requireContext())
            .setTitle("Suspender a ${u.nombre}")
            .setMessage("El usuario será bloqueado y expulsado si está conectado.\n\nMotivo:")
            .setView(et)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Suspender") { _, _ ->
                val reason = et.text?.toString()?.trim().orEmpty()
                suspendUserViaHttpCallable(targetUid = u.uid, reason = reason)
            }
            .create()

        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.parseColor("#C62828"))
        }

        dlg.show()
    }

    /**
     * ✅ Llamada HTTP a endpoint callable:
     * POST https://us-central1-<projectId>.cloudfunctions.net/adminSuspendUser
     * Body: { "data": { targetUid, reason, idToken } }
     */
    private fun suspendUserViaHttpCallable(targetUid: String, reason: String) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            Toast.makeText(requireContext(), "Sesión inválida (no autenticado).", Toast.LENGTH_SHORT).show()
            return
        }

        user.getIdToken(true)
            .addOnSuccessListener { tokenResult ->
                val idToken = tokenResult.token.orEmpty()
                if (idToken.isBlank()) {
                    Toast.makeText(requireContext(), "Token vacío.", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                val projectId = FirebaseApp.getInstance().options.projectId.orEmpty()
                if (projectId.isBlank()) {
                    Toast.makeText(requireContext(), "projectId vacío.", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                val url = "https://us-central1-$projectId.cloudfunctions.net/adminSuspendUser"

                val root = JSONObject().apply {
                    put("data", JSONObject().apply {
                        put("targetUid", targetUid)
                        put("reason", reason)
                        put("idToken", idToken) // ✅ lo que tu backend espera
                    })
                }

                val body = root.toString().toRequestBody(jsonMedia)

                val req = Request.Builder()
                    .url(url)
                    .post(body)
                    // ✅ redundancia: aunque tu backend ya no depende de context.auth
                    .addHeader("Authorization", "Bearer $idToken")
                    .addHeader("Content-Type", "application/json")
                    .build()

                http.newCall(req).enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                        if (!isAdded) return
                        requireActivity().runOnUiThread {
                            Toast.makeText(requireContext(), "Error suspendiendo: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                        }
                    }

                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        val text = response.body.string().orEmpty()
                        if (!isAdded) return
                        requireActivity().runOnUiThread {
                            if (response.isSuccessful) {
                                if (!LogoutBus.isLoggingOut()) {
                                    Toast.makeText(requireContext(), "Usuario suspendido.", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                val msg = runCatching {
                                    val jo = JSONObject(text)
                                    val err = jo.optJSONObject("error")
                                    err?.optString("message") ?: text
                                }.getOrElse { text }

                                if (!LogoutBus.isLoggingOut()) {
                                    Toast.makeText(requireContext(), "Error suspendiendo: $msg", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                })
            }
            .addOnFailureListener { e ->
                Toast.makeText(requireContext(), "No se pudo validar sesión: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
    }

    private fun showDeleteConfirmDialog(u: AdminUserItem) {
        val dlg = AlertDialog.Builder(requireContext())
            .setTitle("Confirmar eliminación")
            .setMessage("¿Estás seguro que deseas eliminar el registro de \"${u.nombre}\"?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Eliminar") { _, _ ->
                deleteUserDoc(u.uid)
            }
            .create()

        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.parseColor("#C62828"))
        }

        dlg.show()
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
                if (!LogoutBus.isLoggingOut()) {
                    Toast.makeText(requireContext(), "Actualizado: $status", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                if (!LogoutBus.isLoggingOut()) {
                    Toast.makeText(requireContext(), "Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun setRole(uid: String, role: String) {
        db.collection("users").document(uid)
            .update("role", role)
            .addOnSuccessListener {
                if (!LogoutBus.isLoggingOut()) {
                    Toast.makeText(requireContext(), "Rol actualizado: $role", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                if (!LogoutBus.isLoggingOut()) {
                    Toast.makeText(requireContext(), "Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun deleteUserDoc(uid: String) {
        db.collection("users").document(uid)
            .delete()
            .addOnSuccessListener {
                if (!LogoutBus.isLoggingOut()) {
                    Toast.makeText(requireContext(), "Documento eliminado.", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                if (!LogoutBus.isLoggingOut()) {
                    Toast.makeText(requireContext(), "Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
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
