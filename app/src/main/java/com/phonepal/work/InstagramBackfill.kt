package com.phonepal.work

import android.content.Context
import android.util.Log
import com.phonepal.PhonePalApp
import com.phonepal.data.LinkParser
import com.phonepal.data.Source
import com.phonepal.data.Status

/**
 * Instagram links saved before reel transcription existed were stored as plain web pages and
 * only got Instagram's login-page title. This re-labels them as Instagram and re-runs them
 * (caption + video transcript → summary). Runs once per version of this backfill.
 */
object InstagramBackfill {
    private const val KEY = "instagram_backfill_v1"
    private const val KEY_WEBVIEW = "instagram_webview_retry_v2"

    /** Spread the work out so the free Gemini tier isn't hit with everything at once. */
    private const val SPACING_SEC = 20L

    suspend fun runOnce(context: Context) {
        val app = context.applicationContext as PhonePalApp
        val prefs = app.settings.prefs
        // v2: reels are now read through the logged-in WebView; re-run what failed with the old method.
        if (prefs.getBoolean(KEY, false)) {
            if (!prefs.getBoolean(KEY_WEBVIEW, false) && com.phonepal.fetch.InstagramSession.isLoggedIn()) {
                prefs.edit().putBoolean(KEY_WEBVIEW, true).apply()
                retryUntranscribed(context, alsoBadMeta = true)
            }
            return
        }

        var n = 0
        app.repo.all().forEach { item ->
            val link = LinkParser.parse(item.url ?: item.sharedText)
            if (link.source != Source.INSTAGRAM) return@forEach
            app.repo.update(
                item.copy(
                    url = link.url ?: item.url,
                    source = Source.INSTAGRAM,
                    // The old title was Instagram's login page; let the new fetch set a real one.
                    title = null,
                    status = Status.QUEUED,
                    error = null,
                )
            )
            ProcessWorker.enqueue(context, item.id, refetch = true, delaySec = n * SPACING_SEC)
            n++
        }
        Log.i("PhonePal", "Instagram backfill: re-processing $n saved items")
        prefs.edit().putBoolean(KEY, true).apply()
    }

    /** After you connect Instagram: re-run every Instagram item that doesn't have a video transcript yet. */
    suspend fun retryUntranscribed(context: Context, alsoBadMeta: Boolean = false) {
        val app = context.applicationContext as PhonePalApp
        var n = 0
        app.repo.all()
            .filter { it.source == Source.INSTAGRAM }
            .filter {
                it.contentNote?.startsWith("Caption + transcript") != true ||
                    (alsoBadMeta && it.author?.contains("instagram.com") == true)
            }
            .forEach { item ->
                app.repo.update(item.copy(title = null, status = Status.QUEUED, error = null))
                ProcessWorker.enqueue(context, item.id, refetch = true, delaySec = n * SPACING_SEC)
                n++
            }
        Log.i("PhonePal", "Instagram connected: re-processing $n items")
    }
}
