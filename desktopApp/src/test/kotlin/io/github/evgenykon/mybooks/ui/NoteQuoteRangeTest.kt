package io.github.evgenykon.mybooks.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Quoted text must be found in the block again to underline it on the page. */
class NoteQuoteRangeTest {

    private val text = "Ну, князь, Генуя и Лукка стали не больше как поместьями фамилии Бонапарте."

    @Test
    fun findsExactQuote() {
        val range = quoteRangeIn(text, "Генуя и Лукка")
        assertEquals("Генуя и Лукка", text.substring(range!!.first, range.last + 1))
    }

    @Test
    fun findsQuoteCapturedAcrossLines() {
        // A note saved from two visual lines holds a space where the line break was.
        val range = quoteRangeIn(text, "поместьями  фамилии Бонапарте")
        assertEquals("поместьями фамилии Бонапарте", text.substring(range!!.first, range.last + 1))
    }

    @Test
    fun missingQuoteGivesNull() {
        assertNull(quoteRangeIn(text, "этой фразы в тексте нет"))
    }

    @Test
    fun blankQuoteGivesNull() {
        assertNull(quoteRangeIn(text, "   "))
    }
}
