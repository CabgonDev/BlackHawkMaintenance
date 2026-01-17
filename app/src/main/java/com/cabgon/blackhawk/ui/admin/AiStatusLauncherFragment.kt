package com.cabgon.blackhawk.ui.admin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.cabgon.blackhawk.data.user.UserSessionStore
import com.cabgon.blackhawk.databinding.FragmentAdminAiStatusLauncherBinding
import com.cabgon.blackhawk.ui.AiStatusReportBuilder
import com.cabgon.blackhawk.util.Roles
import kotlinx.coroutines.launch

class AiStatusLauncherFragment : Fragment() {

    private var _b: FragmentAdminAiStatusLauncherBinding? = null
    private val b get() = _b!!

    private var lastReport: String = "—"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentAdminAiStatusLauncherBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val role = Roles.normalize(UserSessionStore(requireContext()).getProfile()?.role)

        if (role != Roles.DEVELOPER) {
            Toast.makeText(requireContext(), "Acceso restringido: solo Developer.", Toast.LENGTH_LONG).show()
            parentFragmentManager.popBackStack()
            return
        }

        b.txtTitle.text = "Estado IA (Developer)"
        b.btnCopy.isEnabled = false
        b.txtReport.text = "Cargando estado IA…"

        lifecycleScope.launch {
            val report = AiStatusReportBuilder.build(requireContext().applicationContext)
            lastReport = report
            b.txtReport.text = report
            b.btnCopy.isEnabled = true
        }

        b.btnCopy.setOnClickListener {
            copyToClipboard(lastReport)
            Toast.makeText(requireContext(), "Reporte copiado al portapapeles", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("AI Status", text))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
