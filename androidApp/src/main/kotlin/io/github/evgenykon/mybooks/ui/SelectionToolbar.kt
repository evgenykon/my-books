package io.github.evgenykon.mybooks.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.window.Popup

private class SelectionMenu(
    val rect: Rect,
    val onCopy: (() -> Unit)?,
)

/**
 * Replaces the system text selection toolbar with a two-action one: copy, and
 * "add to note". The quote for a note is taken from the clipboard right after the
 * selection is copied, which is how Compose exposes the selected text.
 */
@Composable
fun ReaderSelectionHost(
    onAddNote: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    var menu by remember { mutableStateOf<SelectionMenu?>(null) }
    val clipboard = LocalClipboardManager.current

    val toolbar = remember {
        object : TextToolbar {
            override val status: TextToolbarStatus
                get() = if (menu == null) TextToolbarStatus.Hidden else TextToolbarStatus.Shown

            override fun hide() {
                menu = null
            }

            override fun showMenu(
                rect: Rect,
                onCopyRequested: (() -> Unit)?,
                onPasteRequested: (() -> Unit)?,
                onCutRequested: (() -> Unit)?,
                onSelectAllRequested: (() -> Unit)?,
            ) {
                menu = SelectionMenu(rect, onCopyRequested)
            }
        }
    }

    CompositionLocalProvider(LocalTextToolbar provides toolbar) {
        SelectionContainer {
            content()
        }
    }

    menu?.let { current ->
        Popup(
            alignment = androidx.compose.ui.Alignment.TopStart,
            offset = IntOffset(
                current.rect.left.toInt(),
                (current.rect.top.toInt() - 120).coerceAtLeast(0),
            ),
            onDismissRequest = { menu = null },
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
            ) {
                Row(Modifier.padding(horizontal = 4.dp)) {
                    TextButton(onClick = {
                        current.onCopy?.invoke()
                        menu = null
                    }) {
                        Text("Копировать")
                    }
                    TextButton(onClick = {
                        current.onCopy?.invoke()
                        val quote = clipboard.getText()?.text.orEmpty().trim()
                        menu = null
                        if (quote.isNotEmpty()) onAddNote(quote)
                    }) {
                        Text("В заметку")
                    }
                }
            }
        }
    }
}
