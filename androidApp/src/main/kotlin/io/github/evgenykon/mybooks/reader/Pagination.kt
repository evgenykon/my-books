package io.github.evgenykon.mybooks.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import io.github.evgenykon.mybooks.core.Block
import io.github.evgenykon.mybooks.core.BlockKind

/** A chunk of one block that fits on a page. */
data class PageLine(
    val blockIndex: Int,
    val text: String,
    val offset: Int,
    val kind: BlockKind,
)

data class ReaderPage(val lines: List<PageLine>) {
    val firstBlockIndex: Int get() = lines.firstOrNull()?.blockIndex ?: 0
    val firstOffset: Int get() = lines.firstOrNull()?.offset ?: 0
}

/**
 * Splits the book into pages of a fixed pixel size using the real text layout.
 *
 * Must run on the composition/main thread: [TextMeasurer] is not thread safe.
 */
fun paginate(
    blocks: List<Block>,
    pageWidthPx: Int,
    pageHeightPx: Int,
    measurer: TextMeasurer,
    styleFor: (BlockKind) -> TextStyle,
    paragraphSpacingPx: Float,
    emptyLinePx: Float,
): List<ReaderPage> {
    if (pageWidthPx <= 0 || pageHeightPx <= 0) return emptyList()

    val pages = ArrayList<ReaderPage>()
    var current = ArrayList<PageLine>()
    var used = 0f

    fun commit() {
        if (current.isNotEmpty()) {
            pages.add(ReaderPage(current.toList()))
            current = ArrayList()
            used = 0f
        }
    }

    for (block in blocks) {
        if (block.kind == BlockKind.IMAGE) continue
        val text = block.text
        if (text.isEmpty()) {
            if (used + emptyLinePx > pageHeightPx) commit() else used += emptyLinePx
            continue
        }
        var offset = 0
        var firstChunk = true
        while (offset < text.length) {
            val slice = text.substring(offset)
            val layout = measurer.measure(
                text = AnnotatedString(slice),
                style = styleFor(block.kind),
                constraints = Constraints(maxWidth = pageWidthPx),
                maxLines = Int.MAX_VALUE,
                softWrap = true,
            )
            val lineCount = layout.lineCount
            var fit = 0
            var added = 0f
            var i = 0
            while (i < lineCount) {
                val lineHeight = layout.getLineBottom(i) - layout.getLineTop(i)
                if (used + added + lineHeight > pageHeightPx) break
                added += lineHeight
                fit++
                i++
            }
            if (fit == 0) {
                if (current.isNotEmpty()) {
                    commit()
                    continue
                }
                fit = 1
                added = layout.getLineBottom(0) - layout.getLineTop(0)
            }
            val endChar = if (fit >= lineCount) text.length else offset + layout.getLineStart(fit)
            current.add(PageLine(block.index, text.substring(offset, endChar), offset, block.kind))
            used += added
            if (firstChunk) used += paragraphSpacingPx
            firstChunk = false
            offset = endChar
            if (offset < text.length) commit()
        }
    }
    commit()
    if (pages.isEmpty()) pages.add(ReaderPage(emptyList()))
    return pages
}

/** Index of the page that shows the given block (first page starting at or after it). */
fun pageIndexOfBlock(pages: List<ReaderPage>, blockIndex: Int): Int {
    var result = 0
    for (i in pages.indices) {
        val first = pages[i].lines.firstOrNull() ?: continue
        if (first.blockIndex <= blockIndex) result = i else break
    }
    return result
}
