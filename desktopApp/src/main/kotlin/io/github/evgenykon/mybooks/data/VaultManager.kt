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
import io.github.evgenykon.mybooks.vault.VaultSnapshot
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

    init {
        val saved = secrets.read()
        val name = saved.activeVault.ifBlank { saved.passwords.keys.singleOrNull().orEmpty() }
        AppLog.info("startup: active='${saved.activeVault}' savedVaults=${saved.passwords.keys}")
        if (name.isNotBlank() && configured) {
            saved.passwords[name]?.let { pw ->
                password = pw.toCharArray()
                scope.launch { startupSync(name, pw) }
            }
        }
    }

    private val configured: Boolean
        get() = secrets.read().let { it.token.isNotEmpty() && it.owner.isNotEmpty() && it.repo.isNotEmpty() }

    private fun initialState(): VaultState {
        val saved = secrets.read()
        return VaultState(
            configured = saved.token.isNotEmpty() && saved.owner.isNotEmpty() && saved.repo.isNotEmpty(),
            owner = saved.owner,
            repo = saved.repo,
            tokenSaved = saved.token.isNotEmpty(),
            // The vault that was open last time comes back automatically; the startup check
            // verifies it and pulls whatever another device has pushed meanwhile.
            // Older builds did not remember the vault; fall back to the only saved one so the
            // startup check still has something to verify right after the update.
            active = (saved.activeVault.ifBlank { saved.passwords.keys.singleOrNull().orEmpty() })
                .takeIf { it.isNotBlank() && saved.passwords.containsKey(it) },
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
                secrets.saveActiveVault(cleaned)
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
            val snapshot = withContext(Dispatchers.IO) { repo.openVault(name, pw.toCharArray()) }
            withContext(Dispatchers.IO) { mergeFromVault(name, pw, snapshot) }

            secrets.savePassword(name, pw)
            secrets.saveActiveVault(name)
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


    /**
     * Startup: verify the vault binding and make both sides agree.
     *
     * Without this the app only pushed local changes: another device never saw them unless the
     * vault was reopened by hand, which is why reading progress on Android and Windows drifted
     * apart. Now the vault is pulled, merged (newer reading position wins per book, notes are
     * united by id, settings follow the newest writer) and only then pushed.
     */
    private suspend fun startupSync(name: String, pw: String) {
        AppLog.info("startup: checking vault '$name'")
        val repo = repository()
        _state.update { it.copy(busy = true, busyText = "Проверяю хранилище «$name»…") }
        try {
            if (!withContext(Dispatchers.IO) { repo.vaultExists(name) }) {
                _state.update {
                    it.copy(
                        busy = false,
                        busyText = null,
                        active = null,
                        error = "Ветка «$name» не найдена — хранилище отвязано",
                    )
                }
                secrets.saveActiveVault("")
                password?.fill(' ')
                password = null
                return
            }

            val snapshot = withContext(Dispatchers.IO) { repo.openVault(name, pw.toCharArray()) }
            withContext(Dispatchers.IO) { mergeFromVault(name, pw, snapshot) }
            onLibraryChanged()

            AppLog.info("startup: '$name' merged, pushing")
            _state.update { it.copy(busyText = "Отправляю изменения…") }
            push()
        } catch (e: Throwable) {
            AppLog.error("startup vault check failed", e)
            _state.update {
                it.copy(busy = false, busyText = null, error = "Хранилище «$name»: ${e.message}")
            }
        }
    }

    /** Merges the vault into the local library: nothing local is lost, newer wins. */
    private fun mergeFromVault(name: String, pw: String, snapshot: VaultSnapshot) {
        val repo = repository()
        mergeVaultIntoLibrary(library, snapshot.manifest) { book ->
            repo.readBook(name, pw.toCharArray(), book)
        }
    }

    fun closeVault() {
        password?.fill(' ')
        password = null
        secrets.saveActiveVault("")
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

    /** Two-way sync: pull what other devices wrote, merge, then push local changes. */
    fun syncNow() {
        val name = _state.value.active ?: return
        val pw = secrets.read().passwords[name] ?: return
        scope.launch { startupSync(name, pw) }
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

/**
 * Merges a vault manifest into the local library.
 *
 * Rules: a book that only the vault has is materialised locally (the blob is copied);
 * for a book both sides have, the side with the newer `lastOpenedAt` wins, so the reading
 * position follows the device that read last. Notes are united by id and the vault wins for
 * the same id (it is the shared copy). Reader settings are written from the vault only when
 * the local file is not newer than the manifest stamp.
 */
internal fun mergeVaultIntoLibrary(
    library: LibraryRepository,
    manifest: VaultManifest,
    readBookBlob: (VaultBook) -> ByteArray?,
) {
    val local = library.readBooks().associateBy { it.id }.toMutableMap()

    for (book in manifest.books) {
        val existing = local[book.id]
        if (existing == null) {
            val bytes = readBookBlob(book) ?: continue
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
        } else if (book.lastOpenedAt > existing.lastOpenedAt) {
            local[book.id] = existing.copy(
                blockIndex = book.blockIndex,
                charOffset = book.charOffset,
                percent = book.percent,
                lastOpenedAt = book.lastOpenedAt,
            )
        }
    }
    library.writeBooks(local.values.toList())

    val localNotes = library.readNotes().associateBy { it.id }
    val merged = localNotes + manifest.notes.map { it.toNote() }.associateBy { it.id }
    library.writeNotes(merged.values.toList())

    val settingsFile = File(library.dir, "reader-settings.json")
    val localIsNewer = settingsFile.exists() && settingsFile.lastModified() > manifest.updatedAt
    if (!localIsNewer) library.writeSettings(manifest.settings.toReaderSettings())
}

/** Vault note as the local model. */
internal fun VaultNote.toNote() = BookNote(
    id = id,
    bookId = bookId,
    blockIndex = blockIndex,
    quote = quote,
    text = text,
    createdAt = createdAt,
)

/** Vault settings as the local reader settings. */
internal fun VaultSettings.toReaderSettings() = ReaderSettings(
    fontFamily = fontFamily,
    fontSize = fontSize,
    lineHeight = lineHeight,
    marginH = marginH,
    marginV = marginV,
    theme = theme,
    justified = justified,
)
