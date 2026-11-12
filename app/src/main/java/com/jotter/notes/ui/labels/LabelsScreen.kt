package com.jotter.notes.ui.labels

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jotter.notes.JotterApp
import com.jotter.notes.data.Label
import com.jotter.notes.ui.components.ConfirmDialog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelsScreen(onBack: () -> Unit) {
    val repo = (LocalContext.current.applicationContext as JotterApp).repository
    val labels by repo.labels.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var newName by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Label?>(null) }

    val transparent = TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
    )
    val create = {
        val name = newName
        newName = ""
        scope.launch { repo.createLabel(name) }
        Unit
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Edit labels") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Navigate up") } },
        )
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Add, null, Modifier.padding(12.dp))
                    TextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = { Text("Create new label") },
                        singleLine = true,
                        colors = transparent,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { create() }),
                        modifier = Modifier.weight(1f).testTag("newLabel"),
                    )
                    if (newName.isNotBlank()) IconButton(onClick = create) { Icon(Icons.Outlined.Check, "Create label") }
                }
            }
            items(labels, key = { it.id }) { label ->
                // Keyed on the stored name so an external rename resets the field.
                var name by remember(label.name) { mutableStateOf(label.name) }
                val changed = name.trim() != label.name && name.isNotBlank()
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Outlined.Label, null, Modifier.padding(12.dp))
                    TextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        colors = transparent,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            scope.launch { if (!repo.renameLabel(label.id, name)) name = label.name }
                        }),
                        modifier = Modifier.weight(1f),
                    )
                    if (changed) {
                        IconButton(onClick = { scope.launch { if (!repo.renameLabel(label.id, name)) name = label.name } }) {
                            Icon(Icons.Outlined.Check, "Rename label")
                        }
                    } else {
                        Icon(Icons.Outlined.Edit, null, Modifier.padding(12.dp))
                    }
                    IconButton(onClick = { deleting = label }) { Icon(Icons.Outlined.Delete, "Delete label ${label.name}") }
                }
            }
        }
    }

    deleting?.let { label ->
        ConfirmDialog(
            title = "Delete label?",
            text = "\"${label.name}\" will be removed from all notes. The notes themselves are kept.",
            confirm = "Delete",
            onConfirm = { scope.launch { repo.deleteLabel(label.id) }; deleting = null },
            onDismiss = { deleting = null },
        )
    }
}
