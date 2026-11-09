package com.phonepal.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.phonepal.PhonePalApp
import com.phonepal.ai.Summarizer
import com.phonepal.data.Item
import com.phonepal.data.LinkParser
import com.phonepal.data.Source
import com.phonepal.data.Status
import com.phonepal.fetch.InstagramFetcher
import com.phonepal.fetch.InstagramSession
import com.phonepal.fetch.InstagramWebGrabber
import com.phonepal.fetch.GrabbedReel
import com.phonepal.fetch.TweetFetcher
import com.phonepal.fetch.WebFetcher
import com.phonepal.fetch.YouTubeTranscript
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Fetch content → summarize with Gemini → store. Runs in the background, retries when offline. */
class ProcessWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as PhonePalApp
        val repo = app.repo
        val id = inputData.getLong(KEY_ID, -1)
        var item = repo.get(id) ?: return Result.success()
        val refetch = inputData.getBoolean(KEY_REFETCH, false)

        return try {
            // 1. Fetch (skip if we already have the text, unless a refetch was asked for)
            if (item.contentText.isNullOrBlank() || refetch) {
                item = item.copy(status = Status.FETCHING, error = null)
                repo.update(item)
                item = fetchContent(item)
                repo.update(item)
            }

            // 2. Summarize
            val settings = app.settings
            if (settings.apiKey.isBlank()) {
                repo.update(item.copy(status = Status.FAILED, error = "Add your Gemini API key in Settings, then tap Retry."))
                return Result.success()
            }
            item = item.copy(status = Status.SUMMARIZING, error = null)
            repo.update(item)
            val s = Summarizer(settings.apiKey, settings.model, settings.creatorProfile).summarize(item)
            repo.update(
                item.copy(
                    title = item.title ?: s.title,
                    summary = s.summary,
                    keyPoints = s.keyPoints.joinToString("\n"),
                    contentIdeas = s.contentIdeas.joinToString("\n"),
                    hooks = s.hooks.joinToString("\n"),
                    tags = s.tags.joinToString(", "),
                    category = s.category,
                    status = Status.DONE,
                    error = null,
                )
            )
            RagSyncWorker.enqueue(applicationContext)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // replaced by a newer run of the same item, or stopped by the system
        } catch (e: Exception) {
            Log.w("PhonePal", "Processing item $id failed", e)
            val transient = e is IOException
            if (transient && runAttemptCount < 4) {
                repo.update(item.copy(status = Status.QUEUED, error = "Will retry: ${e.message}"))
                Result.retry()
            } else {
                repo.update(item.copy(status = Status.FAILED, error = e.message ?: e.javaClass.simpleName))
                Result.success()
            }
        }
    }

    private suspend fun fetchContent(item: Item): Item {
        val url = item.url ?: return item // plain note: the shared text is the content
        return when (item.source) {
            Source.YOUTUBE -> {
                val vid = LinkParser.parse(url).youtubeId ?: return item
                val v = YouTubeTranscript.fetch(vid)
                val text = v.transcript ?: listOfNotNull(
                    v.description?.let { "Video description:\n$it" }
                ).joinToString("\n\n")
                item.copy(
                    title = v.title ?: item.title,
                    author = v.channel ?: item.author,
                    durationSec = v.lengthSec,
                    contentText = text.ifBlank { null },
                    contentNote = v.transcriptNote,
                )
            }
            Source.X -> {
                val c = TweetFetcher.fetch(url)
                item.copy(title = c.title, author = c.author, contentText = c.text, contentNote = c.note)
            }
            Source.INSTAGRAM -> fetchInstagram(item, url)
            Source.WEB -> {
                val c = WebFetcher.fetch(url)
                item.copy(title = c.title, author = c.author, contentText = c.text, contentNote = c.note)
            }
            Source.APP, Source.NOTE -> item
        }
    }

    /**
     * Caption + a Gemini transcript of the reel. With an Instagram login, the reel is opened in an
     * off-screen WebView (Instagram builds the page with JavaScript) to find its audio/video files.
     */
    private suspend fun fetchInstagram(item: Item, url: String): Item {
        val code = LinkParser.parse(url).instagramCode
            ?: return item.copy(contentNote = "Not a post/reel link, so there is nothing to transcribe")
        var post = InstagramFetcher.fetch(code, applicationContext)
        var stream: GrabbedReel.Stream? = post.videoUrl?.let { GrabbedReel.Stream(it, isAudio = false) }
        if (stream == null && InstagramSession.isLoggedIn()) {
            InstagramWebGrabber.grab(applicationContext, code)?.let { g ->
                stream = g.streams.firstOrNull()
                post = post.copy(caption = post.caption ?: g.caption, username = post.username ?: g.username)
            }
        }
        val settings = (applicationContext as PhonePalApp).settings
        if (stream == null && post.caption == null) {
            // Don't hand Gemini an empty item: it would invent a summary from the reel ID.
            throw IllegalStateException(
                if (InstagramSession.isLoggedIn()) "Instagram didn't return this reel (deleted, private, or the page changed). Tap Retry later."
                else "Instagram needs a login to show reels. Settings > Connect Instagram, then tap Retry."
            )
        }

        var transcript: String? = null
        val note = when {
            stream == null -> "Caption only: no video found in this post (photo post, or Instagram hid it)"
            settings.apiKey.isBlank() -> "Caption only: add a Gemini API key to transcribe the video"
            else -> try {
                val s = stream!!
                val (bytes, mime) = InstagramFetcher.download(s.url)
                transcript = Summarizer(settings.apiKey, settings.model, settings.creatorProfile)
                    .transcribe(bytes, if (s.isAudio) "audio/mp4" else mime)
                "Caption + transcript of the video (by Gemini, from ${bytes.size / 1024} KB of ${if (s.isAudio) "audio" else "video"})"
            } catch (e: IOException) {
                throw e // network / rate limit: let WorkManager retry
            } catch (e: Exception) {
                Log.w("PhonePal", "Reel transcription failed for $code", e)
                "Caption only: transcribing the video failed (${e.message})"
            }
        }
        val text = listOfNotNull(
            post.caption?.let { "Caption:\n$it" },
            transcript?.let { "Video transcript:\n$it" },
        ).joinToString("\n\n")
        return item.copy(
            title = post.caption?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(90) ?: item.title,
            // Old saves have the website ("www.instagram.com") as author; don't keep that.
            author = post.username?.let { "@$it" } ?: item.author?.takeUnless { it.contains("instagram.com") },
            contentText = text.ifBlank { null },
            contentNote = note,
        )
    }

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_REFETCH = "refetch"

        fun enqueue(context: Context, id: Long, refetch: Boolean = false, delaySec: Long = 0) {
            val req = OneTimeWorkRequestBuilder<ProcessWorker>()
                .setInputData(workDataOf(KEY_ID to id, KEY_REFETCH to refetch))
                .setInitialDelay(delaySec, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("process-$id", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
