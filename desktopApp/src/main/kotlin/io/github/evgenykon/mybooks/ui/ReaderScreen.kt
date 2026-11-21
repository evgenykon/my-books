package io.github.evgenykon.mybooks.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.evgenykon.mybooks.core.BlockKind
import io.github.evgenykon.mybooks.core.Book
import io.github.evgenykon.mybooks.core.BookNote
import io.github.evgenykon.mybooks.core.Position
import io.github.evgenykon.mybooks.core.ReadingProgress
import io.github.evgenykon.mybooks.data.BookRecord
import io.github.evgenykon.mybooks.data.LibraryRepository
import io.github.evgenykon.mybooks.data.ReaderSettings
import io.github.evgenykon.mybooks.reader.PageLine
import io.github.evgenykon.mybooks.reader.ReaderPage
import io.github.evgenykon.mybooks.reader.metricsFor
import io.github.evgenykon.mybooks.reader.pageIndexOfBlock
import io.github.evgenykon.mybooks.reader.paginate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

private val MAX_TEXT_WIDTH = 880.dp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ReaderScreen(
    record: BookRecord,
    repo: LibraryRepository,
    state: AppState,
    settings: ReaderSettings,
    notes: List<BookNote>,
    onSettingsChange: (ReaderSettings) -> Unit,
    onBack: () -> Unit,
) {
    val palette = paletteOf(settings)
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 160)
    val clipboard = LocalClipboardManager.current

    var book by remember(record.id) { mutableStateOf<Book?>(null) }
    var pages by remember(record.id) { mutableStateOf<List<ReaderPage>?>(null) }
    var pageIndex by remember(record.id) { mutableStateOf(0) }
    var anchorBlock by remember(record.id) { mutableStateOf(record.blockIndex) }
    var panel by remember { mutableStateOf<ReaderPanel?>(null) }
    var noteDraft by remember { mutableStateOf<NoteDraft?>(null) }
    var wheel by remember { mutableStateOf(0f) }

    LaunchedEffect(record.id) {
        state.markOpened(record)
        book = withContext(Dispatchers.IO) { repo.loadBook(record) }
    }

    val pageCount = pages?.size ?: 0

    fun goTo(index: Int) {
        val total = pages?.size ?: return
        if (total == 0) return
        pageIndex = index.coerceIn(0, total - 1)
        pages?.getOrNull(pageIndex)?.let { anchorBlock = it.firstBlockIndex }
    }

    val currentBlock = pages?.getOrNull(pageIndex)?.firstBlockIndex ?: anchorBlock
    val percent = book?.let { b ->
        ReadingProgress.percent(b, Position(currentBlock, 0))
    } ?: record.percent

    // Save the reading position shortly after the page settles.
    LaunchedEffect(pageIndex, anchorBlock, book) {
        val b = book ?: return@LaunchedEffect
        delay(500)
        state.updatePosition(record, anchorBlock, 0, ReadingProgress.percent(b, Position(anchorBlock, 0)))
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val typing = panel == ReaderPanel.NOTES || noteDraft != null
                when {
                    event.isCtrlPressed && event.key == Key.Equals -> {
                        onSettingsChange(settings.copy(fontSize = (settings.fontSize + 1).coerceAtMost(34)))
                        true
                    }

                    event.isCtrlPressed && event.key == Key.Minus -> {
                        onSettingsChange(settings.copy(fontSize = (settings.fontSize - 1).coerceAtLeast(12)))
                        true
                    }

                    event.key == Key.Escape -> {
                        when {
                            noteDraft != null -> noteDraft = null
                            panel != null -> panel = null
                            else -> onBack()
                        }
                        true
                    }

                    typing -> false

                    panel != null -> when (event.key) {
                        Key.DirectionLeft, Key.PageUp -> { goTo(pageIndex - 1); true }
                        Key.DirectionRight, Key.PageDown -> { goTo(pageIndex + 1); true }
                        else -> false
                    }

                    else -> when (event.key) {
                        Key.DirectionLeft, Key.PageUp -> { goTo(pageIndex - 1); true }
                        Key.DirectionRight, Key.PageDown, Key.Spacebar -> { goTo(pageIndex + 1); true }
                        Key.MoveHome -> { goTo(0); true }
                        Key.MoveEnd -> { goTo(pageCount - 1); true }
                        else -> false
                    }
                }
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            ReaderHeader(
                title = book?.title ?: record.title,
                author = book?.authorLine ?: record.author,
                pageNumber = if (pageCount > 0) pageIndex + 1 else 0,
                pageCount = pageCount,
                percent = percent,
                palette = palette,
                onBack = onBack,
                onToc = { panel = if (panel == ReaderPanel.TOC) null else ReaderPanel.TOC },
                onNotes = { panel = if (panel == ReaderPanel.NOTES) null else ReaderPanel.NOTES },
                onSettings = { panel = if (panel == ReaderPanel.SETTINGS) null else ReaderPanel.SETTINGS },
            )

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val contentWidth = minOf(maxWidth, MAX_TEXT_WIDTH)
                val pageWidthPx = with(density) {
                    (contentWidth - (settings.marginH * 2).dp).toPx()
                }.toInt()
                val pageHeightPx = with(density) {
                    (maxHeight - (settings.marginV * 2).dp).toPx()
                }.toInt()

                LaunchedEffect(book, pageWidthPx, pageHeightPx, settings, palette) {
                    val loaded = book ?: return@LaunchedEffect
                    val result = paginate(
                        blocks = loaded.blocks,
                        pageWidthPx = pageWidthPx,
                        pageHeightPx = pageHeightPx,
                        measurer = measurer,
                        settings = settings,
                        density = density,
                        styleFor = { kind -> styleFor(kind, settings, palette) },
                    )
                    pages = result
                    pageIndex = pageIndexOfBlock(result, anchorBlock).coerceIn(0, result.lastIndex.coerceAtLeast(0))
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .onPointerEvent(PointerEventType.Scroll) { event ->
                            if (panel != null) return@onPointerEvent
                            wheel += event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
                            if (abs(wheel) >= 1f) {
                                goTo(pageIndex + if (wheel > 0f) 1 else -1)
                                wheel = 0f
                            }
                        },
                    contentAlignment = Alignment.TopCenter,
                ) {
                    val page = pages?.getOrNull(pageIndex)
                    when {
                        pages == null -> Column(
                            Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator(strokeWidth = 2.dp)
                            Spacer(Modifier.height(12.dp))
                            Text("Разбивка на страницы…", color = palette.secondary, fontSize = 13.sp)
                        }

                        page == null || page.lines.isEmpty() -> Text(
                            text = "Книга пуста или не удалось её разобрать",
                            color = palette.secondary,
                            modifier = Modifier.align(Alignment.Center),
                        )

                        else -> Column(
                            Modifier
                                .width(contentWidth)
                                .fillMaxHeight()
                                .padding(
                                    horizontal = settings.marginH.dp,
                                    vertical = settings.marginV.dp,
                                ),
                        ) {
                            SelectionContainer {
                                Column(Modifier.fillMaxWidth()) {
                                    page.lines.forEach { line ->
                                        PageLineView(
                                            line = line,
                                            settings = settings,
                                            palette = palette,
                                            density = density,
                                            onCopy = { blockText ->
                                                clipboard.setText(AnnotatedString(blockText))
                                            },
                                            onNote = { blockText ->
                                                noteDraft = NoteDraft(line.blockIndex, blockText)
                                            },
                                            blockText = book?.blocks?.getOrNull(line.blockIndex)?.text.orEmpty(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            ReaderFooter(
                palette = palette,
                canPrev = pageIndex > 0,
                canNext = pageIndex < pageCount - 1,
                onPrev = { goTo(pageIndex - 1) },
                onNext = { goTo(pageIndex + 1) },
            )
        }

        // Panel scrim
        AnimatedVisibility(
            visible = panel != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.28f))
                    .clickable { panel = null },
            )
        }

        AnimatedVisibility(
            visible = panel != null,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Surface(
                modifier = Modifier.width(400.dp).fillMaxHeight(),
                color = palette.surface,
                shadowElevation = 12.dp,
            ) {
                when (panel) {
                    ReaderPanel.TOC -> TocPanel(
                        toc = book?.toc ?: emptyList(),
                        currentBlock = currentBlock,
                        palette = palette,
                        onSelect = { block ->
                            panel = null
                            val target = pages ?: return@TocPanel
                            goTo(pageIndexOfBlock(target, block))
                        },
                        onClose = { panel = null },
                    )

                    ReaderPanel.NOTES -> NotesPanel(
                        notes = notes,
                        palette = palette,
                        onSelect = { note ->
                            panel = null
                            val target = pages ?: return@NotesPanel
                            goTo(pageIndexOfBlock(target, note.blockIndex))
                        },
                        onDelete = state::deleteNote,
                        onClose = { panel = null },
                    )

                    ReaderPanel.SETTINGS -> SettingsPanel(
                        settings = settings,
                        palette = palette,
                        onChange = onSettingsChange,
                        onClose = { panel = null },
                    )

                    null -> Unit
                }
            }
        }
    }

    noteDraft?.let { draft ->
        NoteEditorDialog(
            draft = draft,
            palette = palette,
            onSave = { text ->
                state.addNote(record.id, draft.blockIndex, draft.quote, text)
                noteDraft = null
                panel = ReaderPanel.NOTES
            },
            onDismiss = { noteDraft = null },
        )
    }
}

@Composable
private fun ReaderHeader(
    title: String,
    author: String,
    pageNumber: Int,
    pageCount: Int,
    percent: Int,
    palette: ReaderPalette,
    onBack: () -> Unit,
    onToc: () -> Unit,
    onNotes: () -> Unit,
    onSettings: () -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "К библиотеке",
                    tint = palette.text,
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(author)
                        if (pageCount > 0) append(" · стр. $pageNumber из $pageCount")
                        append(" · $percent%")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onToc) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "Содержание", tint = palette.text)
            }
            IconButton(onClick = onNotes) {
                Icon(Icons.Filled.EditNote, contentDescription = "Заметки", tint = palette.text)
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Tune, contentDescription = "Настройки", tint = palette.text)
            }
        }
        HorizontalDivider(color = palette.secondary.copy(alpha = 0.18f))
    }
}

@Composable
private fun ReaderFooter(
    palette: ReaderPalette,
    canPrev: Boolean,
    canNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(40.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev, enabled = canPrev) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Предыдущая страница",
                tint = if (canPrev) palette.text else palette.secondary.copy(alpha = 0.35f),
            )
        }
        Text(
            text = "←  →  листать · Ctrl + / −  размер шрифта",
            style = MaterialTheme.typography.labelSmall,
            color = palette.secondary.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        IconButton(onClick = onNext, enabled = canNext) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Следующая страница",
                tint = if (canNext) palette.text else palette.secondary.copy(alpha = 0.35f),
            )
        }
    }
}

@Composable
private fun PageLineView(
    line: PageLine,
    settings: ReaderSettings,
    palette: ReaderPalette,
    density: Density,
    blockText: String,
    onCopy: (String) -> Unit,
    onNote: (String) -> Unit,
) {
    val m = metricsFor(line.kind, settings, density)
    val style = styleFor(line.kind, settings, palette)
    val alignment = when (line.kind) {
        BlockKind.HEADING1, BlockKind.HEADING2 -> TextAlign.Center
        BlockKind.EPIGRAPH_AUTHOR -> TextAlign.End
        else -> TextAlign.Start
    }

    if (line.isFirstOfBlock && m.spaceBeforePx > 0f) {
        Spacer(Modifier.height(with(density) { m.spaceBeforePx.toDp() }))
    }

    ContextMenuArea(
        items = {
            listOf(
                ContextMenuItem("Копировать абзац") { onCopy(blockText.ifBlank { line.text }) },
                ContextMenuItem("Добавить заметку") { onNote(blockText.ifBlank { line.text }) },
            )
        },
    ) {
        Text(
            text = line.text,
            style = style.copy(textAlign = alignment),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = with(density) { m.leadingIndentPx.toDp() })
                .height(with(density) { m.lineHeightPx.toDp() }),
        )
    }

    if (line.isLastOfBlock && m.spaceAfterPx > 0f) {
        Spacer(Modifier.height(with(density) { m.spaceAfterPx.toDp() }))
    }
}
