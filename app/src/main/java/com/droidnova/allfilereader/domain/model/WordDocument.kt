package com.droidnova.allfilereader.domain.model

data class WordRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val fontSizeSp: Float? = null,
    val colorArgb: Long? = null,
    val baseline: WordBaseline = WordBaseline.Normal
)

enum class WordBaseline { Normal, Superscript, Subscript }
enum class WordAlignment { Start, Center, End, Justify }

sealed interface WordBlock {
    val id: Long
    data class Paragraph(override val id: Long, val runs: List<WordRun>, val alignment: WordAlignment, val indentLevel: Int = 0) : WordBlock
    data class Heading(override val id: Long, val level: Int, val runs: List<WordRun>, val alignment: WordAlignment) : WordBlock
    data class ListItem(override val id: Long, val marker: String, val level: Int, val runs: List<WordRun>) : WordBlock
    data class Table(override val id: Long, val rows: List<List<String>>) : WordBlock
    data class Image(override val id: Long, val relationshipId: String, val description: String?) : WordBlock
}
