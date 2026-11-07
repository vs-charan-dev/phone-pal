package com.phonepal.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import android.os.Build
import androidx.lifecycle.lifecycleScope
import com.phonepal.PhonePalApp
import com.phonepal.data.Status
import com.phonepal.work.ProcessWorker
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as PhonePalApp
        setContent {
            PhonePalTheme {
                var openId by rememberSaveable { mutableStateOf<Long?>(null) }
                var tab by rememberSaveable { mutableStateOf(0) }
                var showSettings by rememberSaveable { mutableStateOf(app.settings.apiKey.isBlank()) }

                when {
                    showSettings -> {
                        BackHandler { showSettings = false }
                        SettingsScreen(app.settings, onClose = {
                            showSettings = false
                            // Items that were waiting only for an API key get processed now.
                            if (app.settings.apiKey.isNotBlank()) lifecycleScope.launch {
                                app.repo.all()
                                    .filter { it.status == Status.FAILED && it.error?.contains("API key") == true }
                                    .forEach { ProcessWorker.enqueue(this@MainActivity, it.id) }
                            }
                        })
                    }
                    openId != null -> {
                        BackHandler { openId = null }
                        DetailScreen(app.repo, openId!!, onBack = { openId = null })
                    }
                    else -> {
                        val nav: @Composable () -> Unit = {
                            NavigationBar {
                                NavigationBarItem(
                                    selected = tab == 0, onClick = { tab = 0 },
                                    icon = { Icon(Icons.AutoMirrored.Filled.List, null) }, label = { Text("Library") },
                                )
                                NavigationBarItem(
                                    selected = tab == 1, onClick = { tab = 1 },
                                    icon = { Icon(Icons.Default.DateRange, null) }, label = { Text("Insights") },
                                )
                            }
                        }
                        if (tab == 0) LibraryScreen(
                            repo = app.repo,
                            onOpen = { openId = it },
                            onSettings = { showSettings = true },
                            bottomBar = nav,
                        ) else InsightsScreen(onSettings = { showSettings = true }, bottomBar = nav)
                    }
                }
            }
        }
    }
}

@Composable
fun PhonePalTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

fun shareText(ctx: android.content.Context, subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    ctx.startActivity(Intent.createChooser(send, subject))
}

fun openUrl(ctx: android.content.Context, url: String) {
    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
