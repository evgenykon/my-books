package io.github.evgenykon.mybooks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.evgenykon.mybooks.data.VaultState
import io.github.evgenykon.mybooks.vault.VaultInfo
import io.github.evgenykon.mybooks.vault.VaultNames
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings of the encrypted vaults: GitHub access, the list of vault branches and the
 * "create / open / sync / delete" actions.
 */
@Composable
fun VaultScreen(
    state: VaultState,
    onBack: () -> Unit,
    onSaveSettings: (token: String, owner: String, repo: String) -> Unit,
    onRefresh: () -> Unit,
    onCreate: (name: String, password: String, confirmation: String) -> Unit,
    onOpen: (name: String, password: String?) -> Unit,
    onSync: () -> Unit,
    onCloseVault: () -> Unit,
    onDelete: (name: String) -> Unit,
    onConsumeMessage: () -> Unit,
    onOpenTokenPage: () -> Unit = {},
) {
    var token by remember(state.tokenSaved) { mutableStateOf("") }
    var owner by remember(state.owner) { mutableStateOf(state.owner) }
    var repo by remember(state.repo) { mutableStateOf(state.repo) }
    var creating by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf<VaultInfo?>(null) }
    var deleting by remember { mutableStateOf<VaultInfo?>(null) }

    Box(Modifier.fillMaxSize().background(Color(0xFFF7F7F5))) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
                Column(Modifier.weight(1f)) {
                    Text("Хранилища", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Зашифрованная копия библиотеки в ветке GitHub",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.active != null) {
                    OutlinedButton(onClick = onSync, enabled = !state.busy) {
                        Icon(Icons.Filled.CloudUpload, contentDescription = null)
                        Text("  Синхронизировать")
                    }
                }
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp).widthIn(max = 980.dp)) {
                val statusText = state.error ?: state.message
                if (statusText != null) {
                    Surface(
                        color = if (state.error != null) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = statusText,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (state.error != null) {
                                    MaterialTheme.colorScheme.onErrorContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            TextButton(onClick = onConsumeMessage) { Text("Скрыть") }
                        }
                    }
                }
                state.active?.let { active ->
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(
                                "  Активное хранилище: $active — изменения библиотеки уходят в ветку автоматически",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            TextButton(onClick = onCloseVault) { Text("Закрыть") }
                        }
                    }
                }

                SectionTitle("Доступ к GitHub")
                Text(
                    "Нужен fine-grained token с правом Contents: Read and write на репозиторий с ветками-хранилищами. " +
                        "Токен и пароли лежат в защищённом хранилище Windows (DPAPI).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(if (state.tokenSaved) "Токен сохранён — введите новый, чтобы заменить" else "Personal access token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = owner,
                        onValueChange = { owner = it },
                        label = { Text("Владелец") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = repo,
                        onValueChange = { repo = it },
                        label = { Text("Репозиторий") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            val effectiveToken = token.ifBlank { "" }
                            onSaveSettings(effectiveToken, owner, repo)
                            token = ""
                        },
                        enabled = !state.busy && owner.isNotBlank() && repo.isNotBlank(),
                    ) { Text("Сохранить доступ") }
                    TextButton(onClick = onOpenTokenPage) { Text("Создать токен на GitHub") }
                    OutlinedButton(onClick = onRefresh, enabled = state.configured && !state.busy) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Text("  Обновить список")
                    }
                }

                Spacer(Modifier.height(22.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Ветки-хранилища", Modifier.weight(1f))
                    Button(onClick = { creating = true }, enabled = state.configured && !state.busy) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text("  Создать ветку")
                    }
                }
                Spacer(Modifier.height(10.dp))

                when {
                    !state.configured -> Text(
                        "Сначала сохраните токен и репозиторий.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    state.vaults.isEmpty() -> Text(
                        "Хранилищ пока нет. Создайте ветку — в неё загрузятся книги, заметки, прогресс и настройки.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.vaults.forEach { vault ->
                            VaultRow(
                                vault = vault,
                                active = vault.name == state.active,
                                enabled = !state.busy,
                                onOpen = { opening = vault },
                                onDelete = { deleting = vault },
                            )
                        }
                    }
                }

                if (state.busy) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.height(16.dp), strokeWidth = 2.dp)
                        Text(
                            "  ${state.busyText ?: "Работаю…"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }

    if (creating) {
        CreateVaultDialog(
            onDismiss = { creating = false },
            onCreate = { name, password, confirmation ->
                onCreate(name, password, confirmation)
                creating = false
            },
        )
    }

    opening?.let { vault ->
        UnlockVaultDialog(
            vault = vault,
            onDismiss = { opening = null },
            onOpen = { password ->
                onOpen(vault.name, password)
                opening = null
            },
        )
    }

    deleting?.let { vault ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Удалить ветку «${vault.name}»?") },
            text = { Text("Ветка и зашифрованное хранилище в ней будут удалены из репозитория. Локальная библиотека останется.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(vault.name)
                    deleting = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun VaultRow(vault: VaultInfo, active: Boolean, enabled: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    Surface(
        color = if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = vault.name + if (active) "  · активно" else "",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(if (vault.passwordSaved) "пароль сохранён" else "пароль не сохранён")
                        if (vault.books > 0) append(" · книг ${vault.books}")
                        if (vault.notes > 0) append(" · заметок ${vault.notes}")
                        if (vault.updatedAt > 0) {
                            append(" · ")
                            append(SimpleDateFormat("d MMM yyyy HH:mm", Locale("ru")).format(Date(vault.updatedAt)))
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onOpen, enabled = enabled) {
                Icon(Icons.Filled.CloudDownload, contentDescription = null)
                Text("  Открыть")
            }
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(Icons.Filled.Delete, contentDescription = "Удалить ветку")
            }
        }
    }
}

@Composable
private fun CreateVaultDialog(onDismiss: () -> Unit, onCreate: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val nameError = if (name.isEmpty()) null else VaultNames.validate(name)
    val mismatch = confirmation.isNotEmpty() && password != confirmation

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая ветка-хранилище") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.trim().lowercase() },
                    label = { Text("Имя (оно же имя ветки)") },
                    placeholder = { Text("home-2026") },
                    singleLine = true,
                    isError = nameError != null,
                    supportingText = {
                        Text(nameError ?: "Строчные латинские буквы, цифры и «-»")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Пароль хранилища") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = { Text("Не короче 6 символов. Восстановить пароль невозможно.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    label = { Text("Пароль ещё раз") },
                    singleLine = true,
                    isError = mismatch,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, password, confirmation) },
                enabled = nameError == null && name.isNotEmpty() && password.length >= 6 && password == confirmation,
            ) { Text("Создать и зашифровать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun UnlockVaultDialog(vault: VaultInfo, onDismiss: () -> Unit, onOpen: (String?) -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Открыть «${vault.name}»") },
        text = {
            Column {
                Text(
                    if (vault.passwordSaved) {
                        "Пароль сохранён в защищённом хранилище Windows. Оставьте поле пустым, чтобы использовать его."
                    } else {
                        "Введите пароль хранилища. Он сохранится в Windows и больше спрашиваться не будет."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(if (vault.passwordSaved) "Пароль (можно оставить пустым)" else "Пароль") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onOpen(password.takeIf { it.isNotBlank() }) },
                enabled = vault.passwordSaved || password.isNotBlank(),
            ) { Text("Открыть") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
