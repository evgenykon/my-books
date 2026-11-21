package io.github.evgenykon.mybooks.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.evgenykon.mybooks.core.BookNote
import io.github.evgenykon.mybooks.data.AndroidSecrets
import io.github.evgenykon.mybooks.data.AndroidVaultManager
import io.github.evgenykon.mybooks.data.BookRecord
import io.github.evgenykon.mybooks.data.LibraryRepository
import io.github.evgenykon.mybooks.BuildConfig
import io.github.evgenykon.mybooks.data.ReaderSettings
import io.github.evgenykon.mybooks.update.AvailableUpdate
import io.github.evgenykon.mybooks.update.UpdateChecker
import io.github.evgenykon.mybooks.update.UpdateInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryViewModel(
    private val repo: LibraryRepository,
    secrets: AndroidSecrets,
) : ViewModel() {

    /** Encrypted vaults in GitHub branches; every change is pushed to the active one. */
    private val _update = MutableStateFlow<AvailableUpdate?>(null)
    val update: StateFlow<AvailableUpdate?> = _update.asStateFlow()

    private val _updateProgress = MutableStateFlow<Float?>(null)
    val updateProgress: StateFlow<Float?> = _updateProgress.asStateFlow()

    val vault = AndroidVaultManager(
        library = repo,
        secrets = secrets,
        scope = viewModelScope,
        onLibraryChanged = { reloadFromDisk() },
    )

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

    init {
        // drop duplicates left over from earlier versions
        val unique = mutableListOf<BookRecord>()
        val seen = mutableSetOf<String>()
        for (book in _books.value) {
            if (book.hash.isNotEmpty() && !seen.add(book.hash)) {
                repo.deleteBook(book)
            } else {
                unique.add(book)
            }
        }
        if (unique.size != _books.value.size) {
            _books.value = unique
            repo.writeBooks(unique)
        }
    }

    fun importBook(uri: Uri, name: String?, loader: () -> ByteArray) {
        viewModelScope.launch {
            _importing.value = true
            try {
                val bytes = withContext(Dispatchers.IO) { loader() }
                val hash = LibraryRepository.sha256(bytes)
                val duplicate = _books.value.firstOrNull { it.hash.isNotEmpty() && it.hash == hash }
                if (duplicate != null) {
                    _message.value = "Книга уже в библиотеке: ${duplicate.title}"
                    return@launch
                }
                val record = repo.importBook(uri, name, bytes, hash)
                val updated = _books.value + record
                persist(updated)
                _message.value = "Добавлено: ${record.title}"
                vault.scheduleSync()
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
        vault.scheduleSync()
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
        vault.scheduleSync()
    }

    /** Asks GitHub for the newest release; the result is shown as a banner in the library. */
    fun checkForUpdates() {
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { UpdateChecker.check(BuildConfig.VERSION_NAME) }.getOrNull()
            }
            _update.value = found
        }
    }

    /** Downloads the release APK and opens the system installer. */
    fun installUpdate(context: android.content.Context) {
        val update = _update.value ?: return
        if (!UpdateInstaller.canInstall(context)) {
            _updateProgress.value = null
            UpdateInstaller.requestInstallPermission(context)
            _message.value = "Разрешите установку из этого приложения и нажмите «Обновить» ещё раз"
            return
        }
        viewModelScope.launch {
            _updateProgress.value = 0f
            try {
                val file = UpdateInstaller.download(context, update.apkUrl, update.apkSize) { progress ->
                    _updateProgress.value = progress
                }
                _updateProgress.value = null
                UpdateInstaller.install(context, file)
            } catch (e: Throwable) {
                _updateProgress.value = null
                _message.value = "Не удалось скачать обновление: ${e.message}"
            }
        }
    }

    fun dismissUpdate() {
        _update.value = null
        _updateProgress.value = null
    }

    fun deleteNote(note: BookNote) {
        _notes.value = _notes.value.filterNot { it.id == note.id }
        repo.writeNotes(_notes.value)
        vault.scheduleSync()
    }

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        repo.writeSettings(next)
        vault.scheduleSync()
    }

    /** Re-reads the library after a vault was pulled from GitHub. */
    fun reloadFromDisk() {
        repo.clearCache()
        _books.value = repo.readBooks().sortedByDescending { it.lastOpenedAt }
        _notes.value = repo.readNotes()
        _settings.value = repo.readSettings()
    }

    private fun persist(books: List<BookRecord>) {
        _books.value = books.sortedByDescending { it.lastOpenedAt }
        repo.writeBooks(_books.value)
    }
}
