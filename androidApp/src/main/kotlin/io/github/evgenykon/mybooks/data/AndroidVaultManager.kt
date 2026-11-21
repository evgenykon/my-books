package io.github.evgenykon.mybooks.data

import io.github.evgenykon.mybooks.core.BookNote
import io.github.evgenykon.mybooks.vault.GitHubVaultClient
import io.github.evgenykon.mybooks.vault.VaultBook
import io.github.evgenykon.mybooks.vault.VaultInfo
import io.github.evgenykon.mybooks.vault.VaultManifest
import io.github.evgenykon.mybooks.vault.VaultNote
import io.github.evgenykon.mybooks.vault.VaultRepository
import io.github.evgenykon.mybooks.vault.VaultSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** State of the vault screen. */
data class VaultUiState(
    val configured: Boolean = false,
    val owner: String = "",
    val repo: String = "",
    val tokenSaved: Boolean = false,
    val vaults: List<VaultInfo> = emptyList(),
    val active: String? = null,
    val busy: Boolean = false,
    val busyText: String? = null,
    val message: String? = null,
    val error: String? = null,
)

/**
 * Encrypted vaults in GitHub branches for the Android client.
 *
 * Same format as the desktop and macOS clients: branch per vault, `vault.enc` with the
 * manifest and `books/<id>.enc` per book, PBKDF2 + AES-256-GCM.
 */
class AndroidVaultManager(
    private val library: LibraryRepository,
    private val secrets: AndroidSecrets,
    private val scope: CoroutineScope,
    private val onLibraryChanged: () -> Unit,
) {
    private val _state = MutableStateFlow(
        VaultUiState(
            configured = secrets.isConfigured(),
            owner = secrets.owner,
            repo = secrets.repo,
            tokenSaved = secrets.token.isNotEmpty(),
        ),
    )
    val state: StateFlow<VaultUiState> = _state.asStateFlow()

    private var password: CharArray? = null
    private var syncJob: Job? = null

    private fun repository(): VaultRepository =
        VaultRepository(GitHubVaultClient(secrets.token, secrets.owner, secrets.repo))

    fun consumeMessage() {
        _state.update { it.copy(message = null, error = null) }
    }

    fun saveSettings(token: String, owner: String, repo: String) {
        secrets.token = token
        secrets.owner = owner
        secrets.repo = repo
        _state.update {
            it.copy(
                configured = secrets.isConfigured(),
                owner = secrets.owner,
                repo = secrets.repo,
                tokenSaved = secrets.token.isNotEmpty(),
                message = "Настройки GitHub сохранены",
            )
        }
        refreshVaults()
    }

    fun refreshVaults() {
        if (!secrets.isConfigured()) return
        run("Читаю ветки…") {
            val names = withContext(Dispatchers.IO) { repository().listVaults() }
            _state.update { st ->
                st.copy(
                    vaults = names.map {
                        VaultInfo(name = it, passwordSaved = secrets.password(it) != null)
                    },
                )
            }
        }
    }

    fun createVault(name: String, password: String, confirmation: String) {
        val cleaned = name.trim()
        run("Создаю ветку «$cleaned»…") {
            if (password.length < 6) throw IllegalArgumentException("Пароль должен быть не короче 6 символов")
            if (password != confirmation) throw IllegalArgumentException("Пароли не совпадают")

            val repo = repository()
            val manifest = buildManifest(cleaned)
            val books = withContext(Dispatchers.IO) {
                manifest.books.associate { it.id to library.bookFile(recordOf(it)).readBytes() }
            }
            val saved = withContext(Dispatchers.IO) {
                repo.createVault(cleaned, password.toCharArray(), manifest, books)
            }

            secrets.savePassword(cleaned, password)
            this@AndroidVaultManager.password = password.toCharArray()
            _state.update {
                it.copy(
                    active = cleaned,
                    message = "Хранилище «$cleaned» создано: ${saved.books.size} книг, ${saved.notes.size} заметок",
                )
            }
            refreshVaults()
        }
    }

    fun openVault(name: String, password: String?) {
        run("Открываю «$name»…") {
            val pw = password?.takeIf { it.isNotBlank() }
                ?: secrets.password(name)
                ?: throw IllegalArgumentException("Введите пароль хранилища «$name»")

            val repo = repository()
            val manifest = withContext(Dispatchers.IO) { repo.openVault(name, pw.toCharArray()) }.manifest

            val local = library.readBooks().associateBy { it.id }.toMutableMap()
            for (book in manifest.books) {
                val existing = local[book.id]
                if (existing != null) {
                    local[book.id] = existing.copy(
                        blockIndex = book.blockIndex,
                        charOffset = book.charOffset,
                        percent = book.percent,
                        lastOpenedAt = book.lastOpenedAt,
                    )
                    continue
                }
                val bytes = withContext(Dispatchers.IO) { repo.readBook(name, pw.toCharArray(), book) } ?: continue
                withContext(Dispatchers.IO) { library.writeBookFile(book.fileName, bytes) }
                local[book.id] = BookRecord(
                    id = book.id,
                    title = book.title,
                    author = book.author,
                    fileName = book.fileName,
                    blockIndex = book.blockIndex,
                    charOffset = book.charOffset,
                    percent = book.percent,
                    totalChars = book.totalChars,
                    addedAt = book.addedAt,
                    lastOpenedAt = book.lastOpenedAt,
                    blockCount = book.blockCount,
                    hash = book.hash,
                )
            }

            withContext(Dispatchers.IO) {
                library.writeBooks(local.values.toList())
                library.writeNotes(manifest.notes.map { it.toNote() })
                library.writeSettings(manifest.settings.toReaderSettings())
                library.clearCache()
            }

            secrets.savePassword(name, pw)
            this@AndroidVaultManager.password = pw.toCharArray()
            onLibraryChanged()
            _state.update {
                it.copy(
                    active = name,
                    message = "Хранилище «$name» открыто: ${manifest.books.size} книг, ${manifest.notes.size} заметок",
                    vaults = it.vaults.map { v -> if (v.name == name) v.copy(passwordSaved = true) else v },
                )
            }
        }
    }

    fun closeVault() {
        password?.fill(' ')
        password = null
        _state.update { it.copy(active = null, message = "Хранилище закрыто") }
    }

    fun deleteVault(name: String) {
        run("Удаляю ветку «$name»…") {
            withContext(Dispatchers.IO) { repository().deleteVault(name) }
            secrets.forgetPassword(name)
            if (_state.value.active == name) closeVault()
            _state.update { it.copy(message = "Ветка «$name» удалена") }
            refreshVaults()
        }
    }

    /** Pushes the library to the active vault shorty after the last change. */
    fun scheduleSync() {
        if (_state.value.active == null) return
        syncJob?.cancel()
        syncJob = scope.launch {
            delay(1_500)
            push()
        }
    }

    fun syncNow() {
        scope.launch { push() }
    }

    private suspend fun push() {
        val vault = _state.value.active ?: return
        val pw = password ?: return
        val repo = repository()

        _state.update { it.copy(busy = true, busyText = "Синхронизирую «$vault»…", error = null) }
        try {
            val manifest = buildManifest(vault)
            val remote = withContext(Dispatchers.IO) { repo.openVault(vault, pw) }.manifest
            val remoteById = remote.books.associateBy { it.id }

            val changed = linkedMapOf<String, ByteArray>()
            for (book in manifest.books) {
                val known = remoteById[book.id]
                if (known == null || known.hash != book.hash) {
                    val file = library.bookFile(recordOf(book))
                    if (file.exists()) changed[book.id] = withContext(Dispatchers.IO) { file.readBytes() }
                }
            }
            val removed = remote.books.map { it.id }.filterNot { id -> manifest.books.any { it.id == id } }

            val saved = withContext(Dispatchers.IO) { repo.saveVault(vault, pw, manifest, changed, removed) }
            _state.update {
                it.copy(
                    busy = false,
                    busyText = null,
                    message = "Синхронизировано: ${saved.books.size} книг, ${saved.notes.size} заметок",
                )
            }
        } catch (e: Throwable) {
            _state.update {
                it.copy(busy = false, busyText = null, error = "Синхронизация не удалась: ${e.message}")
            }
        }
    }

    private fun buildManifest(name: String): VaultManifest {
        val settings = library.readSettings()
        return VaultManifest(
            version = 1,
            name = name,
            updatedAt = System.currentTimeMillis(),
            books = library.readBooks().map { r ->
                VaultBook(
                    id = r.id,
                    title = r.title,
                    author = r.author,
                    fileName = r.fileName,
                    blockIndex = r.blockIndex,
                    charOffset = r.charOffset,
                    percent = r.percent,
                    totalChars = r.totalChars,
                    addedAt = r.addedAt,
                    lastOpenedAt = r.lastOpenedAt,
                    blockCount = r.blockCount,
                    hash = r.hash,
                )
            },
            notes = library.readNotes().map { n ->
                VaultNote(
                    id = n.id,
                    bookId = n.bookId,
                    blockIndex = n.blockIndex,
                    quote = n.quote,
                    text = n.text,
                    createdAt = n.createdAt,
                )
            },
            settings = VaultSettings(
                fontFamily = settings.fontFamily,
                fontSize = settings.fontSize,
                lineHeight = settings.lineHeight,
                marginH = settings.marginH,
                marginV = settings.marginV,
                theme = settings.theme,
                justified = settings.justified,
            ),
        )
    }

    private fun recordOf(book: VaultBook) = BookRecord(
        id = book.id,
        title = book.title,
        author = book.author,
        fileName = book.fileName,
        blockIndex = book.blockIndex,
        charOffset = book.charOffset,
        percent = book.percent,
        totalChars = book.totalChars,
        addedAt = book.addedAt,
        lastOpenedAt = book.lastOpenedAt,
        blockCount = book.blockCount,
        hash = book.hash,
    )

    private fun VaultNote.toNote() = BookNote(
        id = id,
        bookId = bookId,
        blockIndex = blockIndex,
        quote = quote,
        text = text,
        createdAt = createdAt,
    )

    private fun VaultSettings.toReaderSettings() = ReaderSettings(
        fontFamily = fontFamily,
        fontSize = fontSize,
        lineHeight = lineHeight,
        marginH = marginH,
        marginV = marginV,
        theme = theme,
        justified = justified,
    )

    private fun run(busyText: String, block: suspend () -> Unit) {
        scope.launch {
            _state.update { it.copy(busy = true, busyText = busyText, error = null, message = null) }
            try {
                block()
            } catch (e: Throwable) {
                _state.update { it.copy(error = e.message ?: "Ошибка") }
            } finally {
                _state.update { it.copy(busy = false, busyText = null) }
            }
        }
    }
}
