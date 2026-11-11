package io.github.evgenykon.mybooks.core

import kotlinx.serialization.Serializable

/** Kind of a text block inside a parsed book. */
@Serializable
enum class BlockKind {
    PARAGRAPH,
    HEADING1,
    HEADING2,
    HEADING3,
    SUBTITLE,
    EPIGRAPH,
    EPIGRAPH_AUTHOR,
    POEM,
    ANNOTATION,
    EMPTY,
    IMAGE,
}

/**
 * One flat, ordered piece of the book text.
 *
 * The list of blocks is the canonical coordinate system for reading positions:
 * a position is a [blockIndex] plus an offset in characters inside that block.
 */
@Serializable
data class Block(
    val index: Int,
    val kind: BlockKind,
    val text: String,
    val href: String? = null,
)

/** Entry of the table of contents; [blockIndex] is where the heading lives. */
@Serializable
data class TocEntry(
    val title: String,
    val blockIndex: Int,
    val level: Int,
)

/** A parsed FB2 book, ready to be rendered by a platform UI. */
@Serializable
data class Book(
    val title: String,
    val authors: List<String>,
    val annotation: String?,
    val blocks: List<Block>,
    val toc: List<TocEntry>,
    val coverBase64: String? = null,
) {
    val authorLine: String
        get() = if (authors.isEmpty()) "Неизвестный автор" else authors.joinToString(", ")

    val totalChars: Int by lazy { blocks.sumOf { it.text.length } }
}

/** A reading position: block in the flat block list plus a character offset. */
@Serializable
data class Position(
    val blockIndex: Int,
    val charOffset: Int,
) {
    companion object {
        val START = Position(0, 0)
    }
}

/** Note attached to a line (block) of the book, optionally quoting a fragment. */
@Serializable
data class BookNote(
    val id: String,
    val bookId: String,
    val blockIndex: Int,
    val quote: String,
    val text: String,
    val createdAt: Long = 0L,
)

object ReadingProgress {
    /** Fraction of the book read, 0..1, derived from a position. */
    fun fraction(book: Book, position: Position): Float {
        val total = book.totalChars
        if (total <= 0) return 0f
        var before = 0
        for (b in book.blocks) {
            if (b.index < position.blockIndex) before += b.text.length
            else if (b.index == position.blockIndex) before += position.charOffset.coerceIn(0, b.text.length)
            else break
        }
        return (before.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

    fun percent(book: Book, position: Position): Int = (fraction(book, position) * 100f).toInt()
}
