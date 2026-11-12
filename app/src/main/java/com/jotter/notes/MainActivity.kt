package com.jotter.notes

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jotter.notes.data.Note
import com.jotter.notes.data.Settings
import com.jotter.notes.reminders.ReminderScheduler
import com.jotter.notes.ui.editor.EditorScreen
import com.jotter.notes.ui.editor.EditorViewModel
import com.jotter.notes.ui.home.HomeScreen
import com.jotter.notes.ui.labels.LabelsScreen
import com.jotter.notes.ui.settings.SettingsScreen
import com.jotter.notes.ui.theme.JotterTheme

class MainActivity : ComponentActivity() {
    /** The launch / share / shortcut intent still waiting to be turned into navigation. */
    private var pendingIntent by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) pendingIntent = intent
        val app = application as JotterApp
        setContent {
            val settings by app.settings.settings.collectAsStateWithLifecycle(Settings())
            JotterTheme(settings.theme, settings.dynamicColor) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val nav = rememberNavController()
                    LaunchedEffect(pendingIntent) {
                        pendingIntent?.let { handleIntent(it, nav) }
                        pendingIntent = null
                    }
                    NavHost(nav, startDestination = "home") {
                        composable("home") {
                            Box(Modifier.fillMaxSize()) { HomeScreen(
                                onOpenNote = { nav.navigate(editorRoute(id = it)) },
                                onNewNote = { checklist, pickImage, labelId ->
                                    nav.navigate(editorRoute(checklist = checklist, pickImage = pickImage, labelId = labelId ?: 0))
                                },
                                onEditLabels = { nav.navigate("labels") },
                                onSettings = { nav.navigate("settings") },
                            ) }
                        }
                        composable(
                            EDITOR_ROUTE,
                            arguments = listOf(
                                navArgument(EditorViewModel.KEY_ID) { type = NavType.LongType; defaultValue = 0L },
                                navArgument(EditorViewModel.KEY_CHECKLIST) { type = NavType.BoolType; defaultValue = false },
                                navArgument(EditorViewModel.KEY_PICK_IMAGE) { type = NavType.BoolType; defaultValue = false },
                                navArgument(EditorViewModel.KEY_LABEL) { type = NavType.LongType; defaultValue = 0L },
                            ),
                        ) {
                            Box(Modifier.fillMaxSize()) { EditorScreen(onBack = { nav.popBackStack("home", inclusive = false) }) }
                        }
                        composable("labels") { LabelsScreen(onBack = { nav.popBackStack() }) }
                        composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingIntent = intent
    }

    private suspend fun handleIntent(intent: Intent, nav: NavHostController) {
        val app = application as JotterApp
        val route = when (intent.action) {
            ACTION_OPEN_NOTE -> intent.getLongExtra(ReminderScheduler.EXTRA_NOTE_ID, 0).takeIf { it > 0 }?.let { editorRoute(id = it) }
            ACTION_NEW_NOTE -> editorRoute()
            ACTION_NEW_LIST -> editorRoute(checklist = true)
            Intent.ACTION_SEND -> {
                // Text shared from another app becomes a new note.
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
                if (text.isBlank() && subject.isBlank()) null
                else editorRoute(id = app.repository.save(Note(title = subject, content = text), emptyList()))
            }
            else -> null
        } ?: return
        nav.popBackStack("home", inclusive = false)
        nav.navigate(route)
    }

    companion object {
        const val ACTION_OPEN_NOTE = "com.jotter.notes.OPEN_NOTE"
        const val ACTION_NEW_NOTE = "com.jotter.notes.NEW_NOTE"
        const val ACTION_NEW_LIST = "com.jotter.notes.NEW_LIST"

        private const val EDITOR_ROUTE = "editor?" +
            "${EditorViewModel.KEY_ID}={${EditorViewModel.KEY_ID}}&" +
            "${EditorViewModel.KEY_CHECKLIST}={${EditorViewModel.KEY_CHECKLIST}}&" +
            "${EditorViewModel.KEY_PICK_IMAGE}={${EditorViewModel.KEY_PICK_IMAGE}}&" +
            "${EditorViewModel.KEY_LABEL}={${EditorViewModel.KEY_LABEL}}"

        fun editorRoute(id: Long = 0, checklist: Boolean = false, pickImage: Boolean = false, labelId: Long = 0) =
            "editor?${EditorViewModel.KEY_ID}=$id&${EditorViewModel.KEY_CHECKLIST}=$checklist&" +
                "${EditorViewModel.KEY_PICK_IMAGE}=$pickImage&${EditorViewModel.KEY_LABEL}=$labelId"
    }
}
