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
import com.phonepal.PhonePalApp
import com.phonepal.ai.FileSearch
import com.phonepal.ai.PermanentAiException
import com.phonepal.ai.StoreMissingException
import com.phonepal.data.RagDoc
import kotlinx.coroutines.delay
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Keeps the Gemini File Search store in step with the library: uploads finished items that are
 * new or changed (re-summarized, note edited) and removes documents of deleted items.
 * The first run after an update indexes the whole existing library, a few items at a time.
 */
class RagSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as PhonePalApp
        if (app.settings.apiKey.isBlank()) return Result.success()
        val dao = app.ragDao
        val fs = FileSearch(app.settings)
        // WorkManager stops a worker after 10 minutes; hand over to a fresh run before that.
        val deadline = System.currentTimeMillis() + 8 * 60_000

        return try {
            if (fs.storeName() == null) dao.clear() // first run, or a different API key: start over
            val store = fs.ensureStore()

            dao.orphans().forEach { doc ->
                fs.deleteDoc(doc.docName)
                dao.delete(doc.itemId)
            }

            var n = 0
            while (System.currentTimeMillis() < deadline) {
                val batch = dao.dirty(BATCH)
                if (batch.isEmpty()) {
                    if (n > 0) Log.i("PhonePal", "File Search: indexed $n items")
                    return Result.success()
                }
                for (item in batch) {
                    if (System.currentTimeMillis() >= deadline) break
                    val old = dao.get(item.id)
                    val doc = try {
                        fs.upload(store, item)
                    } catch (e: PermanentAiException) {
                        // Don't let one bad item block the rest: mark this version as skipped.
                        Log.w("PhonePal", "File Search: skipping item ${item.id}", e)
                        ""
                    }
                    dao.put(RagDoc(item.id, doc, item.updatedAt))
                    // Remove the old version only after the new one is in, so the item never disappears from search.
                    if (old != null && old.docName.isNotBlank() && old.docName != doc) {
                        runCatching { fs.deleteDoc(old.docName) }
                            .onFailure { Log.w("PhonePal", "File Search: couldn't delete ${old.docName}", it) }
                    }
                    n++
                    delay(SPACING_MS)
                }
            }
            enqueue(applicationContext) // more left: continue in a new run
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: StoreMissingException) {
            Log.w("PhonePal", "File Search store is gone; rebuilding", e)
            fs.forgetStore()
            dao.clear()
            Result.retry()
        } catch (e: IOException) {
            Log.w("PhonePal", "File Search sync failed (will retry)", e)
            if (runAttemptCount < 6) Result.retry() else Result.success()
        } catch (e: Exception) {
            Log.w("PhonePal", "File Search sync failed", e)
            Result.success()
        }
    }

    companion object {
        private const val BATCH = 20
        /** Gentle on the free tier: indexing makes embedding calls. */
        private const val SPACING_MS = 1500L

        /** Safe to call often: runs queue up behind each other and a run with nothing to do ends at once. */
        fun enqueue(context: Context, delaySec: Long = 0) {
            val req = OneTimeWorkRequestBuilder<RagSyncWorker>()
                .setInitialDelay(delaySec, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("rag-sync", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}
