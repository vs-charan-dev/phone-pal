package com.phonepal.tracking

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** What each media app is playing right now (used by the bubble and like-capture). */
object NowPlaying {
    data class Track(val title: String, val artist: String?, val at: Long)
    private val map = ConcurrentHashMap<String, Track>()
    fun set(pkg: String, title: String, artist: String?) { map[pkg] = Track(title, artist, System.currentTimeMillis()) }
    fun get(pkg: String, maxAgeMs: Long = 6 * 60 * 60 * 1000L): Track? =
        map[pkg]?.takeIf { System.currentTimeMillis() - it.at < maxAgeMs }
}

/**
 * Notification access lets us read every app's MediaSession: title + channel + play/pause.
 * That is exactly "what am I watching and for how long", without scraping any UI.
 */
class MediaListenerService : NotificationListenerService() {
    private var tracker: MediaTracker? = null

    override fun onListenerConnected() {
        tracker = MediaTracker(this, TrackingDatabase.get(this).dao()).also { it.start() }
    }

    override fun onListenerDisconnected() {
        tracker?.stop()
        tracker = null
    }

    override fun onDestroy() {
        tracker?.stop()
        super.onDestroy()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MediaTracker(private val ctx: Context, private val dao: TrackingDao) {
    // All state changes are serialized on one thread, so no locking is needed.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val msm = ctx.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(ctx, MediaListenerService::class.java)
    private val tracked = HashMap<MediaSession.Token, Tracked>()
    private var ticker: Job? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        scope.launch { onSessions(list ?: emptyList()) }
    }

    fun start() {
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, component)
            val initial = msm.getActiveSessions(component)
            scope.launch { onSessions(initial) }
        } catch (e: SecurityException) {
            Log.w(TAG, "No notification access yet", e)
        }
        ticker = scope.launch {
            while (isActive) {
                delay(60_000)
                tracked.values.forEach { it.flush() }
            }
        }
    }

    fun stop() {
        runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
        val all = tracked.values.toList()
        tracked.clear()
        scope.launch {
            all.forEach { it.release(); it.pause() }
            scope.cancel()
        }
    }

    private suspend fun onSessions(list: List<MediaController>) {
        val tokens = list.map { it.sessionToken }.toSet()
        (tracked.keys - tokens).forEach { t -> tracked.remove(t)?.let { it.pause(); it.release() } }
        list.filter { it.sessionToken !in tracked }.forEach { c ->
            val t = Tracked(c)
            tracked[c.sessionToken] = t
            t.register()
        }
    }

    private inner class Tracked(val controller: MediaController) {
        val pkg: String = controller.packageName
        var title: String? = null
        var sessionId: Long? = null
        var playingSince: Long? = null

        private val cb = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) { scope.launch { onMetadata(metadata) } }
            override fun onPlaybackStateChanged(state: PlaybackState?) { scope.launch { onState(state) } }
            override fun onSessionDestroyed() { scope.launch { pause() } }
        }

        suspend fun register() {
            controller.registerCallback(cb, android.os.Handler(android.os.Looper.getMainLooper()))
            onMetadata(controller.metadata)
            onState(controller.playbackState)
        }

        fun release() = runCatching { controller.unregisterCallback(cb) }

        suspend fun onMetadata(md: MediaMetadata?) {
            md ?: return
            val t = (md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.description?.title?.toString())?.trim()
            if (t.isNullOrEmpty()) return
            val artist = (md.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: md.description?.subtitle?.toString())?.trim()?.ifEmpty { null }
            if (t == title) {
                // YouTube often sends the title first and the channel a moment later.
                if (artist != null) {
                    NowPlaying.set(pkg, t, artist)
                    sessionId?.let { dao.setArtist(it, artist) }
                }
                return
            }
            val duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 }
            NowPlaying.set(pkg, t, artist)

            val wasPlaying = playingSince != null
            pause()                       // close out time on the previous title
            title = t
            val now = System.currentTimeMillis()
            // Coming back to the same video within 30 min continues the same session.
            sessionId = dao.recentSession(pkg, t, now - 30 * 60_000)?.id
                ?: dao.insertSession(WatchSession(pkg = pkg, title = t, artist = artist, durationMs = duration, startedAt = now, lastActiveAt = now))
            if (wasPlaying || controller.playbackState?.state == PlaybackState.STATE_PLAYING) playingSince = now
        }

        suspend fun onState(state: PlaybackState?) {
            val playing = state?.state == PlaybackState.STATE_PLAYING
            if (playing && playingSince == null) playingSince = System.currentTimeMillis()
            if (!playing) pause()
        }

        /** Write accumulated play time, keep counting. */
        suspend fun flush() {
            val id = sessionId ?: return
            val since = playingSince ?: return
            val now = System.currentTimeMillis()
            dao.addPlayed(id, now - since, now)
            playingSince = now
        }

        suspend fun pause() {
            flush()
            playingSince = null
        }
    }

    companion object { private const val TAG = "PhonePal.Media" }
}
