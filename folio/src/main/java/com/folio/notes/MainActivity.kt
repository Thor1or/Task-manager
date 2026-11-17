package com.folio.notes

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.folio.notes.data.Folder
import com.folio.notes.data.Settings
import com.folio.notes.ui.editor.EditorScreen
import com.folio.notes.ui.editor.EditorViewModel
import com.folio.notes.ui.folders.FoldersScreen
import com.folio.notes.ui.notes.ListKind
import com.folio.notes.ui.notes.NotesScreen
import com.folio.notes.ui.notes.NotesViewModel
import com.folio.notes.ui.settings.SettingsScreen
import com.folio.notes.ui.theme.FolioTheme

class MainActivity : ComponentActivity() {
    /** The launch / share / shortcut intent still waiting to be turned into navigation. */
    private var pendingIntent by mutableStateOf<Intent?>(null)
    private val app get() = application as FolioApp

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val firstLaunch = savedInstanceState == null
        if (firstLaunch) pendingIntent = intent
        setContent {
            val settings by app.settings.settings.collectAsStateWithLifecycle(Settings())
            FolioTheme(settings.theme) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val nav = rememberNavController()
                    LaunchedEffect(Unit) {
                        // Open on All Notes, with the folder list one step back.
                        if (firstLaunch && nav.currentDestination?.route == FOLDERS) nav.navigate(notesRoute(ListKind.ALL))
                    }
                    LaunchedEffect(pendingIntent) {
                        pendingIntent?.let { handleIntent(it, nav) }
                        pendingIntent = null
                    }
                    NavHost(nav, startDestination = FOLDERS) {
                        composable(FOLDERS) {
                            Box(Modifier.fillMaxSize()) {
                                FoldersScreen(
                                    onOpenAll = { nav.navigate(notesRoute(ListKind.ALL)) },
                                    onOpenFolder = { nav.navigate(notesRoute(ListKind.FOLDER, it.toString())) },
                                    onOpenTrash = { nav.navigate(notesRoute(ListKind.TRASH)) },
                                    onOpenTag = { nav.navigate(notesRoute(ListKind.TAG, it)) },
                                    onNewNote = { nav.navigate(editorRoute()) },
                                    onSettings = { nav.navigate(SETTINGS) },
                                )
                            }
                        }
                        composable(
                            NOTES_ROUTE,
                            arguments = listOf(
                                navArgument(NotesViewModel.KEY_KIND) { type = NavType.StringType },
                                navArgument(NotesViewModel.KEY_ARG) { type = NavType.StringType; defaultValue = "" },
                            ),
                        ) {
                            Box(Modifier.fillMaxSize()) {
                                NotesScreen(
                                    onBack = { if (!nav.popBackStack()) finish() },
                                    onOpenNote = { nav.navigate(editorRoute(id = it)) },
                                    onNewNote = { nav.navigate(editorRoute(folder = it)) },
                                )
                            }
                        }
                        composable(
                            EDITOR_ROUTE,
                            arguments = listOf(
                                navArgument(EditorViewModel.KEY_ID) { type = NavType.LongType; defaultValue = 0L },
                                navArgument(EditorViewModel.KEY_FOLDER) { type = NavType.LongType; defaultValue = Folder.DEFAULT_ID },
                            ),
                        ) {
                            Box(Modifier.fillMaxSize()) { EditorScreen(onBack = { if (!nav.popBackStack()) finish() }) }
                        }
                        composable(SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingIntent = intent
    }

    override fun onStart() {
        super.onStart()
        app.onReturned()
    }

    override fun onStop() {
        super.onStop()
        // Locked notes close as soon as the app is out of sight.
        if (!isChangingConfigurations) app.onLeftScreen()
    }

    private fun handleIntent(intent: Intent, nav: NavHostController) {
        val route = when (intent.action) {
            ACTION_NEW_NOTE -> editorRoute()
            Intent.ACTION_SEND -> {
                // Text shared from another app becomes a new note.
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
                if (text.isBlank() && subject.isBlank()) null
                else {
                    app.shareText(listOf(subject, text).filter { it.isNotBlank() }.joinToString("\n"))
                    editorRoute()
                }
            }
            else -> null
        } ?: return
        nav.navigate(route)
    }

    companion object {
        const val ACTION_NEW_NOTE = "com.folio.notes.NEW_NOTE"
        private const val FOLDERS = "folders"
        private const val SETTINGS = "settings"
        private const val NOTES_ROUTE = "notes/{${NotesViewModel.KEY_KIND}}?${NotesViewModel.KEY_ARG}={${NotesViewModel.KEY_ARG}}"
        private const val EDITOR_ROUTE = "editor?${EditorViewModel.KEY_ID}={${EditorViewModel.KEY_ID}}&${EditorViewModel.KEY_FOLDER}={${EditorViewModel.KEY_FOLDER}}"

        fun notesRoute(kind: ListKind, arg: String = "") = "notes/${kind.name}?${NotesViewModel.KEY_ARG}=${android.net.Uri.encode(arg)}"
        fun editorRoute(id: Long = 0, folder: Long = Folder.DEFAULT_ID) = "editor?${EditorViewModel.KEY_ID}=$id&${EditorViewModel.KEY_FOLDER}=$folder"
    }
}
