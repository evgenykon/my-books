package io.github.evgenykon.mybooks.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.evgenykon.mybooks.core.BookNote
import io.github.evgenykon.mybooks.core.TocEntry
import io.github.evgenykon.mybooks.data.ReaderSettings
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TocSheet(
    toc: List<TocEntry>,
    palette: ReaderPalette,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = palette.surface,
    ) {
        Text(
            text = "Содержание",
            style = MaterialTheme.typography.titleMedium,
            color = palette.text,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        if (toc.isEmpty()) {
            Text(
                text = "В этой книге нет оглавления",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.secondary,
                modifier = Modifier.padding(20.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
                items(toc) { entry ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(entry.blockIndex) }
                            .padding(
                                start = (20 + (entry.level - 1) * 16).dp,
                                end = 20.dp,
                                top = 10.dp,
                                bottom = 10.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = entry.title,
                            color = palette.text,
                            fontSize = (16 - (entry.level - 1)).sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesSheet(
    notes: List<BookNote>,
    palette: ReaderPalette,
    onDismiss: () -> Unit,
    onSelect: (BookNote) -> Unit,
    onDelete: (BookNote) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = palette.surface,
    ) {
        Text(
            text = "Заметки",
            style = MaterialTheme.typography.titleMedium,
            color = palette.text,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        if (notes.isEmpty()) {
            Text(
                text = "Заметок пока нет. Выделите текст в книге и выберите «В заметку».",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.secondary,
                modifier = Modifier.padding(20.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
                items(notes, key = { it.id }) { note ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(note) }
                            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = note.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.text,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { onDelete(note) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Удалить", tint = palette.secondary)
                            }
                        }
                        if (note.quote.isNotBlank() && note.quote != note.text) {
                            Text(
                                text = "«" + note.quote.take(160) + "»",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.secondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    HorizontalDivider(color = palette.secondary.copy(alpha = 0.15f))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: ReaderSettings,
    palette: ReaderPalette,
    onChange: (ReaderSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = palette.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Настройки чтения",
                style = MaterialTheme.typography.titleMedium,
                color = palette.text,
            )

            Text("Шрифт", style = MaterialTheme.typography.labelMedium, color = palette.secondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fontFamilyNames.forEachIndexed { index, name ->
                    FilterChip(
                        selected = settings.fontFamily == index,
                        onClick = { onChange(settings.copy(fontFamily = index)) },
                        label = { Text(name) },
                    )
                }
            }

            SliderRow(
                label = "Размер шрифта",
                value = settings.fontSize.toFloat(),
                valueText = "${settings.fontSize}",
                range = 12f..34f,
                steps = 21,
                palette = palette,
            ) { onChange(settings.copy(fontSize = it.roundToInt())) }

            SliderRow(
                label = "Межстрочный интервал",
                value = settings.lineHeight,
                valueText = String.format("%.2f", settings.lineHeight),
                range = 1.0f..2.2f,
                steps = 11,
                palette = palette,
            ) { onChange(settings.copy(lineHeight = (it * 100).roundToInt() / 100f)) }

            SliderRow(
                label = "Поля",
                value = settings.marginH.toFloat(),
                valueText = "${settings.marginH} dp",
                range = 8f..48f,
                steps = 19,
                palette = palette,
            ) { onChange(settings.copy(marginH = it.roundToInt())) }

            Text("Тема", style = MaterialTheme.typography.labelMedium, color = palette.secondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Светлая", "Сепия", "Тёмная").forEachIndexed { index, name ->
                    FilterChip(
                        selected = settings.theme == index,
                        onClick = { onChange(settings.copy(theme = index)) },
                        label = { Text(name) },
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Выравнивание по ширине",
                    color = palette.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = settings.justified,
                    onCheckedChange = { onChange(settings.copy(justified = it)) },
                )
            }

            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onChange(ReaderSettings()) }) { Text("Сбросить") }
                TextButton(onClick = onDismiss) { Text("Готово") }
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    palette: ReaderPalette,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = palette.secondary,
                modifier = Modifier.weight(1f),
            )
            Text(text = valueText, style = MaterialTheme.typography.labelMedium, color = palette.text)
        }
        androidx.compose.material3.Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth().height(32.dp),
        )
    }
}
