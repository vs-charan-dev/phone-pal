package com.phonepal.tracking

import android.content.Context
import android.util.Log
import com.phonepal.PhonePalApp
import com.phonepal.data.Source
import com.phonepal.fetch.YouTubeSearch
import com.phonepal.work.ProcessWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** Saving things into the library from tracking (bubble taps, likes, the Insights "Save" button). */
object Capture {
    const val YOUTUBE = "com.google.android.youtube"
    const val X = "com.twitter.android"
    val FEED_APPS = setOf(X, "com.instagram.android", "com.instagram.lite", "com.reddit.frontpage", "com.linkedin.android")

    /** Resolves the video from title + channel and runs the normal transcript → summary pipeline. */
    suspend fun saveYouTube(ctx: Context, title: String, channel: String?): String = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext as PhonePalApp
        val videoId = runCatching { YouTubeSearch.findVideoId(title, channel) }
            .onFailure { Log.w("PhonePal", "YouTube search failed", it) }
            .getOrNull()
        if (videoId != null) {
            val (id, isNew) = app.repo.addFromShare("https://www.youtube.com/watch?v=$videoId")
            if (isNew) ProcessWorker.enqueue(ctx, id)
            if (isNew) "Saved: $title" else "Already saved: $title"
        } else {
            val id = app.repo.addCaptured(
                Source.YOUTUBE, title, channel,
                "YouTube video \"$title\"" + (channel?.let { " by $it" } ?: ""),
                "Couldn't find the video link, so this is summarized from the title only",
            )
            ProcessWorker.enqueue(ctx, id)
            "Saved (title only): $title"
        }
    }

    /** Saves text read from another app's screen. Returns the new item id. */
    suspend fun saveScreenText(ctx: Context, pkg: String, text: String, why: String): Long = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext as PhonePalApp
        val label = appLabel(ctx, pkg)
        val source = if (pkg == X) Source.X else Source.APP
        val title = text.lineSequence().map { it.trim() }.firstOrNull { it.length >= 25 }?.take(90)
            ?: text.lineSequence().firstOrNull()?.take(90)
        val id = app.repo.addCaptured(source, title, label, text, "$why in $label (text read from the screen)")
        ProcessWorker.enqueue(ctx, id)
        id
    }

    fun appLabel(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        when (pkg) {
            X -> "X"
            YOUTUBE -> "YouTube"
            else -> pkg.substringAfterLast('.')
        }
    }

    /** Stable id for a post: ignores digits (like counts / "2h" timestamps change while you scroll). */
    fun hash(pkg: String, text: String): String {
        val norm = pkg + "|" + text.replace(Regex("[0-9.,·]+"), "").replace(Regex("\\s+"), " ").trim().lowercase()
        return MessageDigest.getInstance("SHA-1").digest(norm.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
