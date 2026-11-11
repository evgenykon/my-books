package io.github.evgenykon.mybooks.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.evgenykon.mybooks.core.BookNote
import io.github.evgenykon.mybooks.data.BookRecord
import io.github.evgenykon.mybooks.data.LibraryRepository
import io.github.evgenykon.mybooks.data.ReaderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryViewModel(private val repo: LibraryRepository) : ViewModel() {

    private val _books = MutableStateFlow(repo.readBooks().sortedByDescending { it.lastOpenedAt })
    val books: StateFlow<List<BookRecord>> = _books.asStateFlow()

    private val _settings = MutableStateFlow(repo.readSettings())
    val settings: StateFlow<ReaderSettings> = _settings.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    fun importBook(uri: Uri, name: String?, loader: () -> ByteArray) {
        viewModelScope.launch {
            _importing.value = true
            try {
                val bytes = withContext(Dispatchers.IO) { loader() }
                val record = repo.importBook(uri, name, bytes)
                val updated = _books.value + record
                persist(updated)
                _message.value = "Добавлено: ${record.title}"
            } catch (t: Throwable) {
                _message.value = "Не удалось открыть файл: ${t.message ?: "неизвестная ошибка"}"
            } finally {
                _importing.value = false
            }
        }
    }

    fun delete(record: BookRecord) {
        repo.deleteBook(record)
        persist(_books.value.filterNot { it.id == record.id })
        val notes = repo.readNotes().filterNot { it.bookId == record.id }
        repo.writeNotes(notes)
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
    }

    private val _notes = MutableStateFlow(repo.readNotes())
    val notes: StateFlow<List<BookNote>> = _notes.asStateFlow()

    fun addNote(bookId: String, blockIndex: Int, quote: String, text: String) {
        val note = BookNote(
            id = java.util.UUID.randomUUID().toString(),
            bookId = bookId,
            blockIndex = blockIndex,
            quote = quote,
            text = text,
            createdAt = System.currentTimeMillis(),
        )
        _notes.value = _notes.value + note
        repo.writeNotes(_notes.value)
    }

    fun deleteNote(note: BookNote) {
        _notes.value = _notes.value.filterNot { it.id == note.id }
        repo.writeNotes(_notes.value)
    }

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        repo.writeSettings(next)
    }

    private fun persist(books: List<BookRecord>) {
        _books.value = books.sortedByDescending { it.lastOpenedAt }
        repo.writeBooks(_books.value)
    }
}
