package com.phonepal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phonepal.data.Item
import com.phonepal.data.ItemRepository
import com.phonepal.data.Source
import com.phonepal.data.Status
import com.phonepal.work.ProcessWorker
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    repo: ItemRepository,
    onOpen: (Long) -> Unit,
    onSettings: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf<Source?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    val items by remember(query, source) { repo.search(query, source) }.collectAsState(initial = emptyList())
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PhonePal") },
                actions = {
                    IconButton(onClick = {
                        scope.launch { shareText(ctx, "PhonePal library", Markdown.library(repo.all())) }
                    }) { Icon(Icons.Default.Share, "Export library as Markdown") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, "Add link or note") }
        },
        bottomBar = bottomBar,
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("Search titles, ideas, tags, transcripts…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Clear") }
                },
                singleLine = true,
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { FilterChip(selected = source == null, onClick = { source = null }, label = { Text("All") }) }
                items(Source.entries) { s ->
                    FilterChip(selected = source == s, onClick = { source = if (source == s) null else s }, label = { Text(s.label) })
                }
            }
            if (items.isEmpty()) {
                EmptyState(filtered = query.isNotEmpty() || source != null)
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(items, key = { it.id }) { ItemCard(it) { onOpen(it.id) } }
                }
            }
        }
    }

    if (showAdd) AddDialog(
        onDismiss = { showAdd = false },
        onAdd = { text ->
            showAdd = false
            scope.launch {
                val (id, isNew) = repo.addFromShare(text)
                if (isNew) ProcessWorker.enqueue(ctx, id)
                onOpen(id)
            }
        },
    )
}

val Source.label: String
    get() = when (this) {
        Source.YOUTUBE -> "YouTube"
        Source.X -> "X"
        Source.WEB -> "Web"
        Source.APP -> "Apps"
        Source.NOTE -> "Notes"
    }

@Composable
private fun ItemCard(item: Item, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(item.source.label, item.author).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                item.title ?: item.url ?: "Untitled",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            when (item.status) {
                Status.DONE -> item.summary?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Status.FAILED -> Text(
                    "Failed: ${item.error ?: "unknown error"}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2,
                )
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.height(14.dp).width(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(item.status.label, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (item.tagList.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    item.tagList.joinToString("  ") { "#$it" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

val Status.label: String
    get() = when (this) {
        Status.QUEUED -> "Waiting for network…"
        Status.FETCHING -> "Fetching transcript / text…"
        Status.SUMMARIZING -> "Summarizing with Gemini…"
        Status.DONE -> "Done"
        Status.FAILED -> "Failed"
    }

@Composable
private fun EmptyState(filtered: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(if (filtered) "No matches" else "Nothing saved yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (!filtered) Text(
            "In YouTube or X, tap Share → Save to PhonePal. The transcript or post is fetched and turned into " +
                "a summary, key points and content ideas, saved here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AddDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a link or note") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text("Paste a YouTube / X / web link, or type an idea") },
                minLines = 3,
            )
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onAdd(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
