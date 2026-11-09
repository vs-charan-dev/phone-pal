package com.phonepal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import android.content.Intent
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.phonepal.ai.Summarizer
import com.phonepal.fetch.InstagramSession
import com.phonepal.data.Settings
import com.phonepal.tracking.PaymentGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(settings: Settings, onClose: () -> Unit) {
    var key by remember { mutableStateOf(settings.apiKey) }
    var model by remember { mutableStateOf(settings.model) }
    var profile by remember { mutableStateOf(settings.creatorProfile) }
    var extraPay by remember { mutableStateOf(settings.extraPaymentApps.joinToString(", ")) }
    val ctx = LocalContext.current
    val protectedApps = remember { PaymentGuard.installedProtected(ctx) }
    val canToggle = remember { PaymentGuard.canToggle(ctx) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelsStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var igLoggedIn by remember { mutableStateOf(InstagramSession.isLoggedIn()) }
    // Re-check when coming back from the login screen.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) igLoggedIn = InstagramSession.isLoggedIn() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "PhonePal fetches transcripts on this phone and sends the text to Gemini for the summary. " +
                    "Get a key at aistudio.google.com/apikey. It is stored only on this device.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = key, onValueChange = { key = it },
                label = { Text("Gemini API key (Google AI Studio)") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model, onValueChange = { model = it },
                label = { Text("Model") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Default \"${Settings.DEFAULT_MODEL}\" always tracks Google's current Flash model.") },
            )
            OutlinedButton(
                enabled = key.isNotBlank(),
                onClick = {
                    modelsStatus = "Loading…"
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { Summarizer.listModels(key.trim()) } }
                            .onSuccess { models = it; modelsStatus = if (it.isEmpty()) "No Gemini models found for this key" else null }
                            .onFailure { modelsStatus = "Couldn't load models: ${it.message}" }
                    }
                },
            ) { Text("Load my models") }
            modelsStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (models.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                models.forEach { m -> FilterChip(selected = model == m, onClick = { model = m }, label = { Text(m) }) }
            }
            OutlinedTextField(
                value = profile, onValueChange = { profile = it },
                label = { Text("About you as a creator (optional)") },
                placeholder = { Text("e.g. I make short videos about AI tools for students on Instagram + YouTube") },
                minLines = 3, modifier = Modifier.fillMaxWidth(),
            )
            Text("Instagram", style = MaterialTheme.typography.titleMedium)
            Text(
                "Instagram only shows reels to logged-in people. Log in once on Instagram's own page here and " +
                    "PhonePal can open the reels you save, transcribe them with Gemini and summarize them. " +
                    "Your password goes to Instagram, not PhonePal.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (igLoggedIn) {
                Text("Connected", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                OutlinedButton(onClick = { InstagramSession.logOut(); igLoggedIn = false }) { Text("Disconnect Instagram") }
            } else {
                Button(onClick = { ctx.startActivity(Intent(ctx, InstagramLoginActivity::class.java)) }) { Text("Connect Instagram") }
            }
            Text("Payment apps", style = MaterialTheme.typography.titleMedium)
            Text(
                "PhonePal never reads payment or banking apps. When one opens, PhonePal switches its accessibility " +
                    "service off so the app isn't blocked" +
                    (if (canToggle) ", and turns it back on when you leave." else
                        ". To have it turn back on by itself, run once from a computer:\n" +
                            "adb shell pm grant com.phonepal android.permission.WRITE_SECURE_SETTINGS\n" +
                            "Until then, turn it back on with the \"PhonePal tracking\" quick-settings tile."),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Protected on this phone: " + protectedApps.ifEmpty { listOf("none found") }.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = extraPay, onValueChange = { extraPay = it },
                label = { Text("More apps to leave alone (package names)") },
                placeholder = { Text("e.g. com.example.mybank, com.example.wallet") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    settings.extraPaymentApps = extraPay.split(',', '\n', ' ').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    settings.apiKey = key
                    settings.model = model
                    settings.creatorProfile = profile
                    onClose()
                },
                enabled = key.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }
}
