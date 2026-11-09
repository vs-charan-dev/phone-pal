package com.phonepal.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phonepal.data.Item
import com.phonepal.data.ItemRepository
import com.phonepal.data.Status
import com.phonepal.work.ProcessWorker
import com.phonepal.work.RagSyncWorker
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(repo: ItemRepository, id: Long, onBack: () -> Unit) {
    val item by remember(id) { repo.observe(id) }.collectAsState(initial = null)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item?.source?.label ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    item?.let { it ->
                        IconButton(onClick = { ProcessWorker.enqueue(ctx, it.id, refetch = true) }) {
                            Icon(Icons.Default.Refresh, "Re-fetch and re-summarize")
                        }
                        IconButton(onClick = { shareText(ctx, it.title ?: "PhonePal note", Markdown.of(it)) }) {
                            Icon(Icons.Default.Share, "Share as Markdown")
                        }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                },
            )
        },
    ) { pad ->
        val it = item ?: return@Scaffold
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            if (it.status != Status.DONE && it.status != Status.FAILED) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(it.status.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            SelectionContainer {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Text(it.title ?: it.url ?: "Untitled", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        listOfNotNull(it.author, it.category, it.durationSec?.let { s -> "${s / 60} min" }).joinToString(" · "),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    )
                    if (it.tagList.isNotEmpty()) Text(
                        it.tagList.joinToString("  ") { t -> "#$t" },
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (it.status == Status.FAILED || (it.error != null && it.status == Status.QUEUED)) {
                        Spacer(Modifier.height(8.dp))
                        Text(it.error ?: "Failed", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    it.summary?.let { s -> Section("Summary") { Text(s, style = MaterialTheme.typography.bodyLarge) } }
                    Bullets("Key points", Item.lines(it.keyPoints))
                    Bullets("Content ideas", Item.lines(it.contentIdeas))
                    Bullets("Hooks", Item.lines(it.hooks))
                }
            }

            NoteEditor(it) { note ->
                scope.launch { repo.update(it.copy(myNote = note)); RagSyncWorker.enqueue(ctx, delaySec = 30) }
            }

            Row(Modifier.padding(vertical = 12.dp)) {
                it.url?.let { u -> OutlinedButton(onClick = { openUrl(ctx, u) }) { Text("Open original") } }
                Spacer(Modifier.padding(4.dp))
                OutlinedButton(onClick = { copy(ctx, Markdown.of(it)) }) { Text("Copy Markdown") }
                if (it.status == Status.FAILED) {
                    Spacer(Modifier.padding(4.dp))
                    OutlinedButton(onClick = { ProcessWorker.enqueue(ctx, it.id) }) { Text("Retry") }
                }
            }

            it.contentText?.takeIf { t -> t.isNotBlank() }?.let { text ->
                var expanded by remember { mutableStateOf(false) }
                HorizontalDivider()
                TextButton(onClick = { expanded = !expanded }) {
                    Text((if (expanded) "Hide " else "Show ") + (it.contentNote ?: "source text") + " · ${text.length / 1000}k chars")
                }
                if (expanded) SelectionContainer {
                    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 24.dp))
                }
            } ?: it.contentNote?.let { n ->
                Text(n, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this item?") },
        text = { Text("It will be removed from your library. This can't be undone.") },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                scope.launch { repo.delete(id); RagSyncWorker.enqueue(ctx); onBack() }
            }) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
    content()
}

@Composable
private fun Bullets(title: String, lines: List<String>) {
    if (lines.isEmpty()) return
    Section(title) {
        Column { lines.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) } }
    }
}

@Composable
private fun NoteEditor(item: Item, onSave: (String) -> Unit) {
    var note by remember(item.id) { mutableStateOf(item.myNote ?: "") }
    LaunchedEffect(item.myNote) { if (item.myNote != null && note.isEmpty()) note = item.myNote }
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = note,
        onValueChange = { note = it },
        label = { Text("My note / why I saved this") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
    )
    if (note != (item.myNote ?: "")) TextButton(onClick = { onSave(note) }) { Text("Save note") }
}

private fun copy(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("PhonePal", text))
    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}
