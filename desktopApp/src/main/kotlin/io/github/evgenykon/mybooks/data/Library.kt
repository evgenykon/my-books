package io.github.evgenykon.mybooks.data

import io.github.evgenykon.mybooks.core.Book
import io.github.evgenykon.mybooks.core.BookNote
import io.github.evgenykon.mybooks.core.Fb2Parser
import io.github.evgenykon.mybooks.core.Position
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** One book of the local library; [fileName] is the stored copy of the FB2 file. */
@Serializable
data class BookRecord(
    val id: String,
    val title: String,
    val author: String,
    val fileName: String,
    val blockIndex: Int = 0,
    val charOffset: Int = 0,
    val percent: Int = 0,
    val totalChars: Int = 0,
    val addedAt: Long = 0L,
    val lastOpenedAt: Long = 0L,
    val blockCount: Int = 0,
    val hash: String = "",
) {
    val position: Position get() = Position(blockIndex, charOffset)
}

/** Reader appearance, stored as JSON next to the library. */
@Serializable
data class ReaderSettings(
    val fontFamily: Int = 0,
    val fontSize: Int = 19,
    val lineHeight: Float = 1.45f,
    val marginH: Int = 36,
    val marginV: Int = 28,
    val theme: Int = 0,
    val justified: Boolean = true,
)

@Serializable
private data class LibraryFile(val books: List<BookRecord> = emptyList())

@Serializable
private data class NotesFile(val notes: List<BookNote> = emptyList())

/**
 * Local library of the desktop client: one JSON manifest, one FB2 file per book,
 * plus notes and reader settings. Everything lives in the user's app data
 * directory, so no system-wide storage is touched.
 */
class LibraryRepository(val dir: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val booksFile = File(dir, "books.json")
    private val notesFile = File(dir, "notes.json")
    private val settingsFile = File(dir, "reader-settings.json")

    private val cache = LinkedHashMap<String, Book>()

    init {
        dir.mkdirs()
    }

    fun readBooks(): List<BookRecord> = runCatching {
        if (!booksFile.exists()) emptyList()
        else json.decodeFromString(LibraryFile.serializer(), booksFile.readText()).books
    }.getOrDefault(emptyList())

    fun writeBooks(books: List<BookRecord>) {
        booksFile.writeText(json.encodeToString(LibraryFile.serializer(), LibraryFile(books)))
    }

    fun readNotes(): List<BookNote> = runCatching {
        if (!notesFile.exists()) emptyList()
        else json.decodeFromString(NotesFile.serializer(), notesFile.readText()).notes
    }.getOrDefault(emptyList())

    fun writeNotes(notes: List<BookNote>) {
        notesFile.writeText(json.encodeToString(NotesFile.serializer(), NotesFile(notes)))
    }

    fun readSettings(): ReaderSettings = runCatching {
        if (!settingsFile.exists()) ReaderSettings()
        else json.decodeFromString(ReaderSettings.serializer(), settingsFile.readText())
    }.getOrDefault(ReaderSettings())

    fun writeSettings(settings: ReaderSettings) {
        settingsFile.writeText(json.encodeToString(ReaderSettings.serializer(), settings))
    }

    fun bookFile(record: BookRecord): File = File(dir, record.fileName)

    /** Already parsed book, if it was loaded during this session. */
    fun booksSnapshot(record: BookRecord): Book? = cache[record.id]

    suspend fun loadBook(record: BookRecord, force: Boolean = false): Book = withContext(Dispatchers.IO) {
        if (!force) cache[record.id]?.let { return@withContext it }
        val file = bookFile(record)
        val book = if (file.exists()) {
            Fb2Parser.parseBytes(file.readBytes())
        } else {
            Book(
                title = record.title,
                authors = listOf(record.author),
                annotation = null,
                blocks = emptyList(),
                toc = emptyList(),
            )
        }
        cache[record.id] = book
        book
    }

    suspend fun importBook(sourceName: String, bytes: ByteArray, hash: String = ""): BookRecord =
        withContext(Dispatchers.IO) {
            val book = Fb2Parser.parseBytes(bytes)
            val id = UUID.randomUUID().toString()
            val name = "$id.fb2"
            File(dir, name).writeBytes(bytes)
            cache[id] = book
            BookRecord(
                id = id,
                title = book.title,
                author = book.authorLine,
                fileName = name,
                percent = 0,
                totalChars = book.totalChars,
                addedAt = System.currentTimeMillis(),
                blockCount = book.blocks.size,
                hash = hash.ifEmpty { sha256(bytes) },
            )
        }

    fun deleteBook(record: BookRecord) {
        bookFile(record).delete()
        cache.remove(record.id)
    }

    companion object {
        /** Default storage location: %LOCALAPPDATA%\MyBooks\library. */
        fun default(): LibraryRepository {
            val base = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                ?.let { File(it, "MyBooks") }
                ?: File(System.getProperty("user.home"), ".mybooks")
            return LibraryRepository(File(base, "library"))
        }

        /** SHA-256 of the file contents; identifies duplicates. */
        fun sha256(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) sb.append(String.format("%02x", b))
            return sb.toString()
        }
    }
}
