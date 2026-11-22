package io.github.evgenykon.mybooks.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Footnote markers must survive parsing: the reader shows them in place and needs the anchor
 * to show the note text, otherwise a book with footnotes silently loses them.
 */
class FootnotesTest {

    private val SAMPLE = """
        <?xml version="1.0" encoding="utf-8"?>
        <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
          <description>
            <title-info>
              <book-title>С примечаниями</book-title>
              <lang>ru</lang>
            </title-info>
          </description>
          <body>
            <section id="s1">
              <p>Первый абзац с примечанием<a l:href="#n1" type="note">[1]</a> и продолжением текста.</p>
              <p>Второй абзац<a l:href="#n2" type="note">[2]</a>.</p>
            </section>
          </body>
          <body name="notes">
            <section id="n1">
              <title><p>1</p></title>
              <p>Текст первого примечания.</p>
            </section>
            <section id="n2">
              <title><p>2</p></title>
              <p>Текст второго примечания.</p>
            </section>
          </body>
        </FictionBook>
    """.trimIndent()

    @Test
    fun markerSpanPointsAtTheMarkerInsideTheText() {
        val book = Fb2Parser.parseText(SAMPLE)
        val paragraph = book.blocks.first { it.text.startsWith("Первый абзац") }
        val marker = paragraph.footnotes.single()

        assertEquals("n1", marker.targetId)
        assertEquals("[1]", paragraph.text.substring(marker.start, marker.end))
    }

    @Test
    fun everyMarkerIsKept() {
        val book = Fb2Parser.parseText(SAMPLE)
        val ids = book.blocks.flatMap { block -> block.footnotes.map { it.targetId } }
        assertEquals(listOf("n1", "n2"), ids)
    }

    @Test
    fun noteIndexResolvesTheNoteText() {
        val book = Fb2Parser.parseText(SAMPLE)
        val blockIndex = book.noteIndex["n1"]
        assertNotNull(blockIndex, "the notes body must be indexed by anchor id")

        val text = book.blocks.drop(blockIndex).take(3).joinToString(" ") { it.text }
        assertTrue(text.contains("Текст первого примечания"), "note text should be near the anchor: $text")
    }

    @Test
    fun notesBodyEndsUpAtTheEndOfTheBook() {
        val book = Fb2Parser.parseText(SAMPLE)
        val texts = book.blocks.map { it.text }
        val noteAt = texts.indexOfFirst { it.contains("Текст второго примечания") }
        val firstParagraphAt = texts.indexOfFirst { it.startsWith("Первый абзац") }
        assertTrue(noteAt > firstParagraphAt, "notes should follow the main text")
        assertTrue(texts.contains("Примечания"), "the notes section keeps its heading")
    }
}
