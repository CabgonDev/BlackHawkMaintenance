package com.cabgon.blackhawk.admin.generalities

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cabgon.blackhawk.ai.await
import com.cabgon.blackhawk.databinding.FragmentAdminGeneralitiesRollbackBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch

class AdminGeneralitiesRollbackFragment : Fragment() {

    private var _b: FragmentAdminGeneralitiesRollbackBinding? = null
    private val b get() = _b!!

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private lateinit var adapter: RollbackAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentAdminGeneralitiesRollbackBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = RollbackAdapter { item -> rollback(item) }

        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.recycler.adapter = adapter

        load()
    }

    private fun load() {
        b.txtStatus.text = "Cargando audit logs…"
        lifecycleScope.launch {
            val snap = db.collection("content_admin")
                .document("audit_root")
                .collection("audit_logs")
                .whereEqualTo("module", "generalities")
                .whereEqualTo("action", "publish_stable")
                .get()
                .await()

            val items = snap.documents.mapNotNull { d ->
                val v = d.getLong("version") ?: return@mapNotNull null
                val stablePath = d.getString("stablePath").orEmpty()
                val releasePath = d.getString("releasePath").orEmpty()
                val sha = d.getString("sha256").orEmpty()
                val bytes = d.getLong("bytes") ?: 0L
                RollbackItem(v, stablePath, releasePath, sha, bytes)
            }.sortedByDescending { it.version }

            adapter.submit(items)
            b.txtStatus.text = "Publicaciones encontradas: ${items.size}"
        }
    }

    private fun rollback(item: RollbackItem) {
        val uid = auth.currentUser?.uid ?: return toast("No autenticado.")

        lifecycleScope.launch {
            b.txtStatus.text = "Aplicando rollback a v${item.version}…"

            // Re-point stable.generalities a releasePath (no al stablePath)
            val channelsRef = db.collection("content_ota").document("channels")
            val channelsSnap = channelsRef.get().await()
            val stable = channelsSnap.get("stable") as? Map<*, *> ?: emptyMap<String, Any>()

            val newSpec = mapOf(
                "version" to item.version,
                "storagePath" to item.releasePath, // ✅ usamos release versionado
                "bytes" to item.bytes,
                "sha256" to item.sha256,
                "minAppVersionCode" to 0
            )

            val newStableMap = HashMap<String, Any>()
            stable.forEach { (k, v) -> if (k is String) newStableMap[k] = v as Any }
            newStableMap["generalities"] = newSpec

            channelsRef.set(mapOf("stable" to newStableMap), SetOptions.merge()).await()

            // audit rollback
            db.collection("content_admin")
                .document("audit_root")
                .collection("audit_logs")
                .add(
                    mapOf(
                        "module" to "generalities",
                        "action" to "rollback_stable",
                        "byUid" to uid,
                        "createdAt" to System.currentTimeMillis(),
                        "version" to item.version,
                        "storagePath" to item.releasePath,
                        "sha256" to item.sha256,
                        "bytes" to item.bytes
                    )
                ).await()

            b.txtStatus.text = "Rollback aplicado a v${item.version}."
            toast("Rollback aplicado a v${item.version}.")
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    data class RollbackItem(
        val version: Long,
        val stablePath: String,
        val releasePath: String,
        val sha256: String,
        val bytes: Long
    )
}
