package io.github.evgenykon.mybooks.data

import io.github.evgenykon.mybooks.core.BookNote
import io.github.evgenykon.mybooks.secret.WindowsSecrets
import io.github.evgenykon.mybooks.util.AppLog
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
import java.io.File

/** What the vault screen shows. */
data class VaultState(
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
 * Encrypted vaults stored in git branches of a GitHub repository.
 *
 * A vault holds the whole library: books (one encrypted blob each), notes, reading progress
 * and reader settings. Every change of the library is pushed to the active vault shortly
 * after it happens; opening a vault downloads its content into the local library.
 */
class VaultManager(
    private val library: LibraryRepository,
    private val secrets: WindowsSecrets,
    private val scope: CoroutineScope,
    private val onLibraryChanged: () -> Unit = {},
) {
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<VaultState> = _state.asStateFlow()

    private var password: CharArray? = null
    private var syncJob: Job? = null

    private val configured: Boolean
        get() = secrets.read().let { it.token.isNotEmpty() && it.owner.isNotEmpty() && it.repo.isNotEmpty() }

    private fun initialState(): VaultState {
        val saved = secrets.read()
        return VaultState(
            configured = saved.token.isNotEmpty() && saved.owner.isNotEmpty() && saved.repo.isNotEmpty(),
            owner = saved.owner,
            repo = saved.repo,
            tokenSaved = saved.token.isNotEmpty(),
        )
    }

    private fun client(): GitHubVaultClient {
        val saved = secrets.read()
        return GitHubVaultClient(saved.token, saved.owner, saved.repo)
    }

    private fun repository(): VaultRepository = VaultRepository(client())

    fun consumeMessage() = _state.update { it.copy(message = null, error = null) }

    // ------------------------------------------------------------ settings

    fun saveSettings(token: String, owner: String, repo: String) {
        secrets.saveToken(token, owner, repo)
        _state.update {
            it.copy(
                configured = token.isNotBlank() && owner.isNotBlank() && repo.isNotBlank(),
                owner = owner.trim(),
                repo = repo.trim(),
                tokenSaved = token.isNotBlank(),
                message = "Настройки GitHub сохранены",
            )
        }
        refreshVaults()
    }

    // ------------------------------------------------------------ vault list

    fun refreshVaults() {
        if (!configured) return
        scope.launch {
            _state.update { it.copy(busy = true, busyText = "Читаю ветки…") }
            try {
                val names = withContext(Dispatchers.IO) { repository().listVaults() }
                val saved = secrets.read()
                val infos = names.map { name -> VaultInfo(name = name, passwordSaved = saved.passwords.containsKey(name)) }
                _state.update { it.copy(busy = false, busyText = null, vaults = infos) }
            } catch (e: Throwable) {
                AppLog.error("listVaults failed", e)
                _state.update { it.copy(busy = false, busyText = null, error = e.message ?: "Не удалось прочитать ветки") }
            }
        }
    }

    // ------------------------------------------------------------ create / open

    fun createVault(name: String, password: String, confirmation: String) {
        val cleaned = name.trim()
        io("Создаю ветку «$cleaned»…") {
            run {
                if (password.length < 6) throw IllegalArgumentException("Пароль должен быть не короче 6 символов")
                if (password != confirmation) throw IllegalArgumentException("Пароли не совпадают")
                if (_state.value.vaults.any { it.name == cleaned }) {
                    throw IllegalArgumentException("Ветка «$cleaned» уже существует")
                }

                val repo = repository()
                val manifest = buildManifest(cleaned)
                val books = manifest.books.associate { it.id to library.bookFile(recordOf(it)).readBytes() }
                val saved = repo.createVault(cleaned, password.toCharArray(), manifest, books)

                password.toCharArray().let { secrets.savePassword(cleaned, password) }
                this@VaultManager.password = password.toCharArray()
                _state.update {
                    it.copy(
                        active = cleaned,
                        message = "Хранилище «$cleaned» создано: ${saved.books.size} книг, ${saved.notes.size} заметок",
                    )
                }
                refreshVaultsInternal()
            }
        }
    }

    fun openVault(name: String, password: String?) {
        io("Открываю «$name»…") {
            val saved = secrets.read()
            val pw = password?.takeIf { it.isNotBlank() }
                ?: saved.passwords[name]
                ?: throw IllegalArgumentException("Введите пароль хранилища «$name»")

            val repo = repository()
            val snapshot = repo.openVault(name, pw.toCharArray())

            // Merge the vault into the local library: books we don't have are written to disk,
            // notes and settings come from the vault (vault wins, it is the shared copy).
            val local = library.readBooks().associateBy { it.id }.toMutableMap()
            for (book in snapshot.manifest.books) {
                if (local.containsKey(book.id)) {
                    local[book.id] = local.getValue(book.id).copy(
                        blockIndex = book.blockIndex,
                        charOffset = book.charOffset,
                        percent = book.percent,
                        lastOpenedAt = book.lastOpenedAt,
                    )
                    continue
                }
                val bytes = repo.readBook(name, pw.toCharArray(), book) ?: continue
                File(library.dir, book.fileName).writeBytes(bytes)
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

            library.writeBooks(local.values.toList())
            library.writeNotes(snapshot.manifest.notes.map { it.toNote() })
            library.writeSettings(snapshot.manifest.settings.toReaderSettings())

            secrets.savePassword(name, pw)
            this@VaultManager.password = pw.toCharArray()
            onLibraryChanged()
            _state.update {
                it.copy(
                    active = name,
                    message = "Хранилище «$name» открыто: ${snapshot.manifest.books.size} книг, " +
                        "${snapshot.manifest.notes.size} заметок",
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
        io("Удаляю ветку «$name»…") {
            repository().deleteVault(name)
            secrets.forgetPassword(name)
            if (_state.value.active == name) closeVault()
            _state.update { it.copy(message = "Ветка «$name» удалена") }
            refreshVaultsInternal()
        }
    }

    // ------------------------------------------------------------ sync

    /** Pushes the library to the active vault a second after the last change. */
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

        _state.update { it.copy(busy = true, busyText = "Синхронизирую «$vault»…") }
        try {
            val manifest = withContext(Dispatchers.IO) { buildManifest(vault) }
            val remote = withContext(Dispatchers.IO) { repo.openVault(vault, pw) }.manifest
            val remoteIds = remote.books.associateBy { it.id }

            val changed = linkedMapOf<String, ByteArray>()
            for (book in manifest.books) {
                val known = remoteIds[book.id]
                if (known == null || known.hash != book.hash) {
                    val file = library.bookFile(recordOf(book))
                    if (file.exists()) changed[book.id] = withContext(Dispatchers.IO) { file.readBytes() }
                }
            }
            val removed = remote.books.map { it.id }.filterNot { id -> manifest.books.any { it.id == id } }

            val saved = withContext(Dispatchers.IO) {
                repo.saveVault(vault, pw, manifest, changed, removed)
            }
            _state.update {
                it.copy(
                    busy = false,
                    busyText = null,
                    message = "Синхронизировано: ${saved.books.size} книг, ${saved.notes.size} заметок",
                    vaults = it.vaults.map { v ->
                        if (v.name == vault) {
                            v.copy(books = saved.books.size, notes = saved.notes.size, updatedAt = saved.updatedAt)
                        } else {
                            v
                        }
                    },
                )
            }
        } catch (e: Throwable) {
            _state.update {
                it.copy(busy = false, busyText = null, error = "Синхронизация не удалась: ${e.message}")
            }
        }
    }

    private fun buildManifest(name: String): VaultManifest {
        val records = library.readBooks()
        val settings = library.readSettings()
        return VaultManifest(
            version = 1,
            name = name,
            updatedAt = System.currentTimeMillis(),
            books = records.map { r ->
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

    private fun refreshVaultsInternal() {
        refreshVaults()
    }

    private fun io(busyText: String, block: suspend () -> Unit) {
        scope.launch {
            _state.update { it.copy(busy = true, busyText = busyText, error = null, message = null) }
            try {
                block()
            } catch (e: Throwable) {
                AppLog.error("vault operation failed: $busyText", e)
                _state.update { it.copy(error = e.message ?: "Ошибка") }
            } finally {
                _state.update { it.copy(busy = false, busyText = null) }
            }
        }
    }

}
