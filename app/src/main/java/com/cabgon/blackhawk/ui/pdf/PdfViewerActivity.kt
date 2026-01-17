package com.cabgon.blackhawk.ui.pdf

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.databinding.ActivityPdfViewerBinding
import com.cabgon.blackhawk.util.Prefs
import com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.util.concurrent.Executors

class PdfViewerActivity : AppCompatActivity() {

    private lateinit var b: ActivityPdfViewerBinding
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var assetPath: String

    // ===== Search state =====
    private var currentSearchHits: List<SearchHit> = emptyList()
    private var currentHitIndex: Int = -1

    // ===== Resume state =====
    private var startPage1: Int = 1

    companion object {
        const val EXTRA_ASSET_PATH = "assetPath"
        const val EXTRA_PAGE = "page"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPdfViewerBinding.inflate(layoutInflater)
        setContentView(b.root)

        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        assetPath = intent.getStringExtra(EXTRA_ASSET_PATH)
            ?: error("EXTRA_ASSET_PATH faltante")

        // Título sin extensión
        b.toolbar.title = assetPath.substringAfterLast('/').substringBeforeLast('.')

        // Página inicial:
        // - si viene extra úsala
        // - si no, retoma la última guardada
        val extraPage1 = intent.getIntExtra(EXTRA_PAGE, -1)
        startPage1 = if (extraPage1 >= 1) extraPage1 else Prefs.getManualLastPage1(this, assetPath)

        loadPdf(startPage1)
    }

    private fun loadPdf(page1: Int) {
        val startPage0 = (page1 - 1).coerceAtLeast(0)

        b.pdfView.fromAsset(assetPath)
            .defaultPage(startPage0)
            .enableSwipe(true)
            .swipeHorizontal(false)
            .enableAnnotationRendering(true)
            .scrollHandle(DefaultScrollHandle(this))
            .spacing(8)

            // ✅ Asegura que al terminar de cargar se vaya a la página guardada
            .onLoad { _ ->
                b.pdfView.jumpTo(startPage0, false)
            }

            // ✅ Guarda la última página mientras navegas
            .onPageChange { page0, _ ->
                Prefs.setManualLastPage1(this, assetPath, page0 + 1)
            }

            .load()
    }

    private fun jumpTo(page1: Int) {
        val p0 = (page1 - 1).coerceAtLeast(0)
        b.pdfView.jumpTo(p0, true)
        Prefs.setManualLastPage1(this, assetPath, p0 + 1)
    }

    override fun onPause() {
        super.onPause()
        // ✅ Backup: por si cierran rápido y no alcanzó onPageChange
        try {
            val currentPage0 = b.pdfView.currentPage
            Prefs.setManualLastPage1(this, assetPath, currentPage0 + 1)
        } catch (_: Exception) {
        }
    }

    private fun openToc() {
        val dlg = BottomSheetDialog(this)
        val v = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_toc, null, false)
        val rv = v.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvToc)
        rv.layoutManager = LinearLayoutManager(this)
        dlg.setContentView(v)
        dlg.show()

        io.execute {
            try {
                val toc = PdfBoxManualTools.extractToc(this, assetPath)
                runOnUiThread {
                    if (toc.isEmpty()) {
                        Toast.makeText(this, "Este PDF no trae índice.", Toast.LENGTH_SHORT).show()
                        dlg.dismiss()
                        return@runOnUiThread
                    }
                    rv.adapter = TocAdapter(toc) {
                        dlg.dismiss()
                        jumpTo(it.page1)
                        flashSearchResult()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, e.message ?: "Error leyendo índice", Toast.LENGTH_LONG).show()
                    dlg.dismiss()
                }
            }
        }
    }

    private fun openSearch() {
        val dlg = BottomSheetDialog(this)
        val v = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_search, null, false)

        val edt = v.findViewById<EditText>(R.id.edtQuery)
        val btn = v.findViewById<android.widget.Button>(R.id.btnSearch)
        val progress = v.findViewById<ProgressBar>(R.id.progress)
        val rv = v.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvResults)

        val navRow = v.findViewById<android.view.View>(R.id.searchNavRow)
        val btnPrev = v.findViewById<android.widget.ImageButton>(R.id.btnPrevHit)
        val btnNext = v.findViewById<android.widget.ImageButton>(R.id.btnNextHit)
        val txtCounter = v.findViewById<android.widget.TextView>(R.id.txtHitCounter)

        rv.layoutManager = LinearLayoutManager(this)

        val adapter = SearchResultAdapter(emptyList()) {
            dlg.dismiss()
            jumpTo(it.page1)
            flashSearchResult()
        }
        rv.adapter = adapter

        fun hideKeyboard() {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(edt.windowToken, 0)
        }

        fun showCurrentHit() {
            if (currentSearchHits.isEmpty() || currentHitIndex < 0) {
                adapter.submitList(emptyList())
                navRow.visibility = android.view.View.GONE
                txtCounter.text = "0/0"
                btnPrev.isEnabled = false
                btnNext.isEnabled = false
                return
            }

            val hit = currentSearchHits[currentHitIndex]
            adapter.submitList(listOf(hit))

            txtCounter.text = "${currentHitIndex + 1}/${currentSearchHits.size}"
            btnPrev.isEnabled = currentHitIndex > 0
            btnNext.isEnabled = currentHitIndex < currentSearchHits.lastIndex
            navRow.visibility = android.view.View.VISIBLE
        }

        fun doSearch() {
            val q = edt.text.toString().trim()
            if (q.isBlank()) {
                Toast.makeText(this, "Escribe qué buscar.", Toast.LENGTH_SHORT).show()
                return
            }

            hideKeyboard()
            progress.visibility = android.view.View.VISIBLE
            btn.isEnabled = false

            io.execute {
                try {
                    val hits = PdfBoxManualTools.searchText(this, assetPath, q)
                    runOnUiThread {
                        progress.visibility = android.view.View.GONE
                        btn.isEnabled = true

                        currentSearchHits = hits
                        currentHitIndex = if (hits.isNotEmpty()) 0 else -1

                        if (hits.isEmpty()) {
                            Toast.makeText(this, "Sin resultados.", Toast.LENGTH_SHORT).show()
                        } else {
                            jumpTo(hits[0].page1)
                            flashSearchResult()
                        }

                        showCurrentHit()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        progress.visibility = android.view.View.GONE
                        btn.isEnabled = true
                        Toast.makeText(this, e.message ?: "Error en búsqueda", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        btnPrev.setOnClickListener {
            if (currentHitIndex > 0) {
                currentHitIndex--
                val hit = currentSearchHits[currentHitIndex]
                jumpTo(hit.page1)
                flashSearchResult()
                showCurrentHit()
            }
        }

        btnNext.setOnClickListener {
            if (currentHitIndex < currentSearchHits.lastIndex) {
                currentHitIndex++
                val hit = currentSearchHits[currentHitIndex]
                jumpTo(hit.page1)
                flashSearchResult()
                showCurrentHit()
            }
        }

        btn.setOnClickListener { doSearch() }

        edt.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else false
        }

        dlg.setContentView(v)
        dlg.show()

        showCurrentHit()
    }

    private fun flashSearchResult() {
        val v = b.searchFlash
        v.visibility = android.view.View.VISIBLE
        v.alpha = 0f

        v.animate()
            .alpha(1f)
            .setDuration(90)
            .withEndAction {
                v.animate()
                    .alpha(0f)
                    .setDuration(180)
                    .withEndAction { v.visibility = android.view.View.GONE }
                    .start()
            }
            .start()
    }

    private fun openGotoPage() {
        val input = EditText(this).apply {
            hint = "Página (ej. 120)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Ir a página")
            .setView(input)
            .setPositiveButton("Ir") { d, _ ->
                input.text.toString().toIntOrNull()?.let { jumpTo(it) }
                flashSearchResult()
                d.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_pdf_viewer, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            R.id.action_toc -> { openToc(); true }
            R.id.action_search -> { openSearch(); true }
            R.id.action_goto -> { openGotoPage(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }
}
