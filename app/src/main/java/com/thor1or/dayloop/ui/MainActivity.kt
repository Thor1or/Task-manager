@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.thor1or.dayloop.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val tab = mutableStateOf("plan")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        tab.value = savedInstanceState?.getString(EXTRA_TAB) ?: intent.getStringExtra(EXTRA_TAB) ?: "plan"
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            val dark = isDark(settings.theme)
            // Status and navigation bar icons follow the chosen theme, not only the system's.
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            DayloopTheme(settings.theme) {
                val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                AppRoot(vm, tab.value) { tab.value = it }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_TAB)?.let { tab.value = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(EXTRA_TAB, tab.value)
    }

    companion object {
        const val EXTRA_TAB = "tab"
    }
}

private data class Tab(val id: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("plan", "Plan", Icons.Default.DateRange),
    Tab("tasks", "Tasks", Icons.AutoMirrored.Filled.List),
    Tab("habits", "Habits", Icons.Default.CheckCircle),
    Tab("stats", "Review", Icons.Default.Info),
    Tab("settings", "Settings", Icons.Default.Settings),
)

@Composable
fun AppRoot(vm: AppViewModel, tab: String, onTab: (String) -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(enabled = tab != "plan") { onTab("plan") }
    val typing = WindowInsets.isImeVisible
    // Kept here, not in the bar: the bar leaves the screen while the keyboard is up.
    var pickingTimer by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            // The bars step aside while the keyboard is up, so input fields sit right above it.
            if (!typing) {
                Column {
                    TimerBar(vm) { pickingTimer = true }
                    NavigationBar {
                        TABS.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t.id,
                                onClick = { onTab(t.id) },
                                icon = { Icon(t.icon, contentDescription = null) },
                                label = { Text(t.label, maxLines = 1) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (tab) {
                "tasks" -> TasksScreen(vm)
                "habits" -> HabitsScreen(vm)
                "stats" -> StatsScreen(vm)
                "settings" -> SettingsScreen(vm)
                else -> PlanScreen(vm)
            }
        }
    }
    if (pickingTimer) TimerPicker(vm) { pickingTimer = false }
}
