package com.phonepal.fetch

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

data class GrabbedReel(
    val caption: String?,
    val username: String?,
    /** Full-file stream URLs the player asked for (byte-range params removed), audio streams first. */
    val streams: List<Stream>,
) {
    data class Stream(val url: String, val isAudio: Boolean)
}

/**
 * Instagram renders reels with JavaScript, so the page HTML holds no video link. This opens the reel
 * in an off-screen WebView with your login (same cookies as the login screen), lets Instagram's
 * player start, and notes which media files it requests. Reels are streamed as separate audio and
 * video files; the audio one is all a transcript needs.
 */
object InstagramWebGrabber {
    private const val PAGE_TIMEOUT_MS = 30_000L
    private const val SETTLE_MS = 4_000L

    suspend fun grab(context: Context, code: String): GrabbedReel? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(PAGE_TIMEOUT_MS + 5_000) { load(context.applicationContext, code) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun load(ctx: Context, code: String): GrabbedReel = suspendCancellableCoroutine { cont ->
        val media = ConcurrentHashMap<String, GrabbedReel.Stream>() // key: path without query
        val main = Handler(Looper.getMainLooper())
        val web = WebView(ctx)
        var finished = false

        fun finish() {
            if (finished) return
            finished = true
            web.evaluateJavascript(READ_PAGE_JS) { raw ->
                val info = runCatching { JSONObject(JSONObject("{\"v\":$raw}").getString("v")) }.getOrNull()
                val caption = info?.optString("caption")?.trim()
                    ?.takeUnless { it.isBlank() || it.lowercase() in GENERIC }
                val user = info?.optString("user")?.trim()?.removePrefix("@")?.ifBlank { null }
                val streams = media.values.sortedByDescending { it.isAudio }
                Log.i("PhonePal", "IG $code via WebView: streams=${streams.size} (audio=${streams.count { it.isAudio }}), caption=${caption != null}")
                web.stopLoading()
                web.destroy()
                if (cont.isActive) cont.resume(GrabbedReel(caption, user, streams))
            }
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadsImagesAutomatically = false
        }
        // Give it a real phone-sized viewport so the player lays out and starts loading.
        web.layout(0, 0, 1080, 2340)
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (url.contains(".mp4") || url.contains(".m4a")) {
                    toFullFile(url)?.let { media.putIfAbsent(it.url.substringBefore('?'), it) }
                    // Once audio is seen (or a couple of streams), give it a moment and wrap up.
                    if (media.values.any { it.isAudio } || media.size >= 2) main.postDelayed({ finish() }, 1_500)
                }
                return null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(PLAY_JS, null)
                main.postDelayed({ view?.evaluateJavascript(PLAY_JS, null) }, 2_000)
                main.postDelayed({ if (media.isNotEmpty()) finish() }, SETTLE_MS * 2)
            }
        }
        main.postDelayed({ finish() }, PAGE_TIMEOUT_MS)
        cont.invokeOnCancellation { main.post { if (!finished) { finished = true; web.destroy() } } }
        web.loadUrl("https://www.instagram.com/reel/$code/")
    }

    /** Player requests are byte ranges (…&bytestart=0&byteend=9999); drop those to get the whole file. */
    private fun toFullFile(url: String): GrabbedReel.Stream? {
        val u = url.toHttpUrlOrNull() ?: return null
        val full = u.newBuilder().removeAllQueryParameters("bytestart").removeAllQueryParameters("byteend").build()
        val tag = u.queryParameter("efg")?.let { e ->
            runCatching { String(Base64.decode(e, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)) }.getOrNull()
        }.orEmpty()
        return GrabbedReel.Stream(full.toString(), isAudio = tag.contains("audio", ignoreCase = true))
    }

    /** Page labels that are not a caption. */
    private val GENERIC = setOf("post", "reel", "reels", "instagram", "video")

    private const val PLAY_JS =
        "(function(){document.querySelectorAll('video').forEach(function(v){v.muted=true;try{v.play()}catch(e){}});})()"

    /**
     * Caption + account from the rendered page. Instagram's meta tags read like
     * `user on Instagram: "caption"` or `12 likes, 3 comments - user on May 1, 2026: "caption"`.
     */
    private const val READ_PAGE_JS = """
        (function(){
          function meta(s){ var e=document.querySelector(s); return e ? (e.getAttribute('content')||'') : ''; }
          var srcs = [meta('meta[property="og:description"]'), meta('meta[name="description"]'),
                      meta('meta[property="og:title"]'), document.title || ''];
          var cap = '', user = '';
          for (var i=0;i<srcs.length;i++){
            var s = srcs[i]; if (!s) continue;
            var q = s.match(/:\s*["“]([\s\S]+)["”]\s*\.?\s*$/);
            if (q && !cap) cap = q[1];
            var u = s.match(/(?:-\s*|^)([A-Za-z0-9._]{2,30}) on (?:Instagram|[A-Z][a-z]+ \d)/);
            if (u && !user) user = u[1];
          }
          if (!cap) {
            var best = '';
            document.querySelectorAll('h1, span[dir="auto"]').forEach(function(e){
              var t = (e.innerText||'').trim(); if (t.length > best.length && t.length < 3000) best = t; });
            if (best.length > 25) cap = best;
          }
          if (!user) {
            var a = document.querySelector('header a[href^="/"][role="link"], article header a[href^="/"]');
            if (a) user = (a.getAttribute('href')||'').split('/').filter(Boolean)[0] || '';
          }
          return JSON.stringify({caption: cap, user: user});
        })()
    """
}
