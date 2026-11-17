package com.cabgon.blackhawk.ui.pdf

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.FileProvider
import androidx.core.view.MenuItemCompat
import androidx.lifecycle.lifecycleScope
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.databinding.ActivityPdfViewerBinding
import com.cabgon.blackhawk.manuals.update.ManualUpdateManager
import com.cabgon.blackhawk.util.ManualCache
import com.cabgon.blackhawk.util.Prefs
import com.cabgon.blackhawk.util.SessionFlags
import com.cabgon.blackhawk.ui.StartupActivity
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

class PdfViewerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPdfViewerBinding
    private lateinit var assetPath: String
    private var localPdfFile: File? = null
    private var viewerFragment: AndroidXPdfViewerFragment? = null
    private var startPage1 = 1
    private var currentPage1 = 1
    private var pageCount = 0
    private var documentLoaded = false
    private var openedFromPartSearch = false

    companion object {
        const val EXTRA_ASSET_PATH = "assetPath"
        const val EXTRA_PAGE = "page"
        const val EXTRA_HIGHLIGHT_QUERY = "extra_highlight_query"
        const val EXTRA_HIGHLIGHT_QUERIES = "extra_highlight_queries"
        const val EXTRA_SHOW_PART_SEARCH_LOADING = "extra_show_part_search_loading"
        const val EXTRA_PART_DESCRIPTION = "extra_part_description"
        const val EXTRA_PART_DESCRIPTION_NUMBER = "extra_part_description_number"
        const val EXTRA_PART_DESCRIPTION_NSN = "extra_part_description_nsn"
        private const val FRAGMENT_TAG = "androidx_pdf_viewer"
        private const val ADOBE_READER_PACKAGE = "com.adobe.reader"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val authenticated = SessionFlags.loginCompleted && FirebaseAuth.getInstance().currentUser != null
        super.onCreate(savedInstanceState.takeIf { authenticated })
        if (!authenticated) {
            startActivity(Intent(this, StartupActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
            return
        }
        binding = ActivityPdfViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                closeViewer()
            }
        })
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        assetPath = intent.getStringExtra(EXTRA_ASSET_PATH)
            ?: run {
                Prefs.clearLastPdfState(this)
                finish()
                return
            }
        openedFromPartSearch = intent.getBooleanExtra(EXTRA_SHOW_PART_SEARCH_LOADING, false)
        binding.toolbar.title = assetPath.substringAfterLast('/').substringBeforeLast('.')

        val requestedPage = intent.getIntExtra(EXTRA_PAGE, -1)
        startPage1 = if (requestedPage >= 1) requestedPage else Prefs.getManualLastPage1(this, assetPath)
        currentPage1 = startPage1
        persistPdfState()

        if (savedInstanceState != null) {
            viewerFragment = supportFragmentManager.findFragmentByTag(FRAGMENT_TAG)
                as? AndroidXPdfViewerFragment
            if (viewerFragment != null) return
        }

        openPdfSmart(startPage1)
    }

    private fun openPdfSmart(page1: Int) {
        val manualId = sha1(assetPath)
        val cached = ManualCache.getLocalIfExists(this, manualId, assetPath)
        if (cached != null) {
            localPdfFile = cached
            loadPdf(cached, page1)
        } else {
            downloadToCacheThenOpen(page1)
        }
    }

    private fun loadPdf(file: File, page1: Int) {
        if (!file.isFile) {
            Toast.makeText(this, "PDF no disponible en caché.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        localPdfFile = file
        currentPage1 = page1.coerceAtLeast(1)
        if (openedFromPartSearch) showPartSearchLoading()
        val queries = intent.getStringArrayListExtra(EXTRA_HIGHLIGHT_QUERIES)
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            .orEmpty()
            .ifEmpty {
                intent.getStringExtra(EXTRA_HIGHLIGHT_QUERY)
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let(::listOf)
                    .orEmpty()
            }

        viewerFragment = AndroidXPdfViewerFragment.newInstance(
            pdfFile = file,
            startPage1 = currentPage1,
            highlightQueries = queries.toTypedArray(),
            partDescription = intent.getStringExtra(EXTRA_PART_DESCRIPTION),
            partNumberForDescription = intent.getStringExtra(EXTRA_PART_DESCRIPTION_NUMBER),
            partNsnForDescription = intent.getStringExtra(EXTRA_PART_DESCRIPTION_NSN)
        )
        supportFragmentManager.beginTransaction()
            .replace(R.id.pdfFragmentContainer, viewerFragment!!, FRAGMENT_TAG)
            .commit()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_pdf_viewer, menu)
        val adobeItem = menu.findItem(R.id.action_open_adobe)
        runCatching { packageManager.getApplicationIcon(ADOBE_READER_PACKAGE) }
            .onSuccess { icon ->
                MenuItemCompat.setIconTintList(adobeItem, null)
                adobeItem.icon = icon
            }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            closeViewer()
            true
        }
        R.id.action_search -> {
            if (documentLoaded) viewerFragment?.isTextSearchActive = true
            else Toast.makeText(this, "El documento aún se está cargando.", Toast.LENGTH_SHORT).show()
            true
        }
        R.id.action_goto -> {
            openGotoPage()
            true
        }
        R.id.action_open_adobe -> {
            openInAdobeReader()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun openGotoPage() {
        if (!documentLoaded || pageCount < 1) {
            Toast.makeText(this, "El documento aún se está cargando.", Toast.LENGTH_SHORT).show()
            return
        }

        val input = EditText(this).apply {
            hint = "Página (1-$pageCount)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("Ir a página")
            .setView(input)
            .setPositiveButton("Ir") { _, _ ->
                val page1 = input.text.toString().toIntOrNull()
                if (page1 == null || page1 !in 1..pageCount) {
                    Toast.makeText(this, "Ingresa una página entre 1 y $pageCount.", Toast.LENGTH_SHORT).show()
                } else {
                    currentPage1 = page1
                    viewerFragment?.goToPage(page1)
                    saveCurrentPage()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun openInAdobeReader() {
        val file = localPdfFile?.takeIf(File::isFile)
        if (file == null) {
            Toast.makeText(this, "El PDF todavía no está disponible.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri: Uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )
            val adobeIntent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/pdf")
                .setPackage(ADOBE_READER_PACKAGE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                startActivity(adobeIntent)
            } catch (_: ActivityNotFoundException) {
                val chooser = Intent.createChooser(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/pdf")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    getString(R.string.no_se_encontr_adobe_reader)
                )
                startActivity(chooser)
            }
        } catch (e: Exception) {
            Toast.makeText(
                this,
                e.localizedMessage ?: "No se pudo abrir el PDF externamente.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun onDocumentLoaded(totalPages: Int) {
        pageCount = totalPages
        documentLoaded = totalPages > 0
        currentPage1 = currentPage1.coerceIn(1, pageCount.coerceAtLeast(1))
        updatePageSubtitle()
    }

    fun onPartSearchHighlightsApplied() {
        if (!openedFromPartSearch || !::binding.isInitialized) return
        binding.partSearchLoadingOverlay.animate()
            .alpha(0f)
            .setDuration(180L)
            .withEndAction {
                binding.partSearchLoadingOverlay.visibility = android.view.View.GONE
                binding.partSearchLoadingOverlay.alpha = 1f
                binding.partSearchLoadingAnimation.cancelAnimation()
            }
            .start()
    }

    fun onPageChanged(page1: Int) {
        if (pageCount > 0) currentPage1 = page1.coerceIn(1, pageCount)
        saveCurrentPage()
        persistPdfState()
        updatePageSubtitle()
    }

    fun onDocumentError(message: String) {
        if (::binding.isInitialized) {
            binding.partSearchLoadingOverlay.visibility = android.view.View.GONE
            binding.partSearchLoadingAnimation.cancelAnimation()
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        Prefs.clearLastPdfState(this)
        finish()
    }

    private fun closeViewer() {
        Prefs.clearLastPdfState(this)
        finish()
    }

    private fun showPartSearchLoading() {
        binding.partSearchLoadingOverlay.apply {
            alpha = 0f
            visibility = android.view.View.VISIBLE
            animate().alpha(1f).setDuration(160L).start()
        }
    }

    private fun updatePageSubtitle() {
        if (pageCount > 0) binding.toolbar.subtitle = "$currentPage1 / $pageCount"
    }

    private fun saveCurrentPage() {
        if (::assetPath.isInitialized) Prefs.setManualLastPage1(this, assetPath, currentPage1)
    }

    private fun persistPdfState() {
        if (!::assetPath.isInitialized) return
        Prefs.setLastPdfState(
            this,
            Prefs.LastPdfState(
                assetPath = assetPath,
                page1 = currentPage1,
                highlightQueries = highlightQueriesFromIntent(),
                showPartSearchLoading = intent.getBooleanExtra(EXTRA_SHOW_PART_SEARCH_LOADING, false),
                partDescription = intent.getStringExtra(EXTRA_PART_DESCRIPTION),
                partDescriptionNumber = intent.getStringExtra(EXTRA_PART_DESCRIPTION_NUMBER),
                partDescriptionNsn = intent.getStringExtra(EXTRA_PART_DESCRIPTION_NSN)
            )
        )
    }

    private fun highlightQueriesFromIntent(): List<String> {
        return intent.getStringArrayListExtra(EXTRA_HIGHLIGHT_QUERIES)
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            .orEmpty()
            .ifEmpty {
                intent.getStringExtra(EXTRA_HIGHLIGHT_QUERY)
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let(::listOf)
                    .orEmpty()
            }
    }

    override fun onPause() {
        super.onPause()
        saveCurrentPage()
    }

    private fun downloadToCacheThenOpen(page1: Int) {
        val message = TextView(this).apply {
            text = getString(R.string.descargando_manual)
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
            addView(message, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(progress, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(binding.toolbar.title)
            .setView(content)
            .setCancelable(false)
            .create()
        dialog.show()

        val manualId = sha1(assetPath)
        lifecycleScope.launch {
            try {
                val file = ManualUpdateManager.ensureManualCached(
                    context = this@PdfViewerActivity,
                    manualId = manualId,
                    remotePath = assetPath,
                    localRelativePath = assetPath,
                    titleForUi = assetPath.substringAfterLast('/'),
                    emit = { event ->
                        if (event is ManualUpdateManager.Event.Downloading) {
                            val percent = (event.progress01 * 100f).toInt().coerceIn(0, 100)
                            progress.isIndeterminate = false
                            progress.progress = percent
                            message.text = "${getString(R.string.descargando_manual)} $percent%"
                        }
                    }
                )
                dialog.dismiss()
                localPdfFile = file
                loadPdf(file, page1)
            } catch (e: Exception) {
                dialog.dismiss()
                Toast.makeText(
                    this@PdfViewerActivity,
                    "No se pudo descargar: ${e.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    private fun sha1(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
