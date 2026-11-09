package com.phonepal.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.phonepal.PhonePalApp
import com.phonepal.ai.ChatTurn
import com.phonepal.ai.Citation
import com.phonepal.ai.FileSearch
import com.phonepal.data.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatMessage(
    val fromUser: Boolean,
    val text: String,
    val citations: List<Citation> = emptyList(),
    val isError: Boolean = false,
)

/** Holds the conversation, so it survives switching tabs and opening an item. */
class AskViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as PhonePalApp
    val messages = mutableStateListOf<ChatMessage>()
    var busy by mutableStateOf(false)
        private set
    var only by mutableStateOf<Source?>(null)

    val doneCount = app.ragDao.doneCount()
    val indexedCount = app.ragDao.indexedCount()

    fun send(question: String) {
        val q = question.trim()
        if (q.isEmpty() || busy) return
        messages += ChatMessage(fromUser = true, text = q)
        // Recent turns give follow-up questions ("and the second one?") their context.
        val history = messages.filter { !it.isError }.takeLast(MAX_TURNS).map { ChatTurn(it.fromUser, it.text) }
        busy = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { FileSearch(app.settings).ask(history, only) } }
            result.onSuccess { messages += ChatMessage(fromUser = false, text = it.text, citations = it.citations) }
                .onFailure { messages += ChatMessage(fromUser = false, text = it.message ?: "Something went wrong", isError = true) }
            busy = false
        }
    }

    fun clear() {
        if (!busy) messages.clear()
    }

    private companion object {
        const val MAX_TURNS = 10
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskScreen(
    onOpen: (Long) -> Unit,
    bottomBar: @Composable () -> Unit = {},
    vm: AskViewModel = viewModel(),
) {
    val done by vm.doneCount.collectAsState(initial = null)
    val indexed by vm.indexedCount.collectAsState(initial = null)
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(vm.messages.size, vm.busy) {
        val last = vm.messages.size + (if (vm.busy) 1 else 0) - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ask your library") },
                actions = {
                    if (vm.messages.isNotEmpty()) IconButton(onClick = vm::clear, enabled = !vm.busy) {
                        Icon(Icons.Default.Delete, "Clear conversation")
                    }
                },
            )
        },
        bottomBar = bottomBar,
    ) { pad ->
        Column(Modifier.padding(pad).consumeWindowInsets(pad).imePadding().fillMaxSize()) {
            val d = done
            val i = indexed
            Text(
                when {
                    d == null || i == null -> "" // still loading
                    d == 0 -> "Nothing saved yet."
                    i < d -> "Indexed $i of $d saved items. The rest are being added in the background."
                    else -> "All $d saved items are searchable."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { FilterChip(selected = vm.only == null, onClick = { vm.only = null }, label = { Text("Everything") }) }
                items(Source.entries.toList()) { s ->
                    FilterChip(selected = vm.only == s, onClick = { vm.only = if (vm.only == s) null else s }, label = { Text(s.label) })
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (vm.messages.isEmpty() && !vm.busy) EmptyAsk()
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(vm.messages) { _, m -> Bubble(m, onOpen) }
                    if (vm.busy) item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("  Searching your library…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("What did I save about…") },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                )
                IconButton(
                    onClick = { vm.send(input); input = "" },
                    enabled = input.isNotBlank() && !vm.busy,
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Ask") }
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage, onOpen: (Long) -> Unit) {
    val colors = MaterialTheme.colorScheme
    if (m.fromUser) {
        // Your question: a bubble on the right.
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.CenterEnd) {
            Surface(
                shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
                color = colors.primaryContainer,
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                SelectionContainer {
                    Text(m.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
        }
        return
    }
    // The answer: plain full-width text, like a chat assistant.
    Column(Modifier.fillMaxWidth()) {
        if (m.isError) {
            Text(m.text, style = MaterialTheme.typography.bodyLarge, color = colors.error)
        } else {
            SelectionContainer { ChatMarkdown(m.text) }
        }
        if (m.citations.isNotEmpty()) {
            Text(
                "Sources",
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(m.citations) { c ->
                    AssistChip(
                        onClick = { c.itemId?.let(onOpen) },
                        enabled = c.itemId != null,
                        label = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyAsk() {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Ask anything about what you've saved", style = MaterialTheme.typography.titleMedium)
        Text(
            "e.g. \"What hooks have I saved for fitness reels?\"\n\"What did that video say about pricing?\"",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
