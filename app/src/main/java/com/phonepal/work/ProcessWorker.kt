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
            Result.success()
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

    private fun fetchContent(item: Item): Item {
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
            Source.WEB -> {
                val c = WebFetcher.fetch(url)
                item.copy(title = c.title, author = c.author, contentText = c.text, contentNote = c.note)
            }
            Source.APP, Source.NOTE -> item
        }
    }

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_REFETCH = "refetch"

        fun enqueue(context: Context, id: Long, refetch: Boolean = false) {
            val req = OneTimeWorkRequestBuilder<ProcessWorker>()
                .setInputData(workDataOf(KEY_ID to id, KEY_REFETCH to refetch))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("process-$id", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
