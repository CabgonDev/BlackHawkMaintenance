package com.cabgon.blackhawk.ai

import java.text.Normalizer
import java.util.Locale

/**
 * Planeador liviano para mejorar busquedas generales en manuales.
 * No ejecuta busqueda especial de P/N; solo genera variantes seguras para FTS.
 */
object TechnicalQueryPlanner {

    data class Plan(
        val original: String,
        val translated: String,
        val queries: List<String>,
        val keywords: List<String>
    )

    fun build(original: String, translated: String = original): Plan {
        val originalN = normalize(original)
        val translatedN = normalize(translated)
        val dictionaryN = applyTechnicalDictionary(originalN)
        val merged = listOf(translatedN, dictionaryN, originalN)
            .filter { it.isNotBlank() }
            .distinct()

        val keywordList = merged
            .flatMap { tokenize(it) }
            .filter { it.length >= 3 && it !in stopWords }
            .distinct()
            .take(14)

        val compactKeywords = keywordList.take(8).joinToString(" ")
        val systemExpansion = expandBySystem(keywordList)

        val queries = buildList {
            merged.forEach { add(it) }
            if (compactKeywords.isNotBlank()) add(compactKeywords)
            systemExpansion.forEach { add(it) }
        }
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.length >= 3 }
            .distinct()
            .take(8)

        return Plan(
            original = original.trim(),
            translated = translated.trim(),
            queries = queries,
            keywords = keywordList
        )
    }

    private fun normalize(input: String): String {
        val noAccents = Normalizer.normalize(input, Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
        return noAccents
            .replace('\u2010', '-')
            .replace('\u2011', '-')
            .replace('\u2012', '-')
            .replace('\u2013', '-')
            .replace('\u2014', '-')
            .replace('\u2212', '-')
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\s\\-_/\\.]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun tokenize(input: String): List<String> = input
        .split(Regex("[\\s/\\\\:_\\-.]+"))
        .map { it.trim() }
        .filter { it.isNotBlank() }

    private fun applyTechnicalDictionary(input: String): String {
        var out = input
        replacements.forEach { (es, en) ->
            out = out.replace(Regex("\\b${Regex.escape(es)}\\b"), en)
        }
        return out
    }

    private fun expandBySystem(tokens: List<String>): List<String> {
        val t = tokens.toSet()
        val out = mutableListOf<String>()

        fun hasAny(vararg words: String): Boolean = words.any { it in t }

        if (hasAny("hydraulic", "hydraulics", "hidraulico", "hidraulica")) {
            out += "hydraulic system pressure pump valve reservoir"
            out += "hydraulic troubleshooting pressure leak pump"
        }
        if (hasAny("fuel", "combustible", "gasolina")) {
            out += "fuel system pump boost valve tank"
            out += "fuel troubleshooting pressure flow indication"
        }
        if (hasAny("electrical", "electric", "electrico", "generador", "generator")) {
            out += "electrical system generator bus battery power"
            out += "generator troubleshooting voltage current indication"
        }
        if (hasAny("apu", "auxiliary")) {
            out += "auxiliary power unit apu start troubleshooting"
            out += "apu fuel ignition start motor indication"
        }
        if (hasAny("warning", "caution", "precaucion", "advertencia", "aviso")) {
            out += "warning caution note procedure"
        }
        if (hasAny("torque", "torqueado", "apriete", "limite", "limit")) {
            out += "torque limit tolerance inspection"
        }
        if (hasAny("rotor", "blade", "pala", "hub", "mgb", "transmission")) {
            out += "main rotor blade hub transmission inspection"
        }
        if (hasAny("afcs", "sas", "stability", "flight", "control")) {
            out += "flight control afcs sas servo actuator"
        }

        return out.distinct().take(4)
    }

    private val replacements = listOf(
        "falla" to "failure",
        "fallo" to "failure",
        "no prende" to "does not start",
        "no arranca" to "does not start",
        "arranque" to "start",
        "encendido" to "start",
        "apagado" to "shutdown",
        "procedimiento" to "procedure",
        "inspeccion" to "inspection",
        "revision" to "inspection",
        "presion" to "pressure",
        "bomba" to "pump",
        "valvula" to "valve",
        "fuga" to "leak",
        "combustible" to "fuel",
        "hidraulico" to "hydraulic",
        "hidraulica" to "hydraulic",
        "electrico" to "electrical",
        "generador" to "generator",
        "bateria" to "battery",
        "motor" to "engine",
        "transmision" to "transmission",
        "rotor principal" to "main rotor",
        "rotor de cola" to "tail rotor",
        "pala" to "blade",
        "advertencia" to "warning",
        "precaucion" to "caution",
        "nota" to "note",
        "limite" to "limit",
        "apriete" to "torque"
    )

    private val stopWords = setOf(
        "que", "como", "cuando", "donde", "cual", "cuales", "para", "por", "con", "sin",
        "del", "las", "los", "una", "uno", "unos", "unas", "este", "esta", "esto", "ese", "esa",
        "the", "and", "for", "with", "without", "from", "that", "this", "what", "how", "which",
        "uh", "black", "hawk", "uh60", "uh-60", "uh60l", "uh-60l"
    )
}
