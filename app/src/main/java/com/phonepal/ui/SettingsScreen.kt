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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.phonepal.ai.Summarizer
import com.phonepal.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(settings: Settings, onClose: () -> Unit) {
    var key by remember { mutableStateOf(settings.apiKey) }
    var model by remember { mutableStateOf(settings.model) }
    var profile by remember { mutableStateOf(settings.creatorProfile) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelsStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

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
            Button(
                onClick = {
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
