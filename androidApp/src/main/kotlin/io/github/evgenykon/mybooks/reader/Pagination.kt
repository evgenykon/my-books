package io.github.evgenykon.mybooks.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import io.github.evgenykon.mybooks.core.Block
import io.github.evgenykon.mybooks.core.BlockKind
import io.github.evgenykon.mybooks.data.ReaderSettings

/** One rendered line of the page. */
data class PageLine(
    val blockIndex: Int,
    val kind: BlockKind,
    val text: String,
    val isFirstOfBlock: Boolean,
    val isLastOfBlock: Boolean,
)

data class ReaderPage(val lines: List<PageLine>) {
    val firstBlockIndex: Int get() = lines.firstOrNull()?.blockIndex ?: 0
}

/** Sizes used both by the paginator and by the page renderer — they must match. */
data class BlockMetrics(
    val lineHeightPx: Float,
    val spaceBeforePx: Float,
    val spaceAfterPx: Float,
    val leadingIndentPx: Float,
)

fun metricsFor(kind: BlockKind, settings: ReaderSettings, density: Density): BlockMetrics {
    var scale = 1f
    var lineFactor = 1f
    var spaceBefore = 0f
    var spaceAfter = 0.85f
    var indent = 0f

    when (kind) {
        BlockKind.HEADING1 -> {
            scale = 1.5f; lineFactor = 1.2f; spaceBefore = 1.1f; spaceAfter = 0.6f
        }
        BlockKind.HEADING2 -> {
            scale = 1.28f; lineFactor = 1.15f; spaceBefore = 0.9f; spaceAfter = 0.45f
        }
        BlockKind.HEADING3 -> {
            scale = 1.12f; spaceBefore = 0.65f; spaceAfter = 0.35f
        }
        BlockKind.SUBTITLE -> {
            spaceBefore = 0.4f; spaceAfter = 0.35f
        }
        BlockKind.EPIGRAPH -> {
            scale = 0.92f; spaceBefore = 0.6f; spaceAfter = 0.15f; indent = 3.2f
        }
        BlockKind.EPIGRAPH_AUTHOR -> {
            scale = 0.88f; spaceAfter = 1.1f; indent = 3.2f
        }
        BlockKind.POEM -> {
            scale = 0.95f; spaceAfter = 0.2f; indent = 1.4f
        }
        else -> Unit
    }

    val sizePx = with(density) { (settings.fontSize * scale).sp.toPx() }
    val natural = sizePx * 1.25f
    val lineHeight = maxOf(natural, sizePx * settings.lineHeight * lineFactor)

    return BlockMetrics(
        lineHeightPx = lineHeight,
        spaceBeforePx = sizePx * spaceBefore,
        spaceAfterPx = sizePx * spaceAfter,
        leadingIndentPx = sizePx * indent,
    )
}

/** Paragraph indent as characters, since Compose text indent is not used here. */
private const val INDENT_PREFIX = "\u2003\u2002"

/**
 * Splits the book into pages of whole lines. The page renderer draws every line in a
 * box of exactly [BlockMetrics.lineHeightPx], so nothing is clipped or lost between pages.
 *
 * Must run on the composition thread: [TextMeasurer] is not thread safe.
 */
fun paginate(
    blocks: List<Block>,
    pageWidthPx: Int,
    pageHeightPx: Int,
    measurer: TextMeasurer,
    settings: ReaderSettings,
    density: Density,
    styleFor: (BlockKind) -> TextStyle,
): List<ReaderPage> {
    if (pageWidthPx <= 0 || pageHeightPx <= 0) return listOf(ReaderPage(emptyList()))

    val pages = ArrayList<ReaderPage>()
    var lines = ArrayList<PageLine>()
    var used = 0f

    fun flush() {
        if (lines.isNotEmpty()) {
            pages.add(ReaderPage(lines))
            lines = ArrayList()
        }
        used = 0f
    }

    val emptyLinePx = with(density) { settings.fontSize.sp.toPx() } * 0.6f

    for (block in blocks) {
        if (block.kind == BlockKind.IMAGE) continue
        val text = block.text

        if (text.isEmpty()) {
            if (used + emptyLinePx > pageHeightPx) flush() else used += emptyLinePx
            continue
        }

        val m = metricsFor(block.kind, settings, density)
        if (used + m.spaceBeforePx + m.lineHeightPx > pageHeightPx) flush()
        used += m.spaceBeforePx

        val body = if (block.kind == BlockKind.PARAGRAPH) INDENT_PREFIX + text else text
        val width = (pageWidthPx - m.leadingIndentPx).toInt().coerceAtLeast(40)
        val layout = measurer.measure(
            text = AnnotatedString(body),
            style = styleFor(block.kind),
            constraints = Constraints(maxWidth = width),
            maxLines = Int.MAX_VALUE,
            softWrap = true,
        )

        val blockLines = ArrayList<String>()
        for (i in 0 until layout.lineCount) {
            val start = layout.getLineStart(i)
            val end = layout.getLineEnd(i)
            val piece = body.substring(start, end).trimEnd()
            if (piece.isNotEmpty()) blockLines.add(piece)
        }
        if (blockLines.isEmpty()) blockLines.add(text)

        blockLines.forEachIndexed { index, lineText ->
            if (used + m.lineHeightPx > pageHeightPx) flush()
            lines.add(
                PageLine(
                    blockIndex = block.index,
                    kind = block.kind,
                    text = lineText,
                    isFirstOfBlock = index == 0,
                    isLastOfBlock = index == blockLines.lastIndex,
                )
            )
            used += m.lineHeightPx
            if (index == blockLines.lastIndex) used += m.spaceAfterPx
        }
    }

    flush()
    if (pages.isEmpty()) pages.add(ReaderPage(emptyList()))
    return pages
}

/** Index of the page that shows the given block. */
fun pageIndexOfBlock(pages: List<ReaderPage>, blockIndex: Int): Int {
    var result = 0
    for (i in pages.indices) {
        val first = pages[i].lines.firstOrNull() ?: continue
        if (first.blockIndex <= blockIndex) result = i else break
    }
    return result
}
