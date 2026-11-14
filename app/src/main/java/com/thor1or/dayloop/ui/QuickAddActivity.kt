package com.thor1or.dayloop.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.thor1or.dayloop.logic.NlParser
import com.thor1or.dayloop.repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime

/**
 * A small sheet over whatever is on screen: type a task, press enter, and it is gone again.
 * Reached from the home-screen widget, the launcher shortcut and other apps' Share menu.
 */
class QuickAddActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val shared = if (intent.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty() else ""
        val order = repo.prefs.settings.value.dateOrder

        setContent {
            DayloopTheme(repo.prefs.settings.value.theme) {
                var text by remember { mutableStateOf(shared.lines().firstOrNull().orEmpty().take(200)) }
                val parsed = remember(text) { NlParser.parse(text, LocalDate.now(), LocalTime.now(), order) }
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }

                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish() }
                        .navigationBarsPadding()
                        .imePadding(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Surface(
                        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                        tonalElevation = 3.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Add a task", style = MaterialTheme.typography.titleMedium)
                            QuickAddField(
                                value = text,
                                onValueChange = { text = it },
                                parsed = parsed,
                                placeholder = "buy bread at 9am on 10/12",
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .focusRequester(focus),
                            ) {
                                if (parsed.title.isNotBlank()) {
                                    lifecycleScope.launch {
                                        withContext(Dispatchers.IO) { repo.addTask(parsed) }
                                        Toast.makeText(this@QuickAddActivity, "Task added", Toast.LENGTH_SHORT).show()
                                        finish()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
