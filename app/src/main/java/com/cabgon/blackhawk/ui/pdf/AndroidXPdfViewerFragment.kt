package com.cabgon.blackhawk.ui.pdf

import android.net.Uri
import android.os.Bundle
import android.util.SparseArray
import android.graphics.Color
import android.graphics.RectF
import androidx.pdf.ExperimentalPdfApi
import androidx.pdf.Highlight
import androidx.pdf.PdfDocument
import androidx.pdf.PdfRect
import androidx.pdf.view.PdfView
import androidx.pdf.viewer.fragment.PdfViewerFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class AndroidXPdfViewerFragment : PdfViewerFragment() {
    private var activePdfView: PdfView? = null
    private var startPage1 = 1
    private var pdfFilePath = ""
    private var highlightQueries: List<String> = emptyList()
    private var partDescription: String? = null
    private var partNumberForDescription: String? = null
    private var partNsnForDescription: String? = null
    private var firstContentLoaded = false
    private var renderReadyView: PdfView? = null
    private var renderReadyRunnable: Runnable? = null
    private var appliedHighlights: List<Highlight> = emptyList()
    private var highlightReapplyView: PdfView? = null
    private var highlightReapplyRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = requireArguments()
        startPage1 = args.getInt(ARG_START_PAGE, 1).coerceAtLeast(1)
        pdfFilePath = args.getString(ARG_FILE_PATH).orEmpty()
        highlightQueries = args.getStringArrayList(ARG_HIGHLIGHT_QUERIES).orEmpty()
        partDescription = args.getString(ARG_PART_DESCRIPTION)
        partNumberForDescription = args.getString(ARG_PART_DESCRIPTION_NUMBER)
        partNsnForDescription = args.getString(ARG_PART_DESCRIPTION_NSN)
        if (savedInstanceState == null) {
            documentUri = Uri.fromFile(File(pdfFilePath))
        }
    }

    @OptIn(ExperimentalPdfApi::class)
    override fun onPdfViewCreated(pdfView: PdfView) {
        super.onPdfViewCreated(pdfView)
        this.activePdfView = pdfView
        firstContentLoaded = false
        pdfView.addOnFirstContentLoadListener {
            firstContentLoaded = true
        }
        pdfView.addOnViewportChangedListener(object : PdfView.OnViewportChangedListener {
            override fun onViewportChanged(
                firstVisiblePage: Int,
                visiblePagesCount: Int,
                pageLocations: SparseArray<RectF>,
                zoomLevel: Float
            ) {
                (activity as? PdfViewerActivity)?.onPageChanged(firstVisiblePage + 1)
                scheduleHighlightReapply(pdfView)
            }
        })
    }

    @OptIn(ExperimentalPdfApi::class)
    override fun onLoadDocumentSuccess(document: PdfDocument) {
        super.onLoadDocumentSuccess(document)
        (activity as? PdfViewerActivity)?.onDocumentLoaded(document.pageCount)

        val view = activePdfView ?: return
        val page1 = startPage1.coerceIn(1, document.pageCount)
        val targetPageIndex = page1 - 1
        appliedHighlights = emptyList()
        view.post { view.scrollToPage(page1 - 1) }

        if (highlightQueries.isEmpty() && partDescription.isNullOrBlank()) {
            waitUntilVisibleContentReady(view, targetPageIndex)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val pageIndex = targetPageIndex
            val highlights = ArrayList<Highlight>()
            val seenBounds = HashSet<String>()
            val matchesByQuery = HashMap<String, List<List<RectF>>>()

            suspend fun findMatches(query: String): List<List<RectF>> {
                val key = query.trim()
                if (key.isBlank()) return emptyList()
                matchesByQuery[key]?.let { return it }
                val matches = searchPage(document, key, pageIndex)
                matchesByQuery[key] = matches
                return matches
            }

            fun addHighlights(rectangles: List<RectF>) {
                for (rect in rectangles) {
                    val boundsKey = listOf(rect.left, rect.top, rect.right, rect.bottom)
                        .joinToString("|") { (it * 10f).roundToInt().toString() }
                    if (!seenBounds.add(boundsKey)) continue
                    if (highlights.size >= MAX_EXTERNAL_HIGHLIGHTS) return
                    highlights += Highlight(
                        area = PdfRect(pageIndex, rect),
                        color = EXTERNAL_HIGHLIGHT_COLOR
                    )
                }
            }

            for (query in highlightQueries.map(String::trim).filter(String::isNotBlank).distinct()) {
                addHighlights(findMatches(query).flatten())
                if (highlights.size >= MAX_EXTERNAL_HIGHLIGHTS) break
            }

            val description = partDescription?.trim().orEmpty()
            if (description.isNotBlank() && highlights.size < MAX_EXTERNAL_HIGHLIGHTS) {
                val partMatches = findMatches(partNumberForDescription.orEmpty())
                val nsnMatches = findMatches(partNsnForDescription.orEmpty())
                val rowAnchor = findPartRowAnchor(partMatches, nsnMatches)
                if (rowAnchor != null) {
                    val descriptionMatches = findMatches(description)
                    val rowDescription = findDescriptionInRow(descriptionMatches, rowAnchor)
                    addHighlights(rowDescription.orEmpty())
                }
            }
            if (!awaitPdfDocumentAttached(view, document)) return@launch
            appliedHighlights = highlights.toList()
            view.setHighlights(appliedHighlights)
            waitUntilVisibleContentReady(view, targetPageIndex)
        }
    }

    override fun onResume() {
        super.onResume()
        activePdfView?.let(::scheduleHighlightReapply)
    }

    /** Re-applies highlights after AndroidX rebuilds page content during zoom or resume. */
    private fun scheduleHighlightReapply(view: PdfView) {
        if (appliedHighlights.isEmpty() || activePdfView !== view) return

        highlightReapplyView?.let { previousView ->
            highlightReapplyRunnable?.let(previousView::removeCallbacks)
        }

        val reapply = Runnable {
            if (isAdded && activePdfView === view && view.pdfDocument != null && appliedHighlights.isNotEmpty()) {
                view.setHighlights(appliedHighlights)
            }
            highlightReapplyView = null
            highlightReapplyRunnable = null
        }
        highlightReapplyView = view
        highlightReapplyRunnable = reapply
        view.postDelayed(reapply, HIGHLIGHT_REAPPLY_DELAY_MS)
    }

    /**
     * setHighlights() only updates the pending draw state. Keep the loading layer visible until
     * AndroidX reports first content and the target page is visible, otherwise the PDF can still
     * be blank for a few frames after the indicator disappears.
     */
    private fun waitUntilVisibleContentReady(view: PdfView, targetPageIndex: Int) {
        renderReadyView?.let { previousView ->
            renderReadyRunnable?.let(previousView::removeCallbacks)
        }

        var consecutiveReadyFrames = 0
        lateinit var check: Runnable
        check = Runnable {
            if (!isAdded || activePdfView !== view) return@Runnable

            val visiblePageCount = view.visiblePagesCount
            val hasVisiblePage = visiblePageCount > 0
            val targetPageVisible = hasVisiblePage &&
                targetPageIndex >= view.firstVisiblePage &&
                targetPageIndex < view.firstVisiblePage + visiblePageCount
            val ready = targetPageVisible && firstContentLoaded

            consecutiveReadyFrames = if (ready) consecutiveReadyFrames + 1 else 0
            val readyForHighlight = consecutiveReadyFrames >= REQUIRED_READY_FRAMES

            if (!readyForHighlight) {
                // Highlights are painted on an invalidation frame after setHighlights(). Require
                // several consecutive frames so the overlay cannot fade out before that draw.
                view.postOnAnimation(check)
                return@Runnable
            }

            renderReadyRunnable = null
            renderReadyView = null
            if (readyForHighlight) {
                view.postDelayed({
                    if (isAdded && activePdfView === view) {
                        (activity as? PdfViewerActivity)?.onPartSearchHighlightsApplied()
                    }
                }, HIGHLIGHT_SETTLE_DELAY_MS)
            }
        }

        renderReadyView = view
        renderReadyRunnable = check
        view.postOnAnimation(check)
    }

    /** PdfViewerFragment invokes onLoadDocumentSuccess before assigning PdfView.pdfDocument. */
    private suspend fun awaitPdfDocumentAttached(view: PdfView, document: PdfDocument): Boolean {
        repeat(PDF_DOCUMENT_READY_ATTEMPTS) {
            if (!isAdded || activePdfView !== view) return false
            if (view.pdfDocument === document) return true
            delay(PDF_DOCUMENT_READY_POLL_MS)
        }
        return isAdded && activePdfView === view && view.pdfDocument === document
    }

    private suspend fun searchPage(
        document: PdfDocument,
        query: String,
        pageIndex: Int
    ): List<List<RectF>> {
        val matches = try {
            document.searchDocument(query, pageIndex..pageIndex).get(pageIndex).orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }
        return matches.mapNotNull { match ->
            match.bounds
                .filter { it.left.isFinite() && it.top.isFinite() && it.right.isFinite() && it.bottom.isFinite() }
                .takeIf { it.isNotEmpty() }
        }
    }

    private fun findPartRowAnchor(
        partNumberMatches: List<List<RectF>>,
        nsnMatches: List<List<RectF>>
    ): RectF? {
        val partNumberBounds = partNumberMatches.mapNotNull(::unionBounds)
        val nsnBounds = nsnMatches.mapNotNull(::unionBounds)

        if (partNumberBounds.size == 1) return partNumberBounds.single()
        if (partNumberBounds.isEmpty()) return nsnBounds.singleOrNull()
        if (nsnBounds.isEmpty()) return null

        // Repeated part numbers require a unique NSN match on the same table row.
        val ranked = partNumberBounds
            .map { partBounds ->
                partBounds to nsnBounds.minOf { nsn -> verticalCenterDistance(partBounds, nsn) }
            }
            .sortedBy { it.second }
        val (best, distance) = ranked.first()
        val nextDistance = ranked.getOrNull(1)?.second ?: Float.POSITIVE_INFINITY
        val tolerance = max(MIN_ROW_CENTER_DISTANCE, best.height() * ROW_CENTER_TOLERANCE_FACTOR)
        return best.takeIf { distance <= tolerance && nextDistance - distance > AMBIGUITY_DISTANCE }
    }

    private fun findDescriptionInRow(
        descriptionMatches: List<List<RectF>>,
        rowAnchor: RectF
    ): List<RectF>? {
        // Accept only a description match whose first line shares the part-number row.
        val candidates = descriptionMatches.mapNotNull { bounds ->
            val firstLine = bounds.minByOrNull { it.top } ?: return@mapNotNull null
            if (firstLine.left < rowAnchor.right - DESCRIPTION_COLUMN_OVERLAP_TOLERANCE) {
                return@mapNotNull null
            }
            val centerDistance = verticalCenterDistance(rowAnchor, firstLine)
            val tolerance = max(
                MIN_ROW_CENTER_DISTANCE,
                max(rowAnchor.height(), firstLine.height()) * ROW_CENTER_TOLERANCE_FACTOR
            )
            if (centerDistance > tolerance) return@mapNotNull null

            val horizontalDistance = max(0f, firstLine.left - rowAnchor.right)
            bounds to (centerDistance + horizontalDistance * HORIZONTAL_DISTANCE_WEIGHT)
        }.sortedBy { it.second }

        val best = candidates.firstOrNull() ?: return null
        val nextScore = candidates.getOrNull(1)?.second ?: Float.POSITIVE_INFINITY
        if (nextScore - best.second <= AMBIGUITY_DISTANCE) return null
        return best.first
    }

    private fun unionBounds(rectangles: List<RectF>): RectF? {
        if (rectangles.isEmpty()) return null
        return RectF(
            rectangles.minOf { it.left },
            rectangles.minOf { it.top },
            rectangles.maxOf { it.right },
            rectangles.maxOf { it.bottom }
        )
    }

    private fun verticalCenterDistance(first: RectF, second: RectF): Float =
        abs(first.centerY() - second.centerY())

    override fun onLoadDocumentError(error: Throwable) {
        super.onLoadDocumentError(error)
        (activity as? PdfViewerActivity)?.onDocumentError(
            error.localizedMessage ?: "No se pudo abrir el PDF."
        )
    }

    override fun onDestroyView() {
        renderReadyView?.let { view ->
            renderReadyRunnable?.let(view::removeCallbacks)
        }
        highlightReapplyView?.let { view ->
            highlightReapplyRunnable?.let(view::removeCallbacks)
        }
        renderReadyView = null
        renderReadyRunnable = null
        highlightReapplyView = null
        highlightReapplyRunnable = null
        appliedHighlights = emptyList()
        firstContentLoaded = false
        activePdfView = null
        super.onDestroyView()
    }

    fun goToPage(page1: Int) {
        activePdfView?.scrollToPage(page1 - 1)
    }

    companion object {
        private const val ARG_START_PAGE = "start_page"
        private const val ARG_FILE_PATH = "file_path"
        private const val ARG_HIGHLIGHT_QUERIES = "highlight_queries"
        private const val ARG_PART_DESCRIPTION = "part_description"
        private const val ARG_PART_DESCRIPTION_NUMBER = "part_description_number"
        private const val ARG_PART_DESCRIPTION_NSN = "part_description_nsn"
        private const val MAX_EXTERNAL_HIGHLIGHTS = 100
        private const val MIN_ROW_CENTER_DISTANCE = 2.5f
        private const val ROW_CENTER_TOLERANCE_FACTOR = 0.35f
        private const val AMBIGUITY_DISTANCE = 0.75f
        private const val DESCRIPTION_COLUMN_OVERLAP_TOLERANCE = 1f
        private const val HORIZONTAL_DISTANCE_WEIGHT = 0.01f
        private const val REQUIRED_READY_FRAMES = 3
        private const val PDF_DOCUMENT_READY_POLL_MS = 16L
        private const val PDF_DOCUMENT_READY_ATTEMPTS = 180
        private const val HIGHLIGHT_SETTLE_DELAY_MS = 120L
        private const val HIGHLIGHT_REAPPLY_DELAY_MS = 120L
        private val EXTERNAL_HIGHLIGHT_COLOR = Color.YELLOW

        fun newInstance(
            pdfFile: File,
            startPage1: Int,
            highlightQueries: Array<String>,
            partDescription: String? = null,
            partNumberForDescription: String? = null,
            partNsnForDescription: String? = null
        ) = AndroidXPdfViewerFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_FILE_PATH, pdfFile.absolutePath)
                putInt(ARG_START_PAGE, startPage1)
                putStringArrayList(ARG_HIGHLIGHT_QUERIES, ArrayList(highlightQueries.toList()))
                putString(ARG_PART_DESCRIPTION, partDescription)
                putString(ARG_PART_DESCRIPTION_NUMBER, partNumberForDescription)
                putString(ARG_PART_DESCRIPTION_NSN, partNsnForDescription)
            }
        }
    }
}
