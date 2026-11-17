package com.cabgon.blackhawk.ui.gallery

import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cabgon.blackhawk.databinding.FragmentGalleryBinding
import com.cabgon.blackhawk.util.navigateClean
import com.google.firebase.firestore.ListenerRegistration

class GalleryFragment : Fragment() {

    private var _b: FragmentGalleryBinding? = null
    private val b get() = _b!!
    private val repository = GalleryRepository()
    private var reg: ListenerRegistration? = null
    private lateinit var adapter: GalleryAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentGalleryBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = GalleryAdapter(repository) { item ->
            navigateClean(GalleryViewerFragment.newInstance(item), addToBackStack = true)
        }

        val spanCount = resolveSpanCount()
        val layoutManager = GridLayoutManager(requireContext(), spanCount).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (position == 0) spanCount else 1
                }
            }
        }

        b.recyclerGallery.layoutManager = layoutManager
        b.recyclerGallery.adapter = adapter
        b.recyclerGallery.setHasFixedSize(true)
        if (b.recyclerGallery.itemDecorationCount == 0) {
            b.recyclerGallery.addItemDecoration(GallerySpacingDecoration(spanCount, dp(12), includeEdge = false))
        }

        b.swipeRefresh.setOnRefreshListener { subscribe(force = true) }
        b.fabUpload.setOnClickListener {
            GalleryUploadBottomSheet().show(parentFragmentManager, GalleryUploadBottomSheet.TAG)
        }

        parentFragmentManager.setFragmentResultListener(GalleryUploadBottomSheet.REQUEST_KEY, viewLifecycleOwner) { _, _ ->
            subscribe(force = true)
        }

        subscribe(force = true)
    }

    private fun subscribe(force: Boolean) {
        if (force) {
            reg?.remove()
            reg = null
        }

        b.swipeRefresh.isRefreshing = true
        reg = repository.listenApproved(
            onResult = { items ->
                if (!isAdded || _b == null) return@listenApproved
                b.swipeRefresh.isRefreshing = false
                adapter.submit(items)
                val isEmpty = items.isEmpty()
                b.cardEmpty.isVisible = isEmpty
                b.txtCountBadge.text = countLabel(items.size)
            },
            onError = { e ->
                if (!isAdded || _b == null) return@listenApproved
                b.swipeRefresh.isRefreshing = false
                Toast.makeText(requireContext(), e.localizedMessage ?: "No se pudo cargar la galería.", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun countLabel(size: Int): String {
        return when (size) {
            0 -> getString(com.cabgon.blackhawk.R.string.galeria_sin_publicaciones)
            1 -> "1 publicación"
            else -> "$size publicaciones"
        }
    }

    private fun resolveSpanCount(): Int {
        val widthDp = resources.configuration.screenWidthDp
        return when {
            widthDp >= 1000 -> 3
            else -> 2
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        reg?.remove()
        reg = null
        _b = null
    }

    private class GallerySpacingDecoration(
        private val spanCount: Int,
        private val spacing: Int,
        private val includeEdge: Boolean
    ) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            val position = parent.getChildAdapterPosition(view)
            if (position == RecyclerView.NO_POSITION) return

            val layoutParams = view.layoutParams as? GridLayoutManager.LayoutParams
            val column = layoutParams?.spanIndex ?: 0

            if (position == 0) {
                outRect.bottom = spacing
                return
            }

            if (includeEdge) {
                outRect.left = spacing - column * spacing / spanCount
                outRect.right = (column + 1) * spacing / spanCount
                if (position < spanCount) outRect.top = spacing
                outRect.bottom = spacing
            } else {
                outRect.left = column * spacing / spanCount
                outRect.right = spacing - (column + 1) * spacing / spanCount
                outRect.bottom = spacing
            }
        }
    }
}
