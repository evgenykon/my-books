package io.github.evgenykon.mybooks.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val SAMPLE = """
<?xml version="1.0" encoding="utf-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
  <description>
    <title-info>
      <genre>sf</genre>
      <author><first-name>Лев</first-name><last-name>Толстой</last-name></author>
      <book-title>Война и мир. Том 1</book-title>
      <annotation><p>Роман-эпопея.</p></annotation>
      <coverpage><image l:href="#cover.jpg"/></coverpage>
      <lang>ru</lang>
    </title-info>
  </description>
  <body>
    <title><p>Война и мир</p></title>
    <section id="s1">
      <title><p>Часть первая</p></title>
      <p>— Eh bien, mon prince, Gênes et Lucques <emphasis>ne sont plus</emphasis> que des apanages.</p>
      <empty-line/>
      <p>Анна Павловна&nbsp;улыбнулась.</p>
      <section id="s1_1">
        <title><p>Глава I</p></title>
        <p>Ну, князь, Генуя и Лукка стали не больше как поместьями.</p>
        <poem><stanza><v>Строка первая</v><v>Строка вторая</v></stanza></poem>
        <image l:href="#pic1"/>
      </section>
    </section>
  </body>
  <body name="notes">
    <section id="n1"><title><p>1</p></title><p>Сноска.</p></section>
  </body>
  <binary id="cover.jpg" content-type="image/jpeg">AAAA</binary>
</FictionBook>
""".trimIndent()

class Fb2ParserTest {

    @Test
    fun parsesMetadata() {
        val book = Fb2Parser.parseText(SAMPLE)
        assertEquals("Война и мир. Том 1", book.title)
        assertEquals(listOf("Лев Толстой"), book.authors)
        assertEquals("Роман-эпопея.", book.annotation)
        assertEquals("AAAA", book.coverBase64)
    }

    @Test
    fun parsesBlocksAndToc() {
        val book = Fb2Parser.parseText(SAMPLE)
        assertTrue(book.blocks.size > 6, "expected several blocks, got ${book.blocks.size}")
        assertEquals(BlockKind.HEADING1, book.blocks[0].kind)
        assertEquals("Война и мир", book.blocks[0].text)
        assertTrue(book.blocks.any { it.text.contains("улыбнулась") }, "nbsp entity should decode")
        assertTrue(book.blocks.any { it.kind == BlockKind.IMAGE && it.href == "#pic1" })
        assertTrue(book.blocks.any { it.kind == BlockKind.POEM && it.text == "Строка первая" })

        val titles = book.toc.map { it.title }
        assertEquals(listOf("Война и мир", "Часть первая", "Глава I", "Примечания", "1"), titles)
        assertEquals(2, book.toc[2].level)
        for (entry in book.toc) {
            assertNotNull(book.blocks.getOrNull(entry.blockIndex), "toc index out of range: $entry")
        }
    }

    @Test
    fun blocksAreIndexed() {
        val book = Fb2Parser.parseText(SAMPLE)
        book.blocks.forEachIndexed { i, b -> assertEquals(i, b.index) }
    }

    @Test
    fun progressIsMonotonic() {
        val book = Fb2Parser.parseText(SAMPLE)
        val mid = book.blocks.size / 2
        val early = ReadingProgress.fraction(book, Position(0, 0))
        val middle = ReadingProgress.fraction(book, Position(mid, 0))
        val end = ReadingProgress.fraction(book, Position(book.blocks.size - 1, book.blocks.last().text.length))
        assertTrue(early < middle, "early=$early middle=$middle")
        assertTrue(middle < end, "middle=$middle end=$end")
        assertTrue(end <= 1f)
    }

    @Test
    fun decodesCp1251() {
        val cp1251 = intArrayOf(
            0x3C, 0x61, 0x3E, // <a>
            0xCF, 0xF0, 0xE8, 0xE2, 0xE5, 0xF2, // Привет
            0x3C, 0x2F, 0x61, 0x3E,
        ).map { it.toByte() }.toByteArray()
        val text = Charsets.decode(cp1251, "windows-1251")
        assertTrue(text.contains("Привет"), text)
    }

    @Test
    fun decodesUtf8WithBom() {
        val payload = "<p>Проверка</p>".encodeToByteArray()
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + payload
        val text = Charsets.decode(withBom)
        assertEquals("<p>Проверка</p>", text)
    }
}
