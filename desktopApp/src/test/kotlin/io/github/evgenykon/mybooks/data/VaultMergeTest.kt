package io.github.evgenykon.mybooks.data

import io.github.evgenykon.mybooks.core.BookNote
import io.github.evgenykon.mybooks.vault.VaultBook
import io.github.evgenykon.mybooks.vault.VaultManifest
import io.github.evgenykon.mybooks.vault.VaultNote
import io.github.evgenykon.mybooks.vault.VaultSettings
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reading progress has to move between devices in both directions: the merge keeps the side
 * that read last, never drops a local book or note, and takes the vault's settings only when
 * they are not older than the local ones.
 */
class VaultMergeTest {

    private fun library(): LibraryRepository {
        val dir = File(System.getProperty("java.io.tmpdir"), "mybooks-merge-${System.nanoTime()}")
        dir.mkdirs()
        return LibraryRepository(dir)
    }

    private fun record(id: String, percent: Int, openedAt: Long) = BookRecord(
        id = id,
        title = "Book $id",
        author = "Author",
        fileName = "$id.fb2",
        percent = percent,
        lastOpenedAt = openedAt,
        hash = "hash-$id",
    )

    private fun vaultBook(id: String, percent: Int, openedAt: Long) = VaultBook(
        id = id,
        title = "Book $id",
        author = "Author",
        fileName = "$id.fb2",
        percent = percent,
        lastOpenedAt = openedAt,
        hash = "hash-$id",
    )

    @Test
    fun newerRemotePositionWins() {
        val lib = library()
        lib.writeBooks(listOf(record("a", percent = 5, openedAt = 100)))

        mergeVaultIntoLibrary(
            lib,
            VaultManifest(books = listOf(vaultBook("a", percent = 42, openedAt = 500))),
        ) { null }

        assertEquals(42, lib.readBooks().single().percent)
    }

    @Test
    fun newerLocalPositionIsKept() {
        val lib = library()
        lib.writeBooks(listOf(record("a", percent = 77, openedAt = 900)))

        mergeVaultIntoLibrary(
            lib,
            VaultManifest(books = listOf(vaultBook("a", percent = 3, openedAt = 100))),
        ) { null }

        assertEquals(77, lib.readBooks().single().percent)
    }

    @Test
    fun bookOnlyInVaultIsDownloaded() {
        val lib = library()

        mergeVaultIntoLibrary(
            lib,
            VaultManifest(books = listOf(vaultBook("c", percent = 0, openedAt = 10))),
        ) { book -> "fb2-${book.id}".toByteArray() }

        val stored = lib.readBooks().single()
        assertEquals("c", stored.id)
        assertTrue(File(lib.dir, "c.fb2").exists(), "the blob must be written to the library")
    }

    @Test
    fun localBooksAndNotesSurvive() {
        val lib = library()
        lib.writeBooks(listOf(record("local", percent = 1, openedAt = 5)))
        lib.writeNotes(listOf(BookNote(id = "n-local", bookId = "local", blockIndex = 0, quote = "", text = "моё")))

        mergeVaultIntoLibrary(
            lib,
            VaultManifest(
                books = emptyList(),
                notes = listOf(VaultNote(id = "n-remote", bookId = "x", blockIndex = 1, quote = "q", text = "чужое")),
            ),
        ) { null }

        assertEquals(listOf("local"), lib.readBooks().map { it.id })
        assertEquals(setOf("n-local", "n-remote"), lib.readNotes().map { it.id }.toSet())
    }

    @Test
    fun vaultWinsForTheSameNote() {
        val lib = library()
        lib.writeNotes(listOf(BookNote(id = "n", bookId = "b", blockIndex = 0, quote = "old", text = "old")))

        mergeVaultIntoLibrary(
            lib,
            VaultManifest(notes = listOf(VaultNote(id = "n", bookId = "b", blockIndex = 0, quote = "new", text = "new"))),
        ) { null }

        assertEquals("new", lib.readNotes().single().text)
    }

    @Test
    fun settingsComeFromTheVaultWhenLocalIsNotNewer() {
        val lib = library()
        lib.writeSettings(ReaderSettings())

        mergeVaultIntoLibrary(
            lib,
            VaultManifest(
                updatedAt = System.currentTimeMillis() + 60_000,
                settings = VaultSettings(fontSize = 31, theme = 2),
            ),
        ) { null }

        val settings = lib.readSettings()
        assertEquals(31, settings.fontSize)
        assertEquals(2, settings.theme)
    }
}
