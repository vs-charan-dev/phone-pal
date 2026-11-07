package com.phonepal.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import android.widget.Toast
import com.phonepal.PhonePalApp
import com.phonepal.ai.Summarizer
import com.phonepal.tracking.Capture
import com.phonepal.tracking.Permissions
import com.phonepal.tracking.ScreenTime
import com.phonepal.tracking.TrackingDatabase
import com.phonepal.tracking.WatchSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

private enum class Period(val label: String) { TODAY("Today"), WEEK("7 days") }

private data class Insights(
    val screen: List<ScreenTime.AppTime>,
    val screenTotalMs: Long,
    val watched: List<WatchSession>,
    val postsSeen: List<Pair<String, Int>>,
    val likesSaved: Int,
    val savedCount: Int,
    val categories: List<Pair<String, Int>>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(onSettings: () -> Unit, bottomBar: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PhonePalApp
    val scope = rememberCoroutineScope()
    var period by rememberSaveable { mutableStateOf(Period.TODAY) }
    var refresh by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<Insights?>(null) }
    var perms by remember { mutableStateOf(Triple(false, false, false)) }
    var reflection by remember { mutableStateOf<String?>(null) }
    var reflecting by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    LaunchedEffect(period, refresh) {
        perms = Triple(Permissions.notificationAccess(ctx), Permissions.accessibility(ctx), Permissions.usageAccess(ctx))
        data = withContext(Dispatchers.IO) { load(ctx, startOf(period), perms.third) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Insights") },
                actions = {
                    IconButton(onClick = { refresh++ }) { Icon(Icons.Default.Refresh, "Refresh") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
        bottomBar = bottomBar,
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Period.entries.forEach { p -> FilterChip(selected = period == p, onClick = { period = p }, label = { Text(p.label) }) }
            }

            if (!perms.first || !perms.second || !perms.third) SetupCard(ctx, perms)
            TrackingToggles(app)

            val d = data
            if (d == null) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }

            // ---- Screen time
            if (perms.third) SectionCard("Screen time · ${fmt(d.screenTotalMs)}") {
                val max = d.screen.firstOrNull()?.ms ?: 1
                d.screen.take(8).forEach { a -> Bar(a.label, fmt(a.ms), a.ms.toFloat() / max) }
                if (d.screen.isEmpty()) Muted("No app usage recorded yet.")
            }

            // ---- Watching
            val ytMs = d.watched.filter { it.pkg == Capture.YOUTUBE }.sumOf { it.playedMs }
            SectionCard("Watched · ${fmt(d.watched.sumOf { it.playedMs })} (YouTube ${fmt(ytMs)})") {
                if (!perms.first) { Muted("Turn on notification access to see what you watch."); return@SectionCard }
                if (d.watched.isEmpty()) { Muted("Nothing watched yet in this period."); return@SectionCard }
                val channels = d.watched.groupBy { it.artist ?: "Unknown" }
                    .mapValues { (_, v) -> v.sumOf { it.playedMs } }
                    .entries.sortedByDescending { it.value }.take(5)
                Text("Top channels", style = MaterialTheme.typography.labelLarge)
                val maxC = channels.firstOrNull()?.value ?: 1
                channels.forEach { (ch, ms) -> Bar(ch, fmt(ms), ms.toFloat() / maxC) }
                Spacer(Modifier.height(8.dp))
                Text("Videos", style = MaterialTheme.typography.labelLarge)
                d.watched.take(25).forEach { w -> WatchRow(w) { scope.launch { toast(ctx, Capture.saveYouTube(ctx, w.title, w.artist)) } } }
            }

            // ---- Reading
            SectionCard("Feeds · ${d.postsSeen.sumOf { it.second }} posts seen") {
                if (!perms.second) { Muted("Turn on the accessibility service to count posts you read."); return@SectionCard }
                if (d.postsSeen.isEmpty()) Muted("No feed scrolling recorded yet.")
                d.postsSeen.forEach { (label, n) -> Text("$label: $n posts", style = MaterialTheme.typography.bodyMedium) }
                Text("${d.likesSaved} liked posts auto-saved", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // ---- Library
            SectionCard("Saved to library · ${d.savedCount}") {
                if (d.categories.isEmpty()) Muted("Nothing saved in this period.")
                d.categories.forEach { (c, n) -> Text("$c: $n", style = MaterialTheme.typography.bodyMedium) }
            }

            // ---- AI reflection
            SectionCard("Reflection") {
                when {
                    reflecting -> CircularProgressIndicator(Modifier.height(24.dp).width(24.dp))
                    reflection != null -> Text(reflection!!, style = MaterialTheme.typography.bodyMedium)
                    else -> Muted("Ask Gemini what these numbers say and what to change.")
                }
                OutlinedButton(
                    enabled = !reflecting && app.settings.apiKey.isNotBlank(),
                    onClick = {
                        reflecting = true
                        scope.launch {
                            reflection = runCatching {
                                withContext(Dispatchers.IO) {
                                    Summarizer(app.settings.apiKey, app.settings.model, app.settings.creatorProfile)
                                        .reflect(statsText(period, d))
                                }
                            }.getOrElse { "Couldn't get a reflection: ${it.message}" }
                            reflecting = false
                        }
                    },
                ) { Text(if (reflection == null) "Reflect on ${period.label.lowercase()}" else "Ask again") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SetupCard(ctx: Context, perms: Triple<Boolean, Boolean, Boolean>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Finish setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            PermRow("Notification access", "What you watch & for how long", perms.first) { ctx.startActivity(Permissions.notificationIntent) }
            PermRow("Accessibility · PhonePal", "Posts you read, auto-save likes, floating bubble", perms.second) { ctx.startActivity(Permissions.accessibilityIntent) }
            PermRow("Usage access", "Screen time per app", perms.third) { ctx.startActivity(Permissions.usageIntent) }
            if (!perms.second) Text(
                "If Android says the setting is restricted: Settings → Apps → PhonePal → ⋮ → Allow restricted settings, then try again.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PermRow(title: String, what: String, granted: Boolean, open: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text((if (granted) "✓ " else "") + title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(what, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!granted) TextButton(onClick = open) { Text("Turn on") }
    }
}

@Composable
private fun TrackingToggles(app: PhonePalApp) {
    val s = app.settings
    var bubble by remember { mutableStateOf(s.showBubble) }
    var likes by remember { mutableStateOf(s.autoSaveLikes) }
    var reading by remember { mutableStateOf(s.trackReading) }
    SectionCard("Capture") {
        Toggle("Floating bubble (tap = save what's on screen, long-press = open app)", bubble) { bubble = it; s.showBubble = it }
        Toggle("Auto-save posts & videos I like", likes) { likes = it; s.autoSaveLikes = it }
        Toggle("Count posts I scroll past in X / Instagram / Reddit / LinkedIn", reading) { reading = it; s.trackReading = it }
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun Bar(label: String, value: String, fraction: Float) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(
            Modifier.fillMaxWidth().height(6.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp))
        ) {
            Box(
                Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(6.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp))
            )
        }
    }
}

@Composable
private fun WatchRow(w: WatchSession, onSave: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(w.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(w.artist, fmt(w.playedMs)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (w.pkg == Capture.YOUTUBE) TextButton(onClick = onSave) { Text("Save") }
    }
    HorizontalDivider()
}

@Composable
private fun Muted(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

private fun startOf(p: Period): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    if (p == Period.WEEK) add(Calendar.DAY_OF_YEAR, -6)
}.timeInMillis

private suspend fun load(ctx: Context, start: Long, usage: Boolean): Insights {
    val app = ctx.applicationContext as PhonePalApp
    val dao = TrackingDatabase.get(ctx).dao()
    val screen = if (usage) runCatching { ScreenTime.foreground(ctx, start) }.getOrDefault(emptyList()) else emptyList()
    val items = app.repo.since(start)
    return Insights(
        screen = screen,
        screenTotalMs = screen.sumOf { it.ms },
        watched = dao.watchedSince(start),
        postsSeen = dao.postCountsSince(start).map { Capture.appLabel(ctx, it.pkg) to it.n },
        likesSaved = dao.likedSince(start),
        savedCount = items.size,
        categories = items.groupBy { it.category ?: "uncategorized" }.map { it.key to it.value.size }.sortedByDescending { it.second },
    )
}

private fun statsText(p: Period, d: Insights): String = buildString {
    append("Period: ").append(p.label).append('\n')
    append("Total screen time: ").append(fmt(d.screenTotalMs)).append('\n')
    d.screen.take(10).forEach { append("- ").append(it.label).append(": ").append(fmt(it.ms)).append('\n') }
    append("\nWatched (media apps): ").append(fmt(d.watched.sumOf { it.playedMs })).append('\n')
    d.watched.take(40).forEach {
        append("- ").append(it.title).append(" — ").append(it.artist ?: "?").append(" (").append(fmt(it.playedMs)).append(")\n")
    }
    append("\nFeed posts seen: ").append(d.postsSeen.joinToString { "${it.first} ${it.second}" }).append('\n')
    append("Liked posts auto-saved: ").append(d.likesSaved).append('\n')
    append("Items saved to idea library: ").append(d.savedCount)
    append(" (").append(d.categories.joinToString { "${it.first} ${it.second}" }).append(")\n")
}

private fun fmt(ms: Long): String {
    val m = ms / 60_000
    return when {
        ms in 1 until 60_000 -> "${ms / 1000}s"
        m < 60 -> "${m}m"
        else -> "${m / 60}h ${m % 60}m"
    }
}

private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
