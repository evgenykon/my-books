package io.github.evgenykon.mybooks

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import io.github.evgenykon.mybooks.data.LibraryRepository
import io.github.evgenykon.mybooks.ui.AppState
import io.github.evgenykon.mybooks.ui.LibraryScreen
import io.github.evgenykon.mybooks.ui.ReaderScreen
import io.github.evgenykon.mybooks.ui.VaultScreen
import io.github.evgenykon.mybooks.util.AppLog
import java.awt.FileDialog
import java.awt.Frame
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.io.File

fun main(args: Array<String>) {
    val repo = LibraryRepository.default()
    AppLog.init(repo.dir.parentFile ?: repo.dir)
    AppLog.installCrashHandler()
    AppLog.info("args=${args.joinToString(" ")}")

    val state = AppState(repo)

    application {
        val windowState = rememberWindowState(
            size = DpSize(1180.dp, 820.dp),
            position = WindowPosition(Alignment.Center),
        )
        var openBookId by remember { mutableStateOf<String?>(null) }
        var showVaults by remember { mutableStateOf(false) }

        val books by state.books.collectAsState()
        val settings by state.settings.collectAsState()
        val notes by state.notes.collectAsState()
        val importing by state.importing.collectAsState()
        val message by state.message.collectAsState()
        val vaultState by state.vault.state.collectAsState()

        val openBook = books.firstOrNull { it.id == openBookId }
        val windowTitle = openBook?.let { "${it.title} — My Books" } ?: "My Books"

        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = windowTitle,
        ) {
            val window = this.window

            MenuBar {
                Menu("Файл", mnemonic = 'Ф') {
                    Item(
                        "Добавить книгу…",
                        onClick = { state.importFiles(listOfNotNull(openFb2Dialog(window))) { rec -> openBookId = rec?.id } },
                        shortcut = KeyShortcut(Key.O, ctrl = true),
                    )
                    Separator()
                    Item("Библиотека", onClick = { openBookId = null }, shortcut = KeyShortcut(Key.B, ctrl = true))
                    Item(
                        "Хранилища…",
                        onClick = {
                            showVaults = true
                            state.vault.refreshVaults()
                        },
                        shortcut = KeyShortcut(Key.H, ctrl = true),
                    )
                    Item("Выход", onClick = ::exitApplication)
                }
                Menu("Помощь", mnemonic = 'П') {
                    Item(
                        "Открыть папку с логами",
                        onClick = {
                            val dir = AppLog.directory ?: return@Item
                            runCatching { java.awt.Desktop.getDesktop().open(dir) }
                        },
                    )
                    Separator()
                    Item(
                        "О программе",
                        onClick = {
                            state.showMessage(
                                "My Books — читалка FB2 для Windows, Android и macOS. " +
                                    "Библиотека: ${repo.dir.absolutePath}",
                            )
                        },
                    )
                }
            }

            // A book passed on the command line (file association / "Open with").
            LaunchedEffect(Unit) {
                val path = args.firstOrNull() ?: return@LaunchedEffect
                val file = File(path)
                if (file.isFile) {
                    state.importFiles(listOf(file)) { rec -> openBookId = rec?.id }
                }
            }

            // Drag & drop of .fb2 files onto the window.
            DisposableEffect(window) {
                val target = object : DropTarget() {
                    override fun dragEnter(dtde: DropTargetDragEvent) {
                        dtde.acceptDrag(DnDConstants.ACTION_COPY)
                    }

                    override fun dragOver(dtde: DropTargetDragEvent) {
                        dtde.acceptDrag(DnDConstants.ACTION_COPY)
                    }

                    override fun drop(evt: DropTargetDropEvent) {
                        evt.acceptDrop(DnDConstants.ACTION_COPY)
                        val files = runCatching {
                            @Suppress("UNCHECKED_CAST")
                            (evt.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>)
                        }.getOrDefault(emptyList())
                        val fb2 = files.filter { it.isFile && it.name.endsWith(".fb2", ignoreCase = true) }
                        if (fb2.isNotEmpty()) {
                            state.importFiles(fb2) { rec -> openBookId = rec?.id }
                            evt.dropComplete(true)
                        } else {
                            state.showMessage("Перетащите файл с расширением .fb2")
                            evt.dropComplete(false)
                        }
                    }
                }
                window.dropTarget = target
                onDispose { window.dropTarget = null }
            }

            MyBooksTheme {
                if (showVaults) {
                    VaultScreen(
                        state = vaultState,
                        onBack = { showVaults = false },
                        onSaveSettings = state.vault::saveSettings,
                        onRefresh = state.vault::refreshVaults,
                        onCreate = state.vault::createVault,
                        onOpen = state.vault::openVault,
                        onSync = state.vault::syncNow,
                        onCloseVault = state.vault::closeVault,
                        onDelete = state.vault::deleteVault,
                        onConsumeMessage = state.vault::consumeMessage,
                        onOpenTokenPage = { openUrl(vaultTokenUrl(vaultState.owner)) },
                    )
                } else if (openBook == null) {
                    LibraryScreen(
                        books = books,
                        importing = importing,
                        message = message,
                        onConsumeMessage = state::consumeMessage,
                        onOpenBook = { openBookId = it.id },
                        onDeleteBook = state::delete,
                        onImportClick = {
                            state.importFiles(listOfNotNull(openFb2Dialog(window))) { rec ->
                                openBookId = rec?.id
                            }
                        },
                        onOpenVaults = {
                            showVaults = true
                            state.vault.refreshVaults()
                        },
                        activeVault = vaultState.active,
                    )
                } else {
                    ReaderScreen(
                        record = openBook,
                        repo = repo,
                        state = state,
                        settings = settings,
                        notes = notes.filter { it.bookId == openBook.id },
                        onSettingsChange = state::setSettings,
                        onBack = { openBookId = null },
                    )
                }
            }
        }
    }
}

@Composable
internal fun MyBooksTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF8A6D3B),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFF0E6D6),
            onPrimaryContainer = Color(0xFF3B3229),
            background = Color(0xFFF7F7F5),
            onBackground = Color(0xFF1A1A1A),
            surface = Color(0xFFFDFDFB),
            onSurface = Color(0xFF1A1A1A),
            surfaceVariant = Color(0xFFEDEBE6),
            onSurfaceVariant = Color(0xFF5F5B54),
        ),
        content = content,
    )
}

/** Pre-filled page for creating a fine-grained token with Contents: write. */
private fun vaultTokenUrl(owner: String): String {
    val target = owner.ifBlank { "evgenykon" }
    return "https://github.com/settings/personal-access-tokens/new" +
        "?name=MyBooks&description=My+Books+vault&expires_in=90" +
        "&target_name=" + target + "&permissions%5Bcontents%5D=write"
}

private fun openUrl(url: String) {
    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
}

/** Native file dialog limited to FB2 files. */
private fun openFb2Dialog(parent: Frame?): File? {
    val dialog = FileDialog(parent, "Выберите книгу FB2", FileDialog.LOAD)
    dialog.file = "*.fb2"
    dialog.setFilenameFilter { _, name -> name.endsWith(".fb2", ignoreCase = true) }
    dialog.isVisible = true
    val dir = dialog.directory
    val name = dialog.file
    return if (dir != null && name != null) File(dir, name) else null
}
