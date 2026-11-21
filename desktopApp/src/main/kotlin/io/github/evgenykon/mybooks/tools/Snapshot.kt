package io.github.evgenykon.mybooks.tools

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.evgenykon.mybooks.MyBooksTheme
import io.github.evgenykon.mybooks.core.BlockKind
import io.github.evgenykon.mybooks.core.BookNote
import io.github.evgenykon.mybooks.core.TocEntry
import io.github.evgenykon.mybooks.data.BookRecord
import io.github.evgenykon.mybooks.data.ReaderSettings
import io.github.evgenykon.mybooks.ui.LibraryScreen
import io.github.evgenykon.mybooks.ui.NotesPanel
import io.github.evgenykon.mybooks.ui.ReaderPalette
import io.github.evgenykon.mybooks.ui.SettingsPanel
import io.github.evgenykon.mybooks.ui.TocPanel
import io.github.evgenykon.mybooks.ui.VaultScreen
import io.github.evgenykon.mybooks.ui.paletteOf
import io.github.evgenykon.mybooks.ui.styleFor
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Renders the screens and the reader side panels to PNG files without opening a window.
 *
 * Run with `gradle :desktopApp:snapshot`; images land in `desktopApp/build/snapshots`.
 * Handy for checking layouts in CI or while iterating on the reader appearance
 * (the macOS client has a similar tool in `macos/Tools/Snapshot.swift`).
 */
fun main() {
    val out = File("build/snapshots").apply { mkdirs() }
    val settings = ReaderSettings()
    val palette = paletteOf(settings)

    render(out, "library-empty", 1160, 760) {
        LibraryScreen(
            books = emptyList(),
            importing = false,
            message = null,
            onConsumeMessage = {},
            onOpenBook = {},
            onDeleteBook = {},
            onImportClick = {},
        )
    }

    render(out, "library-books", 1160, 760) {
        LibraryScreen(
            books = demoBooks,
            importing = false,
            message = null,
            onConsumeMessage = {},
            onOpenBook = {},
            onDeleteBook = {},
            onImportClick = {},
        )
    }

    render(out, "vaults", 1160, 900) {
        VaultScreen(
            state = io.github.evgenykon.mybooks.data.VaultState(
                configured = true,
                owner = "evgenykon",
                repo = "my-books",
                tokenSaved = true,
                active = "home-2026",
                vaults = listOf(
                    io.github.evgenykon.mybooks.vault.VaultInfo("home-2026", books = 7, notes = 23, updatedAt = 1762500000000L, passwordSaved = true),
                    io.github.evgenykon.mybooks.vault.VaultInfo("work", books = 2, notes = 4, updatedAt = 1762400000000L, passwordSaved = false),
                    io.github.evgenykon.mybooks.vault.VaultInfo("phone", passwordSaved = false),
                ),
            ),
            onBack = {},
            onSaveSettings = { _, _, _ -> },
            onRefresh = {},
            onCreate = { _, _, _ -> },
            onOpen = { _, _ -> },
            onSync = {},
            onCloseVault = {},
            onDelete = {},
            onConsumeMessage = {},
        )
    }

    render(out, "reader-toc", 1160, 760) {
        ReaderPreview(settings) {
            TocPanel(
                toc = demoToc,
                currentBlock = 20,
                palette = palette,
                onSelect = {},
                onClose = {},
            )
        }
    }

    render(out, "reader-notes", 1160, 760) {
        ReaderPreview(settings) {
            NotesPanel(
                notes = demoNotes,
                palette = palette,
                onSelect = {},
                onDelete = {},
                onClose = {},
            )
        }
    }

    render(out, "reader-settings", 1160, 900) {
        ReaderPreview(settings) {
            SettingsPanel(
                settings = settings,
                palette = palette,
                onChange = {},
                onClose = {},
            )
        }
    }

    println("snapshots written to " + out.absolutePath)
}

/** A page of the book with an optional side panel on the right. */
@Composable
private fun ReaderPreview(settings: ReaderSettings, panel: @Composable () -> Unit) {
    val palette = paletteOf(settings)
    Surface(Modifier.fillMaxSize(), color = palette.background) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Text(
                    text = "Война и мир. Том первый   ·   Лев Николаевич Толстой   ·   стр. 2 из 5   ·   17%",
                    style = styleFor(BlockKind.SUBTITLE, settings, palette),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
                )
                PageMock(settings, palette)
            }
            Surface(
                modifier = Modifier.align(Alignment.CenterEnd).width(400.dp).fillMaxHeight(),
                color = palette.surface,
                shadowElevation = 12.dp,
            ) {
                panel()
            }
        }
    }
}

@Composable
private fun PageMock(settings: ReaderSettings, palette: ReaderPalette) {
    Column(Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 28.dp)) {
        Text("Война и мир Том первый", style = styleFor(BlockKind.HEADING1, settings, palette))
        Text(
            "— Eh bien, mon prince. Gênes et Lucques ne sont plus que des apanages, " +
                "des поместья, de la famille Bonaparte.",
            style = styleFor(BlockKind.EPIGRAPH, settings, palette),
        )
        Text("Анна Павловна Шерер", style = styleFor(BlockKind.EPIGRAPH_AUTHOR, settings, palette))
        Text("Часть первая", style = styleFor(BlockKind.HEADING2, settings, palette))
        Text(
            "Ну, князь, Генуя и Лукка стали не больше как поместьями фамилии Бонапарте. " +
                "Нет, я вас предупреждаю, что, ежели вы мне не скажете, что у нас война, " +
                "ежели вы всё ещё будете защищать все гадости и ужасы этого антихриста, " +
                "я вас больше не знаю, вы уж не друг мой.",
            style = styleFor(BlockKind.PARAGRAPH, settings, palette),
        )
        Text(
            "Анна Павловна, пожилая фрейлина и приближенная императрицы Марии Федоровны, " +
                "встретила князя Василия с грустной, но твердой улыбкой.",
            style = styleFor(BlockKind.PARAGRAPH, settings, palette),
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun render(out: File, name: String, width: Int, height: Int, content: @Composable () -> Unit) {
    val scene = ImageComposeScene(width = width, height = height, density = Density(1f, 1f)) {
        MyBooksTheme { content() }
    }
    try {
        val image = scene.render()
        val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("cannot encode " + name)
        File(out, name + ".png").writeBytes(data.bytes)
        println("  " + name + ".png")
    } finally {
        scene.close()
    }
}

private val demoBooks = listOf(
    BookRecord(
        id = "1",
        title = "Война и мир. Том первый",
        author = "Лев Николаевич Толстой",
        fileName = "1.fb2",
        percent = 12,
        blockIndex = 40,
        totalChars = 5748,
        addedAt = 1762500000000L,
        lastOpenedAt = 1762500000000L,
    ),
    BookRecord(
        id = "2",
        title = "Кибериада",
        author = "Станислав Лем",
        fileName = "2.fb2",
        percent = 47,
        totalChars = 900000,
        addedAt = 1762000000000L,
        lastOpenedAt = 1762100000000L,
    ),
    BookRecord(
        id = "3",
        title = "Мастер и Маргарита",
        author = "Михаил Булгаков",
        fileName = "3.fb2",
        percent = 100,
        totalChars = 700000,
        addedAt = 1761000000000L,
        lastOpenedAt = 1761000000000L,
    ),
)

private val demoToc = listOf(
    TocEntry("Война и мир Том первый", 0, 1),
    TocEntry("Часть первая", 4, 2),
    TocEntry("Глава I", 6, 3),
    TocEntry("Глава II", 18, 3),
    TocEntry("Глава III", 33, 3),
    TocEntry("Часть вторая", 47, 2),
    TocEntry("Глава I", 49, 3),
    TocEntry("Примечания", 120, 1),
)

private val demoNotes = listOf(
    BookNote(
        id = "n1",
        bookId = "1",
        blockIndex = 7,
        quote = "— Eh bien, mon prince. Gênes et Lucques ne sont plus que des apanages.",
        text = "Сравнить перевод с французским оригиналом.",
        createdAt = 1762500000000L,
    ),
    BookNote(
        id = "n2",
        bookId = "1",
        blockIndex = 19,
        quote = "Ну, князь, Генуя и Лукка стали не больше как поместьями фамилии Бонапарте.",
        text = "Ключевая сцена вечера у Шерер.",
        createdAt = 1762600000000L,
    ),
)
