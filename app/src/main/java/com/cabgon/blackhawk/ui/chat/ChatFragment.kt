package com.cabgon.blackhawk.ui.chat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.data.PackageManager
import com.cabgon.blackhawk.data.db.AppDbProvider
import com.cabgon.blackhawk.data.user.UserSessionStore
import com.cabgon.blackhawk.databinding.FragmentChatBinding
import com.cabgon.blackhawk.ui.pdf.PdfViewerActivity
import com.cabgon.blackhawk.util.Prefs
import com.cabgon.blackhawk.util.Roles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    private lateinit var vm: ChatViewModel
    private lateinit var adapter: ChatAdapter

    private fun modelFile(): File =
        File(requireContext().filesDir, "models/model.gguf")

    private fun isAdminOrDev(): Boolean {
        val role = UserSessionStore(requireContext()).getProfile()?.role ?: Roles.USER
        return Roles.isAtLeast(role, Roles.ADMIN) // admin o developer
    }

    private val pickModelLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult

            viewLifecycleOwner.lifecycleScope.launch {
                binding.modelStatusText.text = "Instalando modelo…"
                binding.installModelBtn.isEnabled = false

                val ok = copyUriToModelFile(uri)

                binding.installModelBtn.isEnabled = true
                updateModelBanner()

                if (ok) {
                    parentFragmentManager.beginTransaction()
                        .replace(R.id.fragmentContainer, ChatFragment())
                        .commit()
                }
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = ChatAdapter(
            onSourceClick = { src ->
                openPdfAt(assetPath = src.manual, page1 = src.page)
            }
        )

        binding.chatRecycler.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.chatRecycler.adapter = adapter

        binding.sendBtn.setOnClickListener {
            val text = binding.inputEdit.text?.toString().orEmpty()
            vm.send(text)
            binding.inputEdit.setText("")
        }

        binding.inputEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                binding.sendBtn.performClick()
                true
            } else false
        }

        binding.installModelBtn.setOnClickListener {
            pickModelLauncher.launch(arrayOf("*/*"))
        }

        updateModelBanner()

        val db = AppDbProvider.get(requireContext())
        val chatDao = db.chatDao()

        val factory = ChatViewModelFactory(
            appContext = requireContext().applicationContext,
            chatDao = chatDao
        ) {
            Prefs.getPackage(requireContext()) ?: PackageManager.Pkg.IADS.name
        }

        vm = ViewModelProvider(this, factory)[ChatViewModel::class.java]

        vm.warmUpEngine()

        viewLifecycleOwner.lifecycleScope.launch {
            vm.messages.collectLatest { list ->
                adapter.submit(list)
                if (list.isNotEmpty()) binding.chatRecycler.scrollToPosition(list.size - 1)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.isThinking.collectLatest { thinking ->
                binding.thinkingBar.visibility = if (thinking) View.VISIBLE else View.GONE
                binding.sendBtn.isEnabled = !thinking
                binding.inputEdit.isEnabled = !thinking
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.thinkingLabel.collectLatest { label ->
                binding.thinkingText.text = if (label.isNotBlank()) label else ""
            }
        }

        vm.start()
    }

    private fun updateModelBanner() {
        // ✅ Solo Admin/Developer ven el banner del modelo
        if (!isAdminOrDev()) {
            binding.modelBanner.visibility = View.GONE
            return
        }

        val f = modelFile()
        val installed = f.exists() && f.length() > 10_000_000L

        binding.modelBanner.visibility = View.VISIBLE

        binding.modelStatusText.text = if (installed) {
            val dbg = com.cabgon.blackhawk.ai.llm.DeviceProfiles.debugString(
                context = requireContext().applicationContext,
                modelBytes = f.length()
            )
            "Modelo IA instalado.\n$dbg"
        } else {
            "Modelo IA no instalado. Instálalo para activar el Modo Avanzado offline."
        }

        binding.installModelBtn.visibility = if (installed) View.GONE else View.VISIBLE
    }

    private suspend fun copyUriToModelFile(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val ctx = requireContext().applicationContext
            val modelsDir = File(ctx.filesDir, "models").apply { mkdirs() }
            val outFile = File(modelsDir, "model.gguf")

            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile, false).use { output ->
                    input.copyTo(output, bufferSize = 1024 * 1024)
                }
            } ?: error("No se pudo abrir el archivo seleccionado")

            outFile.exists() && outFile.length() > 10_000_000L
        }.getOrElse { false }
    }

    private fun openPdfAt(assetPath: String, page1: Int) {
        val intent = Intent(requireContext(), PdfViewerActivity::class.java).apply {
            putExtra(PdfViewerActivity.EXTRA_ASSET_PATH, assetPath)
            putExtra(PdfViewerActivity.EXTRA_PAGE, page1)
        }
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
