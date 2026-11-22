package io.github.evgenykon.mybooks.ui

import io.github.evgenykon.mybooks.core.BlockKind
import io.github.evgenykon.mybooks.reader.PageLine
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The quote must be exactly what the reader selected, not the whole paragraph: the page
 * tracks the drag itself because Compose does not expose the selected text.
 */
class QuoteSelectionTest {

    private fun line(text: String) = PageLine(0, BlockKind.PARAGRAPH, text, true, true)

    private val lines = listOf(
        line("Ну, князь, Генуя и Лукка стали не больше как поместьями"),
        line("фамилии Бонапарте. Нет, я вас предупреждаю."),
    )

    @Test
    fun sameLineSelectionIsTrimmedToTheSelection() {
        val quote = quoteOf(PageSelection(Hit(0, 4), Hit(0, 10)), lines)
        assertEquals("князь,", quote)
    }

    @Test
    fun selectionAcrossLinesIsJoinedInReadingOrder() {
        val start = lines[0].text.indexOf("поместьями")
        val end = lines[1].text.indexOf("Бонапарте") + "Бонапарте".length
        val quote = quoteOf(PageSelection(Hit(0, start), Hit(1, end)), lines)
        assertEquals("поместьями фамилии Бонапарте", quote)
    }

    @Test
    fun backwardsDragStillProducesReadingOrder() {
        val start = lines[0].text.indexOf("поместьями")
        val end = lines[1].text.indexOf("Бонапарте") + "Бонапарте".length
        val forward = quoteOf(PageSelection(Hit(0, start), Hit(1, end)), lines)
        val backward = quoteOf(PageSelection(Hit(1, end), Hit(0, start)), lines)
        assertEquals("поместьями фамилии Бонапарте", forward)
        assertEquals(forward, backward)
    }

    @Test
    fun emptySelectionHasNoQuote() {
        assertEquals("", quoteOf(PageSelection(Hit(0, 5), Hit(0, 5)), lines))
        assertEquals("", quoteOf(null, lines))
    }

    @Test
    fun wholeLineIsPossibleWhenTheSelectionSpansIt() {
        val quote = quoteOf(PageSelection(Hit(1, 0), Hit(1, lines[1].text.length)), lines)
        assertEquals(lines[1].text, quote)
    }
}
