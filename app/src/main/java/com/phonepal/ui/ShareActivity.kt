package com.phonepal.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.phonepal.PhonePalApp
import com.phonepal.work.ProcessWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Target of "Share → Save to PhonePal". Saves the link, queues background processing,
 * shows a toast and closes, so you stay in YouTube / X.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = buildString {
            intent?.getStringExtra(Intent.EXTRA_SUBJECT)?.let { append(it).append('\n') }
            intent?.getStringExtra(Intent.EXTRA_TEXT)?.let { append(it) }
        }.trim()

        if (intent?.action != Intent.ACTION_SEND || text.isEmpty()) {
            Toast.makeText(this, "Nothing to save", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val app = application as PhonePalApp
        val appContext = applicationContext
        // Outlives this activity on purpose: the insert takes milliseconds, then WorkManager takes over.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val (id, isNew) = app.repo.addFromShare(text)
            if (isNew) ProcessWorker.enqueue(appContext, id)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    appContext,
                    if (isNew) "Saved to PhonePal — summarizing…" else "Already in PhonePal",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        finish()
    }
}
