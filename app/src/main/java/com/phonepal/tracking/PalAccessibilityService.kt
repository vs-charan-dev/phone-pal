package com.phonepal.tracking

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.ImageView
import android.widget.Toast
import com.phonepal.PhonePalApp
import com.phonepal.R
import com.phonepal.data.Settings
import com.phonepal.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Reads other apps' screens (only on your phone, only into the local database):
 *  - posts that were fully on screen in X / Instagram / Reddit / LinkedIn  → "what did I read"
 *  - taps on a Like button → the liked post/video is saved to the library automatically
 *  - a floating bubble: tap it to save whatever you're looking at
 */
class PalAccessibilityService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var settings: Settings
    private var lastScan = 0L
    private var bubble: ImageView? = null

    override fun onServiceConnected() {
        settings = (application as PhonePalApp).settings
        settings.prefs.registerOnSharedPreferenceChangeListener(this)
        if (settings.showBubble) showBubble()
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        if (key == Settings.KEY_BUBBLE) if (settings.showBubble) showBubble() else hideBubble()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (::settings.isInitialized) settings.prefs.unregisterOnSharedPreferenceChangeListener(this)
        hideBubble()
        scope.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> if (settings.autoSaveLikes) onClick(pkg, e)
            AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                if (settings.trackReading && pkg in Capture.FEED_APPS) maybeScan(pkg)
        }
    }

    // ---------------------------------------------------------------- reading tracker

    private fun maybeScan(pkg: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastScan < 1500) return
        lastScan = now
        val root = rootInActiveWindow ?: return
        val list = mainList(root) ?: return
        val listRect = Rect().also { list.getBoundsInScreen(it) }
        val posts = mutableListOf<String>()
        for (i in 0 until list.childCount) {
            val child = list.getChild(i) ?: continue
            val r = Rect().also { child.getBoundsInScreen(it) }
            // Only count posts that were fully on screen and big enough to be a post.
            if (r.top >= listRect.top && r.bottom <= listRect.bottom && r.height() > 120) {
                val text = gatherText(child)
                if (text.length >= 40) posts += text
            }
        }
        if (posts.isEmpty()) return
        val dao = TrackingDatabase.get(this).dao()
        val at = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            posts.forEach { dao.insertPost(SeenPost(pkg = pkg, hash = Capture.hash(pkg, it), text = it.take(2000), seenAt = at)) }
        }
    }

    // ---------------------------------------------------------------- likes

    private fun onClick(pkg: String, e: AccessibilityEvent) {
        val node = e.source
        val desc = (e.contentDescription ?: node?.contentDescription)?.toString() ?: return
        if (desc.length > 80 || !LIKE.containsMatchIn(desc)) return
        Log.i(TAG, "Like tap in $pkg: $desc")

        if (pkg == Capture.YOUTUBE) {
            val now = NowPlaying.get(pkg) ?: return toast("Liked, but couldn't tell which video. Use the bubble.")
            scope.launch {
                val msg = Capture.saveYouTube(this@PalAccessibilityService, now.title, now.artist)
                toast("❤ $msg")
            }
            return
        }
        node ?: return
        val text = gatherText(listItemOf(node))
        if (text.length < 20) return
        val hash = Capture.hash(pkg, text)
        val dao = TrackingDatabase.get(this).dao()
        scope.launch {
            val already = withContext(Dispatchers.IO) { dao.findPost(hash)?.itemId }
            if (already != null) return@launch   // liked before (or this tap was an un-like)
            val id = Capture.saveScreenText(this@PalAccessibilityService, pkg, text, "Liked")
            withContext(Dispatchers.IO) {
                dao.insertPost(SeenPost(pkg = pkg, hash = hash, text = text.take(2000), seenAt = System.currentTimeMillis()))
                dao.markLiked(hash, id)
            }
            toast("❤ Saved to PhonePal")
        }
    }

    // ---------------------------------------------------------------- bubble

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        if (bubble != null) return
        val wm = getSystemService(WindowManager::class.java)
        val size = (52 * resources.displayMetrics.density).toInt()
        val metrics = resources.displayMetrics
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = settings.bubbleX.takeIf { it >= 0 } ?: (metrics.widthPixels - size - 8)
            y = settings.bubbleY.takeIf { it >= 0 } ?: (metrics.heightPixels / 3)
        }
        val v = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher)
            alpha = 0.8f
            contentDescription = "Save to PhonePal"
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var downAt = 0L
        v.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; startX = params.x; startY = params.y; downAt = SystemClock.uptimeMillis()
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (ev.rawX - downX).toInt()
                    params.y = startY + (ev.rawY - downY).toInt()
                    wm.updateViewLayout(v, params)
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(ev.rawX - downX) > 12 || abs(ev.rawY - downY) > 12
                    if (moved) {
                        settings.bubbleX = params.x; settings.bubbleY = params.y
                    } else if (SystemClock.uptimeMillis() - downAt > 600) {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } else {
                        v.animate().scaleX(0.7f).scaleY(0.7f).setDuration(90).withEndAction {
                            v.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                        }.start()
                        onBubbleTap()
                    }
                }
            }
            true
        }
        try {
            wm.addView(v, params)
            bubble = v
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't show bubble", e)
        }
    }

    private fun hideBubble() {
        bubble?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        bubble = null
    }

    private fun onBubbleTap() {
        val root = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root != null }?.root
            ?: rootInActiveWindow
            ?: return toast("Nothing to capture here")
        val pkg = root.packageName?.toString() ?: return
        if (pkg == packageName) return toast("Open the app you want to capture, then tap the bubble")

        if (pkg == Capture.YOUTUBE || pkg == "com.google.android.apps.youtube.music") {
            NowPlaying.get(pkg, maxAgeMs = 30 * 60_000)?.let { now ->
                toast("Saving…")
                scope.launch { toast(Capture.saveYouTube(this@PalAccessibilityService, now.title, now.artist)) }
                return
            }
        }
        val text = focusedPostText(root).ifBlank { gatherText(root) }
        if (text.length < 15) return toast("Couldn't read any text on this screen")
        scope.launch {
            Capture.saveScreenText(this@PalAccessibilityService, pkg, text.take(6000), "Saved from the screen")
            toast("Saved to PhonePal — summarizing…")
        }
    }

    /** In a feed, the post that sits in the middle of the screen; otherwise empty. */
    private fun focusedPostText(root: AccessibilityNodeInfo): String {
        val list = mainList(root) ?: return ""
        val centerY = resources.displayMetrics.heightPixels / 2
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE
        for (i in 0 until list.childCount) {
            val c = list.getChild(i) ?: continue
            val r = Rect().also { c.getBoundsInScreen(it) }
            val score = if (centerY in r.top..r.bottom) 100_000 + r.height() else -abs(r.centerY() - centerY)
            if (score > bestScore) { bestScore = score; best = c }
        }
        return best?.let { gatherText(it) } ?: ""
    }

    // ---------------------------------------------------------------- tree helpers

    /** The biggest scrollable container on screen: the feed. */
    private fun mainList(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40) return
            if (n.isScrollable && n.childCount > 0) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                val area = r.width() * r.height()
                if (area > bestArea) { bestArea = area; best = n }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        return best
    }

    /** Climbs from a tapped button to the row of the feed that contains it. */
    private fun listItemOf(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var cur = node
        repeat(30) {
            val parent = cur.parent ?: return cur
            if (parent.isScrollable) return cur
            cur = parent
        }
        return cur
    }

    private fun gatherText(node: AccessibilityNodeInfo): String {
        val out = LinkedHashSet<String>()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40 || out.size > 300) return
            if (!n.isVisibleToUser) return
            n.text?.toString()?.trim()?.takeIf { it.length >= 2 }?.let { out += it }
            // Content descriptions of icons ("Reply", "Share") are noise; long ones carry real info.
            n.contentDescription?.toString()?.trim()?.takeIf { it.length >= 25 && n.text.isNullOrBlank() }?.let { out += it }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(node, 0)
        return out.joinToString("\n")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "PhonePal.A11y"
        /** "Like", "Liked", "Unlike", "like this video along with N people" — but not "Dislike" or "12 likes". */
        private val LIKE = Regex("(?i)(^|[^a-z])(like|liked|unlike)([^a-z]|$)")
    }
}
