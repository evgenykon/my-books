package io.github.evgenykon.mybooks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.evgenykon.mybooks.data.BookRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibraryScreen(
    books: List<BookRecord>,
    importing: Boolean,
    message: String?,
    onConsumeMessage: () -> Unit,
    onOpenBook: (BookRecord) -> Unit,
    onDeleteBook: (BookRecord) -> Unit,
    onImportClick: () -> Unit,
    onOpenVaults: () -> Unit = {},
    activeVault: String? = null,
    /** Vault work (create / open / sync / delete) runs in the background: show it here too. */
    vaultBusy: Boolean = false,
    vaultBusyText: String? = null,
) {
    val snackbar = remember { SnackbarHostState() }
    var query by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<BookRecord?>(null) }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        onConsumeMessage()
    }

    val visible = remember(books, query) {
        val q = query.trim()
        if (q.isEmpty()) books
        else books.filter {
            it.title.contains(q, ignoreCase = true) || it.author.contains(q, ignoreCase = true)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Color(0xFFF7F7F5),
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 28.dp, end = 20.dp, top = 22.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Библиотека",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = bookCountLabel(books.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (activeVault != null) {
                    Icon(
                        Icons.Filled.CloudDone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    Text(
                        text = activeVault,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 10.dp),
                    )
                }
                IconButton(onClick = onOpenVaults) {
                    Icon(
                        Icons.Filled.CloudQueue,
                        contentDescription = "Хранилища",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(10.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Поиск") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {}),
                    modifier = Modifier.width(240.dp),
                )
                Spacer(Modifier.width(12.dp))
                Button(onClick = onImportClick, enabled = !importing) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (importing) "Загрузка…" else "Добавить книгу")
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            if (vaultBusy) {
                VaultBusyBar(vaultBusyText)
            }

            Box(Modifier.fillMaxSize()) {
                when {
                    books.isEmpty() -> EmptyLibrary(onImportClick)
                    visible.isEmpty() -> Text(
                        text = "Ничего не найдено",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )

                    else -> LazyColumn(
                        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(visible, key = { it.id }) { book ->
                            BookRow(
                                book = book,
                                onClick = { onOpenBook(book) },
                                onDelete = { pendingDelete = book },
                            )
                        }
                    }
                }
                if (importing) {
                    CircularProgressIndicator(
                        Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                        strokeWidth = 2.dp,
                    )
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
                    onDeleteBook(book)
                    pendingDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Отмена") }
            },
        )
    }
}

private fun bookCountLabel(count: Int): String = when {
    count == 0 -> "Пока пусто"
    count % 10 == 1 && count % 100 != 11 -> "$count книга"
    count % 10 in 2..4 && count % 100 !in 12..14 -> "$count книги"
    else -> "$count книг"
}

@Composable
private fun EmptyLibrary(onImportClick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Библиотека пуста", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Перетащите файл .fb2 в окно или нажмите «Добавить книгу».",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onImportClick) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Добавить книгу")
        }
    }
}

@Composable
private fun BookRow(book: BookRecord, onClick: () -> Unit, onDelete: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Row(
        Modifier
            .fillMaxWidth()
            .background(
                color = if (hovered) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f) else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
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
            Spacer(Modifier.height(7.dp))
            LinearProgressIndicator(
                progress = { book.percent / 100f },
                modifier = Modifier.width(240.dp).height(4.dp),
                drawStopIndicator = {},
            )
            Spacer(Modifier.height(5.dp))
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
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Удалить",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Thin progress bar under the toolbar while the vault is being read or written. */
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
