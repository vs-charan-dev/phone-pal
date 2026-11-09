package com.phonepal.fetch

import android.content.Context
import android.util.Log
import okhttp3.Request
import java.io.IOException

data class InstagramPost(
    val code: String,
    val username: String?,
    val caption: String?,
    /** Direct MP4 link, when the public embed page exposes one (reels / video posts). */
    val videoUrl: String?,
)

/**
 * Reads a post through Instagram's public embed page (the one websites use to embed a post),
 * which works without logging in. It gives the caption, the account, and for most reels a direct
 * video link. Instagram changes this page now and then; if it breaks, the caption or video
 * pattern below is the place to look.
 */
object InstagramFetcher {
    /** Gemini takes up to ~20 MB inline; larger files go through its Files API. Cap downloads anyway. */
    const val MAX_VIDEO_BYTES = 60L * 1024 * 1024

    /** Uses your Instagram login when there is one (Instagram walled off embeds), else the embed page. */
    fun fetch(code: String, context: Context): InstagramPost {
        if (InstagramSession.isLoggedIn()) {
            fetchLoggedIn(code, context)?.let { return it }
        }
        return fetchEmbed(code)
    }

    /**
     * Opens the reel's normal page with your session cookies. Its HTML carries the post data as
     * JSON (caption, owner, video_versions). Returns null if nothing usable is in it.
     */
    private fun fetchLoggedIn(code: String, context: Context): InstagramPost? {
        val html = Http.get(
            "https://www.instagram.com/reel/$code/",
            mapOf(
                "Cookie" to (InstagramSession.cookies() ?: ""),
                "User-Agent" to InstagramSession.userAgent(context),
                "Accept" to "text/html,application/xhtml+xml",
            ),
        )
        // Only look at the part of the page about this post, not the feed around it.
        val raw = near(html, "\"code\":\"$code\"") ?: near(html, code) ?: html
        val flat = unescapeJson(raw)

        val video = Regex(""""video_versions"\s*:\s*\[\s*\{[^\]]*?"url"\s*:\s*"(https://[^"]+)"""").find(flat)?.groupValues?.get(1)
            ?: Regex(""""video_url"\s*:\s*"(https://[^"]+)"""").find(flat)?.groupValues?.get(1)
        val caption = Regex(""""caption"\s*:\s*\{[^{}]*?"text"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(raw)
            ?.groupValues?.get(1)?.let { decodeJsonString(it) }?.ifBlank { null }
            ?: ogCaption(html)
        val user = Regex(""""owner"\s*:\s*\{[^{}]*?"username"\s*:\s*"([A-Za-z0-9._]+)"""").find(flat)?.groupValues?.get(1)
            ?: Regex(""""user"\s*:\s*\{[^{}]*?"username"\s*:\s*"([A-Za-z0-9._]+)"""").find(flat)?.groupValues?.get(1)
        Log.i("PhonePal", "IG $code via login: ${html.length} chars, post data=${raw !== html}, video=${video != null}, caption=${caption != null}")
        if (video == null && caption == null) return null
        return InstagramPost(code, user, caption, video?.let { Html.unescape(it) })
    }

    private fun near(html: String, marker: String): String? {
        val at = html.indexOf(marker).takeIf { it >= 0 } ?: return null
        return html.substring((at - 40_000).coerceAtLeast(0), (at + 60_000).coerceAtMost(html.length))
    }

    /** og:description looks like: 12K likes, 80 comments - user on May 1, 2026: "caption…". */
    private fun ogCaption(html: String): String? {
        val d = Regex("""(?is)<meta[^>]+property="og:description"[^>]*content="([^"]*)"""").find(html)?.groupValues?.get(1)
            ?.let { Html.unescape(it) } ?: return null
        return d.substringAfter(": \"", "").removeSuffix("\"").ifBlank { null }
    }

    private fun fetchEmbed(code: String): InstagramPost {
        val html = Http.get(
            "https://www.instagram.com/p/$code/embed/captioned/",
            mapOf("Referer" to "https://www.instagram.com/"),
        )
        return InstagramPost(code, username(html), caption(html), videoUrl(html))
    }

    private fun username(html: String): String? =
        Regex("""(?is)class="[^"]*(?:CaptionUsername|UsernameText)[^"]*"[^>]*>(.*?)</""").find(html)
            ?.groupValues?.get(1)?.let { Html.toText(it) }?.ifBlank { null }
            ?: Regex(""""owner"\s*:\s*\{[^}]*?"username"\s*:\s*"([^"]+)"""").find(unescapeJson(html))?.groupValues?.get(1)

    private fun caption(html: String): String? {
        val block = Regex("""(?is)<div class="Caption"[^>]*>(.*?)<div class="CaptionComments""").find(html)?.groupValues?.get(1)
            ?: Regex("""(?is)<div class="Caption"[^>]*>(.*?)</div>""").find(html)?.groupValues?.get(1)
        val fromHtml = block
            ?.replace(Regex("""(?is)<a[^>]*class="CaptionUsername"[^>]*>.*?</a>"""), "")
            ?.let { Html.toText(it.replace(Regex("(?i)<br\\s*/?>"), "\n")) }
            ?.ifBlank { null }
        if (fromHtml != null) return fromHtml
        // Some embed variants only carry the caption in the page's JSON.
        return Regex(""""edge_media_to_caption"\s*:\s*\{\s*"edges"\s*:\s*\[\s*\{\s*"node"\s*:\s*\{\s*"text"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            .find(unescapeJson(html))?.groupValues?.get(1)?.let { decodeJsonString(it) }?.ifBlank { null }
    }

    private fun videoUrl(html: String): String? {
        val flat = unescapeJson(html)
        Regex(""""video_url"\s*:\s*"(https://[^"]+)"""").find(flat)?.let { return Html.unescape(it.groupValues[1]) }
        Regex("""(?is)<video[^>]+src="(https://[^"]+)"""").find(html)?.let { return Html.unescape(it.groupValues[1]) }
        return null
    }

    /** Downloads the video. Returns bytes + mime type, or throws if it's too large or the link expired. */
    fun download(url: String): Pair<ByteArray, String> {
        val req = Request.Builder().url(url)
            .header("User-Agent", Http.BROWSER_UA)
            .header("Referer", "https://www.instagram.com/")
            .build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} downloading the reel video")
            val body = resp.body ?: throw IOException("Empty video download")
            val len = body.contentLength()
            if (len > MAX_VIDEO_BYTES) throw IllegalStateException("Video is too large (${len / 1_048_576} MB)")
            val bytes = body.byteStream().use { input ->
                val out = java.io.ByteArrayOutputStream(if (len > 0) len.toInt() else 4 shl 20)
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_VIDEO_BYTES) throw IllegalStateException("Video is too large")
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            val mime = body.contentType()?.let { "${it.type}/${it.subtype}" }
                ?.takeIf { it.startsWith("video/") } ?: "video/mp4"
            return bytes to mime
        }
    }

    /** The embed page nests JSON inside JS strings, so links show up as https:\/\/… or https:\\\/\\\/…. */
    private fun unescapeJson(s: String): String = s
        .replace("\\\\\\/", "/").replace("\\\\/", "/").replace("\\/", "/")
        .replace("\\\\u0026", "&").replace("\\u0026", "&")
        .replace("\\\\\"", "\"").replace("\\\"", "\"")

    private fun decodeJsonString(s: String): String =
        runCatching { org.json.JSONArray("[\"$s\"]").getString(0) }.getOrDefault(s)
}
