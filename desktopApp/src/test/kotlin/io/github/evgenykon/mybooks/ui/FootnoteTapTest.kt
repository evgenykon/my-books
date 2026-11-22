package io.github.evgenykon.mybooks.ui

import io.github.evgenykon.mybooks.core.Book
import io.github.evgenykon.mybooks.core.Fb2Parser
import io.github.evgenykon.mybooks.reader.PageLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A footnote marker in the text must open the note it points at — otherwise a book with
 * footnotes loses them completely, which is what readers complained about.
 */
class FootnoteTapTest {

    private val sample = """
        <?xml version="1.0" encoding="utf-8"?>
        <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
          <description><title-info><book-title>Сноски</book-title><lang>ru</lang></title-info></description>
          <body>
            <section id="s1">
              <p>Первый абзац с примечанием<a l:href="#n1" type="note">[1]</a> и продолжением.</p>
            </section>
          </body>
          <body name="notes">
            <section id="n1"><title><p>1</p></title><p>Текст первого примечания.</p></section>
          </body>
        </FictionBook>
    """.trimIndent()

    private fun book(): Book = Fb2Parser.parseText(sample)

    private fun lineOf(book: Book): PageLine {
        val block = book.blocks.first { it.text.startsWith("Первый абзац") }
        return PageLine(
            blockIndex = block.index,
            kind = block.kind,
            text = block.text,
            isFirstOfBlock = true,
            isLastOfBlock = true,
            startInBlock = 0,
        )
    }

    @Test
    fun clickOnMarkerResolvesTheNoteText() {
        val book = book()
        val line = lineOf(book)
        val marker = book.blocks.first { it.text.startsWith("Первый абзац") }.footnotes.single()

        val popup = footnoteAt(book, listOf(line), Hit(0, marker.start + 1))

        assertNotNull(popup, "a click inside the marker must open a note")
        assertTrue(popup.text.contains("Текст первого примечания"), popup.text)
        assertEquals(book.noteIndex["n1"], popup.blockIndex)
    }

    @Test
    fun clickOnPlainTextDoesNothing() {
        val book = book()
        val line = lineOf(book)
        assertNull(footnoteAt(book, listOf(line), Hit(0, 0)))
    }

    @Test
    fun markerOutsideTheRenderedLineIsIgnored() {
        val book = book()
        val block = book.blocks.first { it.text.startsWith("Первый абзац") }
        val marker = block.footnotes.single()
        // A line that starts after the marker (second visual line of the paragraph).
        val line = PageLine(
            blockIndex = block.index,
            kind = block.kind,
            text = block.text.substring(marker.end).trimStart(),
            isFirstOfBlock = false,
            isLastOfBlock = true,
            startInBlock = block.text.indexOf(line2Text(block.text, marker.end)),
        )
        assertNull(footnoteAt(book, listOf(line), Hit(0, 0)))
    }

    private fun line2Text(text: String, from: Int): String = text.substring(from).trimStart()
}
