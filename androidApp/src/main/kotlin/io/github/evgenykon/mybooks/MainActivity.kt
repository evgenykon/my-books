package io.github.evgenykon.mybooks

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.evgenykon.mybooks.ui.LibraryScreen
import io.github.evgenykon.mybooks.ui.LibraryViewModel
import io.github.evgenykon.mybooks.ui.ReaderScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                MyBooksApp()
            }
        }
    }
}

@Composable
fun MyBooksApp() {
    val context = LocalContext.current
    val repo = (context.applicationContext as MyBooksApplication).library
    val viewModel: LibraryViewModel = viewModel { LibraryViewModel(repo) }

    val books by viewModel.books.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val notes by viewModel.notes.collectAsStateWithLifecycle()

    var openBookId by rememberSaveable { mutableStateOf<String?>(null) }
    val openBook = books.firstOrNull { it.id == openBookId }

    BackHandler(enabled = openBook != null) { openBookId = null }

    if (openBook == null) {
        LibraryScreen(
            viewModel = viewModel,
            onOpenBook = { openBookId = it.id },
            onOpenSettings = { },
        )
    } else {
        ReaderScreen(
            record = openBook,
            repo = repo,
            viewModel = viewModel,
            settings = settings,
            notes = notes.filter { it.bookId == openBook.id },
            onSettingsChange = { newSettings -> viewModel.updateSettings { newSettings } },
            onBack = { openBookId = null },
        )
    }
}
