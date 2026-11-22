package io.github.evgenykon.mybooks.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.evgenykon.mybooks.data.BookRecord
import io.github.evgenykon.mybooks.update.AvailableUpdate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenBook: (BookRecord) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenVaults: () -> Unit = {},
    activeVault: String? = null,
    /** Vault work (create / open / sync / delete) runs in the background: show it here. */
    vaultBusy: Boolean = false,
    vaultBusyText: String? = null,
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val update by viewModel.update.collectAsStateWithLifecycle()
    val updateProgress by viewModel.updateProgress.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.checkForUpdates() }
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<BookRecord?>(null) }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        viewModel.consumeMessage()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.importBook(uri, null) { readAll(context, uri) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Библиотека") },
                actions = {
                    if (activeVault != null) {
                        Icon(
                            Icons.Filled.CloudDone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = onOpenVaults) {
                        Icon(Icons.Filled.CloudQueue, contentDescription = "Хранилища")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Tune, contentDescription = "Настройки")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(arrayOf("*/*")) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(if (importing) "Загрузка…" else "Добавить книгу") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (vaultBusy) {
                VaultBusyBar(vaultBusyText)
            }
            update?.let { available ->
                UpdateBanner(
                    update = available,
                    progress = updateProgress,
                    onInstall = { viewModel.installUpdate(context) },
                    onDismiss = viewModel::dismissUpdate,
                )
            }
        Box(Modifier.fillMaxSize()) {
            if (books.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Библиотека пуста",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Нажмите «Добавить книгу» и выберите файл FB2.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(books, key = { it.id }) { book ->
                        BookRow(
                            book = book,
                            onClick = { onOpenBook(book) },
                            onDelete = { pendingDelete = book },
                        )
                        HorizontalDivider()
                    }
                }
            }
            if (importing) {
                CircularProgressIndicator(Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
            }
        }
        }
    }

    pendingDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить книгу?") },
            text = { Text("«${book.title}» и все заметки к ней будут удалены.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(book)
                    pendingDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun BookRow(book: BookRecord, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = book.author,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { book.percent / 100f },
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = buildString {
                    append("Прочитано ${book.percent}%")
                    val opened = if (book.lastOpenedAt > 0) book.lastOpenedAt else book.addedAt
                    if (opened > 0) {
                        append(" · ")
                        append(SimpleDateFormat("d MMM yyyy", Locale("ru")).format(Date(opened)))
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Удалить")
        }
    }
}

private fun readAll(context: Context, uri: android.net.Uri): ByteArray {
    return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IllegalStateException("Не удалось прочитать файл")
}

/** Banner that reports a newer release and drives the self-update. */
@Composable
private fun UpdateBanner(
    update: AvailableUpdate,
    progress: Float?,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Доступно обновление ${update.version}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = if (progress != null) {
                            "Скачивание… ${(progress * 100).toInt()}%"
                        } else {
                            "Скачается APK из релиза на GitHub"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                if (progress == null) {
                    TextButton(onClick = onInstall) { Text("Обновить") }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Скрыть")
                    }
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp).padding(end = 8.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
            if (progress != null) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                )
            }
        }
    }
}

/** Thin progress line under the toolbar while the vault is being read or written. */
@Composable
private fun VaultBusyBar(text: String?) {
    Column(Modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.width(12.dp).height(12.dp), strokeWidth = 1.5.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = text ?: "Работаю с хранилищем…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
