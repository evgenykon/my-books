package io.github.evgenykon.mybooks.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VaultCryptoTest {

    private val password = "correct horse battery staple".toCharArray()
    private val iterations = 1_000

    @Test
    fun `round trip restores the payload`() {
        val payload = "Война и мир — Лев Толстой".toByteArray(Charsets.UTF_8)
        val sealed = VaultCrypto.encrypt(payload, password, iterations)
        val opened = VaultCrypto.decrypt(sealed, password)
        assertEquals(String(payload, Charsets.UTF_8), String(opened, Charsets.UTF_8))
    }

    @Test
    fun `wrong password fails authentication`() {
        val sealed = VaultCrypto.encrypt("secret".toByteArray(), password, iterations)
        assertFailsWith<Exception> { VaultCrypto.decrypt(sealed, "wrong password".toCharArray()) }
    }

    @Test
    fun `envelope is randomized and keeps parameters`() {
        val payload = "same".toByteArray()
        val first = VaultCrypto.encrypt(payload, password, iterations)
        val second = VaultCrypto.encrypt(payload, password, iterations)
        assertTrue(!first.contentEquals(second))
        val text = first.toString(Charsets.UTF_8)
        assertTrue(text.contains("PBKDF2-HMAC-SHA256"), text)
        assertTrue(text.contains("AES-256-GCM"), text)
        assertEquals(iterations, VaultCrypto.iterationsOf(first))
    }

    @Test
    fun `vault names are limited to latin digits and dash`() {
        assertEquals(null, VaultNames.validate("home-2026"))
        assertTrue(VaultNames.validate("Дом") != null)
        assertTrue(VaultNames.validate("home_lib") != null)
        assertTrue(VaultNames.validate("main") != null)
        assertTrue(VaultNames.validate(" trailing") == null)
        assertTrue(VaultNames.validate("-start") != null)
        assertTrue(VaultNames.validate("two--dash") != null)
    }

    @Test
    fun `manifest survives encryption`() {
        val manifest = VaultManifest(
            name = "home",
            books = listOf(
                VaultBook(id = "b1", title = "Кибериада", author = "Станислав Лем", fileName = "b1.fb2", percent = 42),
            ),
            notes = listOf(VaultNote(id = "n1", bookId = "b1", blockIndex = 3, quote = "цитата", text = "заметка")),
            settings = VaultSettings(fontSize = 21, theme = 2),
        )
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val bytes = json.encodeToString(VaultManifest.serializer(), manifest).toByteArray()
        val sealed = VaultCrypto.encrypt(bytes, password, iterations)
        val restored = json.decodeFromString(
            VaultManifest.serializer(),
            VaultCrypto.decrypt(sealed, password).toString(Charsets.UTF_8),
        )
        assertEquals(manifest, restored)
        assertEquals("books/b1.enc", restored.books.first().blobPath)
    }
}
