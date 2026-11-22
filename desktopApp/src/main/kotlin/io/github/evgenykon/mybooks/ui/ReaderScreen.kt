package io.github.evgenykon.mybooks.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
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
    var ctxMenu by remember { mutableStateOf<ContextMenuTarget?>(null) }
    // Selection tracked by the page itself: Compose's SelectionContainer draws the highlight
    // but does not expose the selected text, so the quote has to be measured from the lines.
    var pageSelection by remember(record.id) { mutableStateOf<PageSelection?>(null) }
    val lineBoxes = remember(record.id) { mutableStateMapOf<Int, LineBox>() }
    var pageOrigin by remember(record.id) { mutableStateOf(Offset.Zero) }
    var footnote by remember(record.id) { mutableStateOf<FootnotePopup?>(null) }
    // Ranges of text already quoted in a note, per block, to underline them on the page.
    val quoteRanges = remember(notes, book) {
        notes.groupBy { it.blockIndex }.mapValues { (blockIndex, list) ->
            val text = book?.blocks?.getOrNull(blockIndex)?.text ?: return@mapValues emptyList()
            list.mapNotNull { note -> quoteRangeIn(text, note.quote) }
        }
    }

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
                                .onGloballyPositioned { pageOrigin = it.positionInRoot() }
                                .pointerInput(page, pageIndex) {
                                    // Drag over the page selects text; the range is remembered so
                                    // "Добавить заметку" quotes exactly what was selected.
                                    lineBoxes.clear()
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        if (down.type != PointerType.Mouse) return@awaitEachGesture
                                        val anchor = hitTest(lineBoxes, pageOrigin + down.position)
                                            ?: return@awaitEachGesture
                                        pageSelection = PageSelection(anchor, anchor)
                                        var last = anchor
                                        var moved = false
                                        while (true) {
                                            val change = awaitPointerEvent().changes
                                                .firstOrNull { it.id == down.id } ?: break
                                            if (!change.pressed) break
                                            val hit = hitTest(lineBoxes, pageOrigin + change.position)
                                            if (hit != null && hit != last) {
                                                last = hit
                                                moved = true
                                                pageSelection = PageSelection(anchor, hit)
                                            }
                                        }
                                        if (!moved) {
                                            // A click on a footnote marker opens its note.
                                            val loaded = book ?: return@awaitEachGesture
                                            val target = footnoteAt(loaded, page.lines, anchor)
                                            if (target != null) {
                                                footnote = target
                                                pageSelection = null
                                            }
                                        }
                                    }
                                }
                                .padding(
                                    horizontal = settings.marginH.dp,
                                    vertical = settings.marginV.dp,
                                ),
                        ) {
                            SelectionContainer {
                                Column(Modifier.fillMaxWidth()) {
                                    page.lines.forEachIndexed { lineIndex, line ->
                                        PageLineView(
                                            line = line,
                                            lineIndex = lineIndex,
                                            footnotes = book?.blocks?.getOrNull(line.blockIndex)?.footnotes.orEmpty(),
                                            quotes = quoteRanges[line.blockIndex].orEmpty(),
                                            accent = palette.accent,
                                            settings = settings,
                                            palette = palette,
                                            density = density,
                                            onLineBox = { box ->
                                                if (box.index != null) lineBoxes[box.index] = box
                                            },
                                            onContextMenu = { position, blockIndex, fallbackText ->
                                                val quote = quoteOf(pageSelection, page.lines).ifBlank { fallbackText }
                                                ctxMenu = ContextMenuTarget(
                                                    position = position,
                                                    blockIndex = blockIndex,
                                                    blockText = quote.ifBlank {
                                                        book?.blocks?.getOrNull(blockIndex)?.text.orEmpty()
                                                    },
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Kept outside SelectionContainer: a popup inside it inherits the
                    // selection registrar but lives in another window, which crashed
                    // coordinate mapping ("layouts are not part of the same hierarchy").
                    footnote?.let { note ->
                        FootnotePopup(
                            note = note,
                            palette = palette,
                            onJump = {
                                goTo(pageIndexOfBlock(pages ?: emptyList(), note.blockIndex))
                                footnote = null
                            },
                            onDismiss = { footnote = null },
                        )
                    }

                    ctxMenu?.let { menu ->
                        ReaderContextMenu(
                            target = menu,
                            onCopy = {
                                clipboard.setText(AnnotatedString(it))
                                ctxMenu = null
                            },
                            onNote = {
                                noteDraft = NoteDraft(menu.blockIndex, it)
                                ctxMenu = null
                            },
                            onDismiss = { ctxMenu = null },
                        )
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
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                // Pointer-only dismissal: a Clickable would also fire on the space key when it
                // happens to hold focus, closing the editor while typing.
                .pointerInput(Unit) { detectTapGestures { noteDraft = null } },
            contentAlignment = Alignment.Center,
        ) {
            Surface(shape = RoundedCornerShape(14.dp), color = palette.surface, shadowElevation = 16.dp) {
                NoteEditorPanel(
                    draft = draft,
                    palette = palette,
                    onSave = { text ->
                        noteDraft = null
                        runCatching { state.addNote(record.id, draft.blockIndex, draft.quote, text) }
                            .onFailure { state.showMessage("Не удалось сохранить заметку: ${it.message}") }
                        panel = ReaderPanel.NOTES
                    },
                    onDismiss = { noteDraft = null },
                )
            }
        }
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PageLineView(
    line: PageLine,
    lineIndex: Int,
    footnotes: List<Footnote>,
    quotes: List<IntRange>,
    accent: Color,
    settings: ReaderSettings,
    palette: ReaderPalette,
    density: Density,
    onLineBox: (LineBox) -> Unit,
    onContextMenu: (absolute: Offset, blockIndex: Int, text: String) -> Unit,
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

    var origin by remember { mutableStateOf(Offset.Zero) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    // Footnote markers ("[1]") are accent coloured so they read as something to tap.
    val annotated = remember(line.text, footnotes, line.startInBlock, accent) {
        val builder = AnnotatedString.Builder(line.text)
        val lineFrom = line.startInBlock
        val lineTo = lineFrom + line.text.length
        for (note in footnotes) {
            val from = (note.start - lineFrom).coerceAtLeast(0)
            val to = (note.end - lineFrom).coerceAtMost(line.text.length)
            if (note.end <= lineFrom || note.start >= lineTo || to <= from) continue
            builder.addStyle(SpanStyle(color = accent), from, to)
        }
        builder.toAnnotatedString()
    }

    Text(
        text = annotated,
        style = style.copy(textAlign = alignment),
        maxLines = 1,
        softWrap = false,
        onTextLayout = { result ->
            layout = result
            onLineBox(LineBox(lineIndex, line.text, origin, IntSize.Zero, result))
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = with(density) { m.leadingIndentPx.toDp() })
            .height(with(density) { m.lineHeightPx.toDp() })
            // Coordinates of the same node that reports the pointer position, so
            // origin + position is the exact window point of the click.
            .onGloballyPositioned { coords ->
                origin = coords.positionInRoot()
                onLineBox(LineBox(lineIndex, line.text, origin, coords.size, layout))
            }
            // Quoted text gets a dotted underline: a solid one reads like a link, and a
            // background box fights with the selection highlight.
            .drawBehind {
                val result = layout ?: return@drawBehind
                val from = line.startInBlock
                val to = from + line.text.length
                for (range in quotes) {
                    val start = maxOf(range.first, from) - from
                    val end = minOf(range.last, to - 1) - from
                    if (end <= start) continue
                    val y = result.getLineBottom(0) - 2.dp.toPx()
                    val first = result.getBoundingBox(start.coerceAtMost(line.text.length - 1))
                    val last = result.getBoundingBox(end.coerceAtMost(line.text.length - 1))
                    val x1 = first.left
                    val x2 = last.right
                    drawLine(
                        color = accent.copy(alpha = 0.65f),
                        start = Offset(x1, y),
                        end = Offset(x2, y),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())),
                    )
                }
            }
            // Right click: consume the press so the text does not start its own (broken for
            // popups) selection, and report where it happened.
            .onPointerEvent(PointerEventType.Press) { event ->
                if (event.button == PointerButton.Secondary) {
                    val position = event.changes.first().position
                    onContextMenu(origin + position, line.blockIndex, "")
                    event.changes.forEach { it.consume() }
                }
            },
    )

    if (line.isLastOfBlock && m.spaceAfterPx > 0f) {
        Spacer(Modifier.height(with(density) { m.spaceAfterPx.toDp() }))
    }
}


/** Geometry of one rendered line, in window coordinates. */
private data class LineBox(
    val index: Int,
    val text: String,
    val topLeft: Offset,
    val size: IntSize,
    val layout: TextLayoutResult?,
)

/** A point inside a line: which line and which character offset. */
internal data class Hit(val line: Int, val offset: Int)

/** Anchor and end of a mouse selection across the page. */
internal data class PageSelection(val anchor: Hit, val end: Hit)

/** Finds the line under a window point and the character offset inside it. */
private fun hitTest(boxes: Map<Int, LineBox>, point: Offset): Hit? {
    val candidates = boxes.values.filter { box ->
        val height = if (box.size.height > 0) box.size.height else 1
        point.y >= box.topLeft.y - 2f && point.y <= box.topLeft.y + height + 2f
    }
    val box = candidates.minByOrNull { kotlin.math.abs(it.topLeft.y + it.size.height / 2f - point.y) }
        ?: return null
    val layout = box.layout ?: return Hit(box.index, 0)
    val local = Offset(
        (point.x - box.topLeft.x).coerceAtLeast(0f),
        (point.y - box.topLeft.y).coerceAtLeast(0f),
    )
    val offset = layout.getOffsetForPosition(local).coerceIn(0, box.text.length)
    return Hit(box.index, offset)
}

/** Text covered by the selection, lines joined with a space. Empty when nothing is selected. */
internal fun quoteOf(selection: PageSelection?, lines: List<PageLine>): String {
    selection ?: return ""
    val first = selection.anchor
    val second = selection.end
    val (from, to) = if (first.line < second.line || (first.line == second.line && first.offset <= second.offset)) {
        first to second
    } else {
        second to first
    }
    if (from.line == to.line && from.offset == to.offset) return ""

    val builder = StringBuilder()
    for (i in from.line..to.line) {
        val text = lines.getOrNull(i)?.text ?: continue
        val start = if (i == from.line) from.offset.coerceIn(0, text.length) else 0
        val end = if (i == to.line) to.offset.coerceIn(0, text.length) else text.length
        if (end <= start) continue
        val part = text.substring(start, end).trim()
        if (part.isEmpty()) continue
        if (builder.isNotEmpty()) builder.append(' ')
        builder.append(part)
    }
    return builder.toString().trim()
}


/** Note shown in the footnote popup: where it lives and what it says. */
internal data class FootnotePopup(val blockIndex: Int, val text: String)

/** Footnote marker under a click, if any. */
internal fun footnoteAt(book: Book, lines: List<PageLine>, hit: Hit): FootnotePopup? {
    val line = lines.getOrNull(hit.line) ?: return null
    val block = book.blocks.getOrNull(line.blockIndex) ?: return null
    val offset = line.startInBlock + hit.offset
    val note = block.footnotes.firstOrNull { offset >= it.start && offset <= it.end } ?: return null
    val target = book.noteIndex[note.targetId] ?: return null

    // A note section starts with its number; take it and the paragraph that follows.
    val collected = StringBuilder()
    var i = target
    var taken = 0
    while (i < book.blocks.size && taken < 2) {
        val text = book.blocks[i].text
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

/** Small card with the note text, anchored to the page corner. */
@Composable
private fun FootnotePopup(
    note: FootnotePopup,
    palette: ReaderPalette,
    onJump: () -> Unit,
    onDismiss: () -> Unit,
) {
    Popup(alignment = Alignment.BottomCenter, onDismissRequest = onDismiss) {
        Surface(
            color = palette.surface,
            shape = RoundedCornerShape(10.dp),
            shadowElevation = 12.dp,
            modifier = Modifier.padding(16.dp).widthIn(max = 460.dp),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    text = note.text,
                    style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, color = palette.text),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onJump) { Text("Перейти") }
                    TextButton(onClick = onDismiss) { Text("Закрыть") }
                }
            }
        }
    }
}

/** Paragraph menu opened by a right click: window position and the paragraph it acts on. */
private data class ContextMenuTarget(
    val position: Offset,
    val blockIndex: Int,
    val blockText: String,
)

/**
 * Paragraph menu anchored at the exact window position of the right click.
 *
 * A bare popup with a custom [PopupPositionProvider] is used instead of DropdownMenu: the
 * menu must not be anchored to a layout node (the page column is centred, and offsets from
 * it landed half a window away).
 */
@Composable
private fun ReaderContextMenu(
    target: ContextMenuTarget,
    onCopy: (String) -> Unit,
    onNote: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Popup(
        popupPositionProvider = remember(target) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val x = target.position.x.toInt()
                        .coerceIn(8, (windowSize.width - popupContentSize.width - 8).coerceAtLeast(8))
                    val y = target.position.y.toInt()
                        .coerceIn(8, (windowSize.height - popupContentSize.height - 8).coerceAtLeast(8))
                    return IntOffset(x, y)
                }
            }
        },
        onDismissRequest = onDismiss,
        focusable = true,
    ) {
        MaterialTheme {
            Surface(
                shape = RoundedCornerShape(8.dp),
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.width(220.dp).padding(vertical = 6.dp)) {
                    DropdownMenuItem(
                        text = { Text("Копировать абзац") },
                        onClick = { onCopy(target.blockText) },
                    )
                    DropdownMenuItem(
                        text = { Text("Добавить заметку") },
                        onClick = { onNote(target.blockText) },
                    )
                }
            }
        }
    }
}
