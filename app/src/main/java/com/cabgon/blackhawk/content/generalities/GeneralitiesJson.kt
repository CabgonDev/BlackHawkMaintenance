package com.cabgon.blackhawk.content.generalities

import org.json.JSONArray
import org.json.JSONObject

object GeneralitiesJson {

    fun parse(json: String): GeneralitiesManifest {
        val root = JSONObject(json)

        val schema = root.optInt("schema", 1)
        val generatedAt = root.optString("generatedAt", "")

        val sectionsArr = root.optJSONArray("sections") ?: JSONArray()
        val sections = ArrayList<GeneralitiesSection>(sectionsArr.length())

        for (i in 0 until sectionsArr.length()) {
            val s = sectionsArr.optJSONObject(i) ?: continue

            val id = s.optString("id", "").trim()
            val title = s.optString("title", "").trim()
            val order = s.optInt("order", 0)

            val blocksArr = s.optJSONArray("blocks") ?: JSONArray()
            val blocks = ArrayList<GeneralitiesBlock>(blocksArr.length())

            for (b in 0 until blocksArr.length()) {
                val o = blocksArr.optJSONObject(b) ?: continue
                val type = o.optString("type", "").trim().lowercase()
                val blockTitle = o.optStringOrNull("title")

                when (type) {
                    "table" -> {
                        val columns = o.optStringList("columns")
                        val rows = o.opt2dStringList("rows")

                        // columnas obligatorias
                        if (columns.isNotEmpty()) {
                            blocks += GeneralitiesTableBlock(
                                title = blockTitle,
                                columns = columns,
                                rows = rows
                            )
                        }
                    }
                    else -> {
                        // futuro: "text", "cards", etc.
                    }
                }
            }

            if (id.isNotBlank() && title.isNotBlank()) {
                sections += GeneralitiesSection(
                    id = id,
                    title = title,
                    order = order,
                    blocks = blocks
                )
            }
        }

        return GeneralitiesManifest(
            schema = schema,
            generatedAt = generatedAt,
            sections = sections.sortedBy { it.order }
        )
    }

    // ---------- helpers ----------

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val v = optString(key, "").trim()
        if (v.isBlank()) return null
        if (v.equals("null", ignoreCase = true)) return null
        return v
    }

    private fun JSONObject.optStringList(key: String): List<String> {
        val arr = optJSONArray(key) ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) out += arr.optString(i, "").trim()
        return out.filter { it.isNotBlank() }
    }

    private fun JSONObject.opt2dStringList(key: String): List<List<String>> {
        val arr = optJSONArray(key) ?: return emptyList()
        val out = ArrayList<List<String>>(arr.length())
        for (i in 0 until arr.length()) {
            val rowArr = arr.optJSONArray(i) ?: JSONArray()
            val row = ArrayList<String>(rowArr.length())
            for (j in 0 until rowArr.length()) row += rowArr.optString(j, "")
            out += row
        }
        return out
    }
}
