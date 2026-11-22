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
import io.github.evgenykon.mybooks.data.AndroidSecrets
import io.github.evgenykon.mybooks.ui.ReaderScreen
import io.github.evgenykon.mybooks.ui.SettingsSheet
import io.github.evgenykon.mybooks.ui.VaultScreen
import io.github.evgenykon.mybooks.ui.paletteOf

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
    val viewModel: LibraryViewModel = viewModel { LibraryViewModel(repo, AndroidSecrets(context)) }

    val books by viewModel.books.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val notes by viewModel.notes.collectAsStateWithLifecycle()

    var openBookId by rememberSaveable { mutableStateOf<String?>(null) }
    var showVaults by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val openBook = books.firstOrNull { it.id == openBookId }

    BackHandler(enabled = openBook != null || showVaults || showSettings) {
        when {
            showSettings -> showSettings = false
            showVaults -> showVaults = false
            else -> openBookId = null
        }
    }

    val vaultState by viewModel.vault.state.collectAsStateWithLifecycle()

    if (showSettings) {
        SettingsSheet(
            settings = settings,
            palette = paletteOf(settings),
            onChange = viewModel::setSettings,
            onDismiss = { showSettings = false },
            onCheckUpdates = {
                viewModel.checkForUpdates()
                showSettings = false
            },
        )
    }

    if (showVaults) {
        VaultScreen(
            state = vaultState,
            onBack = { showVaults = false },
            onSaveSettings = viewModel.vault::saveSettings,
            onRefresh = viewModel.vault::refreshVaults,
            onCreate = viewModel.vault::createVault,
            onOpen = { name, password -> 
                viewModel.vault.openVault(name, password)
                viewModel.reloadFromDisk()
            },
            onSync = viewModel.vault::syncNow,
            onCloseVault = viewModel.vault::closeVault,
            onDelete = viewModel.vault::deleteVault,
            onConsumeMessage = viewModel.vault::consumeMessage,
            onOpenTokenPage = {
                val url = "https://github.com/settings/personal-access-tokens/new" +
                    "?name=MyBooks&description=My+Books+vault&expires_in=90" +
                    "&target_name=" + vaultState.owner.ifBlank { "evgenykon" } +
                    "&permissions%5Bcontents%5D=write"
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(url),
                        ),
                    )
                }
            },
        )
    } else if (openBook == null) {
        LibraryScreen(
            viewModel = viewModel,
            onOpenBook = { openBookId = it.id },
            onOpenSettings = { showSettings = true },
            onOpenVaults = {
                showVaults = true
                viewModel.vault.refreshVaults()
            },
            activeVault = vaultState.active,
            vaultBusy = vaultState.busy,
            vaultBusyText = vaultState.busyText,
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
