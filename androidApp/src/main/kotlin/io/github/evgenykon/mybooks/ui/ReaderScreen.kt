package io.github.evgenykon.mybooks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.evgenykon.mybooks.core.Footnote
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
import io.github.evgenykon.mybooks.reader.pageIndexOfBlock
import io.github.evgenykon.mybooks.reader.metricsFor
import io.github.evgenykon.mybooks.reader.paginate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ReaderScreen(
    record: BookRecord,
    repo: LibraryRepository,
    viewModel: LibraryViewModel,
    settings: ReaderSettings,
    notes: List<BookNote>,
    onSettingsChange: (ReaderSettings) -> Unit,
    onBack: () -> Unit,
) {
    val palette = paletteOf(settings)
    val measurer = rememberTextMeasurer(cacheSize = 96)
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var book by remember(record.id) { mutableStateOf<Book?>(null) }
    var pages by remember(record.id) { mutableStateOf<List<ReaderPage>>(emptyList()) }
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showNotes by remember { mutableStateOf(false) }
    var pendingQuote by remember { mutableStateOf<String?>(null) }
    var pendingBlock by remember { mutableStateOf(0) }
    var footnote by remember { mutableStateOf<FootnotePopup?>(null) }
    var jumpTarget by remember { mutableStateOf<Int?>(null) }
    // Height of the toolbar above the page. The pagination budget must exclude it: without
    // that the last lines of every page are laid out below the visible area and disappear.
    var headerHeightPx by remember { mutableStateOf(0) }

    LaunchedEffect(record.id) {
        book = repo.loadBook(record)
    }

    Column(Modifier.fillMaxSize().background(palette.background)) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val pageWidth = with(density) { (maxWidth - (settings.marginH * 2).dp).toPx() }.toInt()
            val pageHeight = (
                with(density) { (maxHeight - (settings.marginV * 2).dp).toPx() } - headerHeightPx
                ).toInt().coerceAtLeast(1)
            val paragraphSpacing = with(density) { (settings.fontSize * 0.45f).dp.toPx() }
            val emptyLine = with(density) { (settings.fontSize * 0.8f).dp.toPx() }

            LaunchedEffect(book, pageWidth, pageHeight, settings) {
                val b = book ?: return@LaunchedEffect
                pages = paginate(
                    blocks = b.blocks,
                    pageWidthPx = pageWidth,
                    pageHeightPx = pageHeight,
                    measurer = measurer,
                    settings = settings,
                    density = density,
                    styleFor = { kind -> styleFor(kind, settings, palette) },
                )
            }

            val pagerState = rememberPagerState(pageCount = { pages.size.coerceAtLeast(1) })
            val pageIndex = pagerState.currentPage

            var restored by remember(book) { mutableStateOf(false) }
            LaunchedEffect(pages, book) {
                if (!restored && pages.isNotEmpty() && book != null) {
                    restored = true
                    pagerState.scrollToPage(pageIndexOfBlock(pages, record.blockIndex).coerceIn(0, pages.lastIndex))
                }
            }

            LaunchedEffect(pageIndex, pages) {
                val page = pages.getOrNull(pageIndex) ?: return@LaunchedEffect
                val b = book ?: return@LaunchedEffect
                delay(400)
                viewModel.updatePosition(
                    record = record,
                    blockIndex = page.firstBlockIndex,
                    charOffset = 0,
                    percent = ReadingProgress.percent(b, Position(page.firstBlockIndex, 0)),
                )
            }

            val jumpToBlock: (Int) -> Unit = { target ->
                val idx = pageIndexOfBlock(pages, target).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                scope.launch { pagerState.animateScrollToPage(idx) }
            }

            // Jump requested from outside this scope (a footnote popup).
            LaunchedEffect(jumpTarget) {
                val target = jumpTarget ?: return@LaunchedEffect
                jumpToBlock(target)
                jumpTarget = null
            }

            Column(Modifier.fillMaxSize()) {
                val currentPage = pages.getOrNull(pageIndex)
                val percent = book?.let { b ->
                    currentPage?.let { ReadingProgress.percent(b, Position(it.firstBlockIndex, 0)) }
                } ?: record.percent

                Box(Modifier.onGloballyPositioned { headerHeightPx = it.size.height }) {
                ReaderHeader(
                    title = book?.title ?: record.title,
                    author = book?.authorLine ?: record.author,
                    pageNumber = pageIndex + 1,
                    pageCount = pages.size,
                    percent = percent,
                    palette = palette,
                    onBack = onBack,
                    onToc = { showToc = true },
                    onNotes = { showNotes = true },
                    onSettings = { showSettings = true },
                )
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    beyondViewportPageCount = 1,
                ) { index ->
                    val current = pages.getOrNull(index)
                    if (current == null) {
                        Box(Modifier.fillMaxSize())
                    } else {
                        ReaderSelectionHost(
                            onAddNote = { quote ->
                                pendingQuote = quote
                                pendingBlock = current.firstBlockIndex
                            },
                        ) {
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = settings.marginH.dp, vertical = settings.marginV.dp),
                            ) {
                                current.lines.forEach { line ->
                                    PageLineView(
                                        line = line,
                                        footnotes = book?.blocks?.getOrNull(line.blockIndex)?.footnotes.orEmpty(),
                                        settings = settings,
                                        palette = palette,
                                        density = density,
                                        onFootnote = { note -> footnote = popupFor(book, note) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (showToc) {
                TocSheet(
                    toc = book?.toc ?: emptyList(),
                    palette = palette,
                    onDismiss = { showToc = false },
                    onSelect = { blockIndex ->
                        showToc = false
                        jumpToBlock(blockIndex)
                    },
                )
            }

            if (showNotes) {
                NotesSheet(
                    notes = notes,
                    palette = palette,
                    onDismiss = { showNotes = false },
                    onSelect = { note ->
                        showNotes = false
                        jumpToBlock(note.blockIndex)
                    },
                    onDelete = viewModel::deleteNote,
                )
            }

            if (showSettings) {
                SettingsSheet(
                    settings = settings,
                    palette = palette,
                    onChange = onSettingsChange,
                    onDismiss = { showSettings = false },
                )
            }
        }
    }

    footnote?.let { note ->
        AlertDialog(
            onDismissRequest = { footnote = null },
            title = { Text("Примечание") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(note.text, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    footnote = null
                    jumpTarget = note.blockIndex
                }) { Text("Перейти") }
            },
            dismissButton = {
                TextButton(onClick = { footnote = null }) { Text("Закрыть") }
            },
        )
    }

    pendingQuote?.let { quote ->
        var noteText by remember(quote) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pendingQuote = null },
            title = { Text("Новая заметка") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = "«" + quote.take(300) + "»",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.secondary,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        placeholder = { Text("Текст заметки") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.addNote(record.id, pendingBlock, quote, noteText.ifBlank { quote })
                    pendingQuote = null
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { pendingQuote = null }) { Text("Отмена") }
            },
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = palette.text)
        }
        Column(Modifier.weight(1f)) {
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
            Icon(Icons.Filled.MenuBook, contentDescription = "Содержание", tint = palette.text)
        }
        IconButton(onClick = onNotes) {
            Icon(Icons.Filled.EditNote, contentDescription = "Заметки", tint = palette.text)
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Tune, contentDescription = "Настройки", tint = palette.text)
        }
    }
}

@Composable
private fun PageLineView(
    line: PageLine,
    footnotes: List<Footnote>,
    settings: ReaderSettings,
    palette: ReaderPalette,
    density: Density,
    onFootnote: (Footnote) -> Unit,
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
    // Footnote markers ("[1]") are accent coloured, and a tap on one opens the note.
    val annotated = remember(line.text, footnotes, line.startInBlock, palette.accent) {
        val builder = AnnotatedString.Builder(line.text)
        val lineFrom = line.startInBlock
        val lineTo = lineFrom + line.text.length
        for (note in footnotes) {
            val from = (note.start - lineFrom).coerceAtLeast(0)
            val to = (note.end - lineFrom).coerceAtMost(line.text.length)
            if (note.end <= lineFrom || note.start >= lineTo || to <= from) continue
            builder.addStyle(SpanStyle(color = palette.accent), from, to)
        }
        builder.toAnnotatedString()
    }

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    Text(
        text = annotated,
        style = style.copy(textAlign = alignment),
        maxLines = 1,
        softWrap = false,
        onTextLayout = { layout = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = with(density) { m.leadingIndentPx.toDp() })
            .height(with(density) { m.lineHeightPx.toDp() })
            .pointerInput(line.text, footnotes) {
                detectTapGestures { point ->
                    val result = layout ?: return@detectTapGestures
                    val offset = result.getOffsetForPosition(point)
                    val inBlock = line.startInBlock + offset
                    footnotes.firstOrNull { inBlock >= it.start && inBlock <= it.end }
                        ?.let(onFootnote)
                }
            },
    )
    if (line.isLastOfBlock && m.spaceAfterPx > 0f) {
        Spacer(Modifier.height(with(density) { m.spaceAfterPx.toDp() }))
    }
}

/** Note opened from a footnote marker. */
data class FootnotePopup(val blockIndex: Int, val text: String)

/** Resolves a marker to the text of the note it points at. */
private fun popupFor(book: io.github.evgenykon.mybooks.core.Book?, note: Footnote): FootnotePopup? {
    val loaded = book ?: return null
    val target = loaded.noteIndex[note.targetId] ?: return null
    val collected = StringBuilder()
    var i = target
    var taken = 0
    while (i < loaded.blocks.size && taken < 2) {
        val text = loaded.blocks[i].text
        if (text.isNotBlank()) {
            if (collected.isNotEmpty()) collected.append(' ')
            collected.append(text)
            taken++
        }
        i++
        if (i - target > 3) break
    }
    return FootnotePopup(target, collected.toString().trim())
}
