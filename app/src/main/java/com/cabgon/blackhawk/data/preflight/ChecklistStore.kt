package com.cabgon.blackhawk.data.preflight

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okio.buffer
import okio.source

object ChecklistStore {

    private val moshi by lazy {
        Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    fun loadPreflight(context: Context, path: String = "checklists/preflight.json"): PreflightChecklist {
        context.assets.open(path).source().buffer().use { buf ->
            val json = buf.readUtf8()
            val adapter = moshi.adapter(PreflightChecklist::class.java)
            return requireNotNull(adapter.fromJson(json)) { "Checklist JSON inválido: $path" }
        }
    }

    /** Fase 1: aplana a lista de títulos (compatible con tu DB actual). */
    fun flattenTitles(checklist: PreflightChecklist): List<String> =
        checklist.sections.flatMap { sec -> sec.items.map { it.title } }
}
