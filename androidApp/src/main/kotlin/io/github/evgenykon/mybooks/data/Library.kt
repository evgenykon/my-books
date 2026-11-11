package io.github.evgenykon.mybooks.data

import android.content.Context
import android.net.Uri
import io.github.evgenykon.mybooks.core.Block
import io.github.evgenykon.mybooks.core.Book
import io.github.evgenykon.mybooks.core.BookNote
import io.github.evgenykon.mybooks.core.Fb2Parser
import io.github.evgenykon.mybooks.core.Position
import io.github.evgenykon.mybooks.core.ReadingProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

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

@Serializable
data class ReaderSettings(
    val fontFamily: Int = 0,
    val fontSize: Int = 18,
    val lineHeight: Float = 1.45f,
    val marginH: Int = 20,
    val marginV: Int = 14,
    val theme: Int = 0,
    val justified: Boolean = true,
)

@Serializable
private data class LibraryFile(val books: List<BookRecord> = emptyList())

@Serializable
private data class NotesFile(val notes: List<BookNote> = emptyList())

/**
 * Local library: one JSON manifest + one FB2 file per book, plus notes and reader
 * settings. Files live in the app's private storage so no storage permission is needed.
 */
class LibraryRepository(context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val dir = File(context.filesDir, "library").apply { mkdirs() }
    private val booksFile = File(dir, "books.json")
    private val notesFile = File(dir, "notes.json")
    private val settingsFile = File(dir, "reader-settings.json")

    private val cache = LinkedHashMap<String, Book>()

    fun readBooks(): List<BookRecord> = runCatching {
        if (!booksFile.exists()) emptyList()
        else json.decodeFromString<LibraryFile>(booksFile.readText()).books
    }.getOrDefault(emptyList())

    fun writeBooks(books: List<BookRecord>) {
        booksFile.writeText(json.encodeToString(LibraryFile.serializer(), LibraryFile(books)))
    }

    fun readNotes(): List<BookNote> = runCatching {
        if (!notesFile.exists()) emptyList()
        else json.decodeFromString<NotesFile>(notesFile.readText()).notes
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

    suspend fun loadBook(record: BookRecord, force: Boolean = false): Book = withContext(Dispatchers.IO) {
        if (!force) cache[record.id]?.let { return@withContext it }
        val file = bookFile(record)
        val book = if (file.exists()) {
            Fb2Parser.parseBytes(file.readBytes())
        } else {
            Book(title = record.title, authors = listOf(record.author), annotation = null, blocks = emptyList(), toc = emptyList())
        }
        cache[record.id] = book
        book
    }

    companion object {
        /** SHA-256 of the file contents; identifies duplicates. */
        fun sha256(bytes: ByteArray): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) sb.append(String.format("%02x", b))
            return sb.toString()
        }
    }

    suspend fun importBook(
        uri: Uri,
        displayName: String?,
        bytes: ByteArray,
        hash: String = "",
    ): BookRecord = withContext(Dispatchers.IO) {
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
            hash = hash,
        )
    }

    fun deleteBook(record: BookRecord) {
        bookFile(record).delete()
        cache.remove(record.id)
    }

    fun progressFor(book: Book, blockIndex: Int, charOffset: Int): Int =
        ReadingProgress.percent(book, Position(blockIndex, charOffset))

    fun blocksFor(book: Book, from: Int, count: Int): List<Block> =
        book.blocks.drop(from).take(count)
}
