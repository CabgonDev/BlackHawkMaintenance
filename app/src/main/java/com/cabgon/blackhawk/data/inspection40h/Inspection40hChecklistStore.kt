package com.cabgon.blackhawk.data.inspection40h

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okio.buffer
import okio.source

object Inspection40hChecklistStore {

    data class FlatChecklistEntry(
        val sectionTitle: String,
        val item: Inspection40hChecklistItem,
        val depth: Int,
        val parentTitle: String?,
        val parentOrderIndex: Int?,
        val orderIndex: Int
    ) {
        val title: String get() = item.title
        val hasChildren: Boolean get() = !item.subitems.isNullOrEmpty()
    }

    private val moshi by lazy {
        Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    fun loadChecklist(
        context: Context,
        path: String = "checklists/inspection_40h.json"
    ): Inspection40hChecklist {
        context.assets.open(path).source().buffer().use { buf ->
            val json = buf.readUtf8()
            val adapter = moshi.adapter(Inspection40hChecklist::class.java)
            return requireNotNull(adapter.fromJson(json)) {
                "Checklist JSON inválido: $path"
            }
        }
    }

    fun flattenItems(checklist: Inspection40hChecklist): List<FlatChecklistEntry> {
        val out = mutableListOf<FlatChecklistEntry>()
        var order = 0

        fun appendItem(
            sectionTitle: String,
            item: Inspection40hChecklistItem,
            depth: Int,
            parentTitle: String?,
            parentOrderIndex: Int?
        ) {
            val myOrder = order++

            out += FlatChecklistEntry(
                sectionTitle = sectionTitle,
                item = item,
                depth = depth,
                parentTitle = parentTitle,
                parentOrderIndex = parentOrderIndex,
                orderIndex = myOrder
            )

            item.subitems.orEmpty().forEach { child ->
                appendItem(
                    sectionTitle = sectionTitle,
                    item = child,
                    depth = depth + 1,
                    parentTitle = item.title,
                    parentOrderIndex = myOrder
                )
            }
        }

        checklist.sections.forEach { section ->
            section.items.forEach { item ->
                appendItem(
                    sectionTitle = section.title,
                    item = item,
                    depth = 0,
                    parentTitle = null,
                    parentOrderIndex = null
                )
            }
        }

        return out
    }
}
