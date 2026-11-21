package io.github.evgenykon.mybooks.ui

import io.github.evgenykon.mybooks.core.BookNote
import io.github.evgenykon.mybooks.core.Position
import io.github.evgenykon.mybooks.core.ReadingProgress
import io.github.evgenykon.mybooks.data.BookRecord
import io.github.evgenykon.mybooks.data.LibraryRepository
import io.github.evgenykon.mybooks.data.ReaderSettings
import io.github.evgenykon.mybooks.data.VaultManager
import io.github.evgenykon.mybooks.secret.WindowsSecrets
import io.github.evgenykon.mybooks.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Holds library state for the Compose UI. Desktop has no AndroidX ViewModel, so this is a
 * plain state holder working on the Swing (main) dispatcher.
 */
class AppState(val repo: LibraryRepository) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Encrypted vaults in GitHub branches; every library change is pushed to the active one. */
    val vault = VaultManager(
        library = repo,
        secrets = WindowsSecrets(File(repo.dir.parentFile ?: repo.dir, "secrets.dat")),
        scope = scope,
        onLibraryChanged = { reloadFromDisk() },
    )

    private val _books = MutableStateFlow(dedupe(repo.readBooks()))
    val books: StateFlow<List<BookRecord>> = _books.asStateFlow()

    private val _settings = MutableStateFlow(repo.readSettings())
    val settings: StateFlow<ReaderSettings> = _settings.asStateFlow()

    private val _notes = MutableStateFlow(repo.readNotes())
    val notes: StateFlow<List<BookNote>> = _notes.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    fun showMessage(text: String) {
        _message.value = text
    }

    /** Imports FB2 files dropped onto the window, picked in the dialog or passed on the command line. */
    fun importFiles(files: List<File>, onDone: (BookRecord?) -> Unit = {}) {
        if (files.isEmpty()) return
        scope.launch {
            _importing.value = true
            var last: BookRecord? = null
            try {
                for (file in files) {
                    runCatching { addFile(file) }
                        .onSuccess { record -> last = record ?: last }
                        .onFailure { error ->
                            AppLog.error("import of '${file.name}' failed", error)
                            _message.value =
                                "Не удалось открыть «${file.name}»: ${error.message ?: "неизвестная ошибка"}"
                        }
                }
            } finally {
                _importing.value = false
            }
            onDone(last)
        }
    }

    private suspend fun addFile(file: File): BookRecord? {
        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
        val hash = LibraryRepository.sha256(bytes)
        val duplicate = _books.value.firstOrNull { it.hash.isNotEmpty() && it.hash == hash }
        if (duplicate != null) {
            _message.value = "Книга уже в библиотеке: ${duplicate.title}"
            return duplicate
        }
        val record = repo.importBook(file.name, bytes, hash)
        persist(_books.value + record)
        _message.value = "Добавлено: ${record.title}"
        vault.scheduleSync()
        return record
    }

    fun delete(record: BookRecord) {
        repo.deleteBook(record)
        persist(_books.value.filterNot { it.id == record.id })
        val remaining = repo.readNotes().filterNot { it.bookId == record.id }
        _notes.value = remaining
        repo.writeNotes(remaining)
        vault.scheduleSync()
    }

    fun markOpened(record: BookRecord) {
        val updated = _books.value.map {
            if (it.id == record.id) it.copy(lastOpenedAt = System.currentTimeMillis()) else it
        }
        persist(updated)
    }

    fun updatePosition(record: BookRecord, blockIndex: Int, charOffset: Int, percent: Int) {
        val updated = _books.value.map {
            if (it.id == record.id) {
                it.copy(
                    blockIndex = blockIndex,
                    charOffset = charOffset,
                    percent = percent,
                    lastOpenedAt = System.currentTimeMillis(),
                )
            } else {
                it
            }
        }
        persist(updated)
        vault.scheduleSync()
    }

    fun progressPercent(record: BookRecord, blockIndex: Int): Int {
        val book = repo.booksSnapshot(record) ?: return record.percent
        return ReadingProgress.percent(book, Position(blockIndex, 0))
    }

    fun addNote(bookId: String, blockIndex: Int, quote: String, text: String) {
        val note = BookNote(
            id = UUID.randomUUID().toString(),
            bookId = bookId,
            blockIndex = blockIndex,
            quote = quote,
            text = text,
            createdAt = System.currentTimeMillis(),
        )
        _notes.value = _notes.value + note
        repo.writeNotes(_notes.value)
        vault.scheduleSync()
    }

    fun deleteNote(note: BookNote) {
        _notes.value = _notes.value.filterNot { it.id == note.id }
        repo.writeNotes(_notes.value)
        vault.scheduleSync()
    }

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) = setSettings(transform(_settings.value))

    fun setSettings(settings: ReaderSettings) {
        _settings.value = settings
        repo.writeSettings(settings)
        vault.scheduleSync()
    }

    /** Re-reads the local library after a vault was pulled from GitHub. */
    fun reloadFromDisk() {
        _books.value = dedupe(repo.readBooks())
        _notes.value = repo.readNotes()
        _settings.value = repo.readSettings()
    }

    private fun persist(books: List<BookRecord>) {
        _books.value = books.sortedByDescending { it.lastOpenedAt }
        repo.writeBooks(_books.value)
    }

    private fun dedupe(books: List<BookRecord>): List<BookRecord> {
        val unique = mutableListOf<BookRecord>()
        val seen = mutableSetOf<String>()
        for (book in books) {
            if (book.hash.isNotEmpty() && !seen.add(book.hash)) {
                repo.deleteBook(book)
            } else {
                unique.add(book)
            }
        }
        if (unique.size != books.size) repo.writeBooks(unique)
        return unique.sortedByDescending { it.lastOpenedAt }
    }
}
