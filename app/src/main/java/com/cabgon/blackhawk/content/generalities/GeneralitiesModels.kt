package com.cabgon.blackhawk.content.generalities

/**
 * Generalidades OTA
 *
 * JSON:
 *  - sections[] -> blocks[]
 *  - Por ahora: blocks.type == "table"
 */
data class GeneralitiesManifest(
    val schema: Int,
    val generatedAt: String,
    val sections: List<GeneralitiesSection>
)

data class GeneralitiesSection(
    val id: String,
    val title: String,
    val order: Int,
    val blocks: List<GeneralitiesBlock>
)

sealed interface GeneralitiesBlock {
    val type: String
    val title: String?
}

data class GeneralitiesTableBlock(
    override val type: String = "table",
    override val title: String? = null,
    val columns: List<String>,
    val rows: List<List<String>>
) : GeneralitiesBlock
