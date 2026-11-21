package io.github.evgenykon.mybooks.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.sp
import io.github.evgenykon.mybooks.core.BookNote
import io.github.evgenykon.mybooks.core.TocEntry
import io.github.evgenykon.mybooks.data.ReaderSettings
import kotlin.math.roundToInt

/** Side panel shown over the page. */
enum class ReaderPanel { TOC, NOTES, SETTINGS }

/** Draft of a note being written. */
data class NoteDraft(val blockIndex: Int, val quote: String)

@Composable
fun PanelHeader(title: String, palette: ReaderPalette, onClose: () -> Unit, trailing: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = palette.text)
            if (trailing != null) {
                Text(trailing, style = MaterialTheme.typography.labelSmall, color = palette.secondary)
            }
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = "Закрыть", tint = palette.secondary)
        }
    }
}

@Composable
fun TocPanel(
    toc: List<TocEntry>,
    currentBlock: Int,
    palette: ReaderPalette,
    onSelect: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val listState = rememberLazyListState()
    val currentIndex = remember(toc, currentBlock) {
        toc.indexOfLast { it.blockIndex <= currentBlock }.coerceAtLeast(0)
    }
    LaunchedEffect(currentIndex, toc) {
        if (toc.isNotEmpty()) listState.scrollToItem(currentIndex.coerceIn(0, toc.lastIndex))
    }

    Column(Modifier.fillMaxSize()) {
        PanelHeader("Содержание", palette, onClose, "${toc.size} разделов")
        HorizontalDivider(color = palette.secondary.copy(alpha = 0.15f))
        if (toc.isEmpty()) {
            Text(
                text = "В этой книге нет оглавления",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.secondary,
                modifier = Modifier.padding(20.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                itemsIndexed(toc) { index, entry ->
                    val active = index == currentIndex
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (active) palette.accent.copy(alpha = 0.12f) else Color.Transparent)
                            .clickable { onSelect(entry.blockIndex) }
                            .padding(
                                start = (20 + (entry.level - 1) * 16).dp,
                                end = 16.dp,
                                top = 9.dp,
                                bottom = 9.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = entry.title,
                            color = if (active) palette.accent else palette.text,
                            fontSize = (15 - (entry.level - 1)).sp,
                            fontWeight = if (entry.level == 1) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun NotesPanel(
    notes: List<BookNote>,
    palette: ReaderPalette,
    onSelect: (BookNote) -> Unit,
    onDelete: (BookNote) -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        PanelHeader("Заметки", palette, onClose, "${notes.size}")
        HorizontalDivider(color = palette.secondary.copy(alpha = 0.15f))
        if (notes.isEmpty()) {
            Text(
                text = "Заметок пока нет. Выделите текст и нажмите правую кнопку мыши на абзаце, " +
                    "чтобы добавить заметку.",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.secondary,
                modifier = Modifier.padding(20.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(notes, key = { it.id }) { note ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(note) }
                            .padding(start = 20.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
                    ) {
                        // Quote first and in full (no line limit): it is the anchor of the note.
                        if (note.quote.isNotBlank() && note.quote != note.text) {
                            Text(
                                text = "«" + note.quote.trim() + "»",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.secondary,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                text = note.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.text,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { onDelete(note) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Удалить",
                                    tint = palette.secondary,
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = palette.secondary.copy(alpha = 0.12f))
                }
            }
        }
    }
}

@Composable
fun SettingsPanel(
    settings: ReaderSettings,
    palette: ReaderPalette,
    onChange: (ReaderSettings) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        PanelHeader("Настройки чтения", palette, onClose)
        HorizontalDivider(color = palette.secondary.copy(alpha = 0.15f))

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel("Шрифт", palette)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fontFamilyNames.forEachIndexed { index, name ->
                    FilterChip(
                        selected = settings.fontFamily == index,
                        onClick = { onChange(settings.copy(fontFamily = index)) },
                        label = { Text(name, fontSize = 12.sp) },
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            SectionLabel("Тема", palette)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                themeNames.forEachIndexed { index, name ->
                    FilterChip(
                        selected = settings.theme == index,
                        onClick = { onChange(settings.copy(theme = index)) },
                        label = { Text(name, fontSize = 12.sp) },
                    )
                }
            }

            SliderRow(
                label = "Размер шрифта",
                valueText = "${settings.fontSize}",
                value = settings.fontSize.toFloat(),
                range = 12f..34f,
                steps = 0,
                palette = palette,
            ) { onChange(settings.copy(fontSize = it.roundToInt())) }

            SliderRow(
                label = "Межстрочный интервал",
                valueText = String.format("%.2f", settings.lineHeight),
                value = settings.lineHeight,
                range = 1.0f..2.2f,
                steps = 0,
                palette = palette,
            ) { onChange(settings.copy(lineHeight = it)) }

            SliderRow(
                label = "Поля по горизонтали",
                valueText = "${settings.marginH}",
                value = settings.marginH.toFloat(),
                range = 8f..120f,
                steps = 0,
                palette = palette,
            ) { onChange(settings.copy(marginH = it.roundToInt())) }

            SliderRow(
                label = "Поля по вертикали",
                valueText = "${settings.marginV}",
                value = settings.marginV.toFloat(),
                range = 4f..90f,
                steps = 0,
                palette = palette,
            ) { onChange(settings.copy(marginV = it.roundToInt())) }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Выравнивание по ширине",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.text,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = settings.justified,
                    onCheckedChange = { onChange(settings.copy(justified = it)) },
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, palette: ReaderPalette) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = palette.secondary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    palette: ReaderPalette,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.text,
                modifier = Modifier.weight(1f),
            )
            Text(valueText, style = MaterialTheme.typography.labelMedium, color = palette.secondary)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

/**
 * Editor of a note. Rendered inside the window (not as a separate `Dialog`): a dialog opened
 * from a right-click context menu belongs to another composition, and Compose fails with
 * "layouts are not part of the same hierarchy".
 */
@Composable
fun NoteEditorPanel(
    draft: NoteDraft,
    palette: ReaderPalette,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(draft) { mutableStateOf("") }

    Column(Modifier.width(520.dp).padding(22.dp)) {
        Text("Новая заметка", style = MaterialTheme.typography.titleMedium, color = palette.text)
        Spacer(Modifier.height(10.dp))
        Text(
            text = "«" + draft.quote.take(300) + "»",
            style = MaterialTheme.typography.bodySmall,
            color = palette.secondary,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Текст заметки") },
            minLines = 3,
            maxLines = 8,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Отмена") }
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = { onSave(text.ifBlank { draft.quote.take(200) }) }) {
                Text("Сохранить")
            }
        }
    }
}
