package com.phonepal.fetch

import com.phonepal.fetch.Http.bodyOrThrow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class YouTubeVideo(
    val videoId: String,
    val title: String?,
    val channel: String?,
    val description: String?,
    val lengthSec: Long?,
    val transcript: String?,
    val transcriptNote: String,
)

/**
 * On-device port of the approach used by the Python `youtube-transcript-api`:
 *  1. load the watch page to get the InnerTube API key (and pass the EU consent wall if shown),
 *  2. ask the InnerTube `player` endpoint (as the Android client) for caption tracks,
 *  3. download the chosen track's timedtext XML and flatten it to text.
 *
 * It works from a phone for the same reason it works from a laptop: YouTube blocks datacenter
 * IPs, not home/mobile ones. YouTube changes these internals now and then; if it breaks, the
 * item still gets summarized from title + description and is marked so you can retry later.
 */
object YouTubeTranscript {
    private const val WATCH = "https://www.youtube.com/watch?v="
    private const val PLAYER = "https://www.youtube.com/youtubei/v1/player?key="
    private val apiKeyRegex = Regex("\"INNERTUBE_API_KEY\":\\s*\"([a-zA-Z0-9_-]+)\"")
    private val consentRegex = Regex("name=\"v\" value=\"(.*?)\"")
    private val textTag = Regex("(?s)<text[^>]*>(.*?)</text>")
    private val pTag = Regex("(?s)<p\\b[^>]*>(.*?)</p>")

    fun fetch(videoId: String, preferredLangs: List<String> = listOf("en")): YouTubeVideo {
        var cookie: String? = null
        var html = Http.get(WATCH + videoId)
        if (html.contains("action=\"https://consent.youtube.com/s\"")) {
            val v = consentRegex.find(html)?.groupValues?.get(1)
                ?: throw IOException("YouTube consent page could not be passed")
            cookie = "CONSENT=YES+$v"
            html = Http.get(WATCH + videoId, mapOf("Cookie" to cookie))
        }
        if (html.contains("class=\"g-recaptcha\"")) throw IOException("YouTube is asking for a captcha from this network")
        val apiKey = apiKeyRegex.find(html)?.groupValues?.get(1)
            ?: throw IOException("Could not find InnerTube key on the watch page")

        val player = innertubePlayer(videoId, apiKey, cookie)
        val details = player.optJSONObject("videoDetails")
        val title = details?.optString("title")?.ifBlank { null }
        val channel = details?.optString("author")?.ifBlank { null }
        val description = details?.optString("shortDescription")?.ifBlank { null }
        val length = details?.optString("lengthSeconds")?.toLongOrNull()

        val playability = player.optJSONObject("playabilityStatus")
        val status = playability?.optString("status") ?: ""
        if (status != "OK" && status.isNotEmpty() && details == null) {
            throw IOException("Video not playable: $status ${playability?.optString("reason").orEmpty()}".trim())
        }

        val tracks = player.optJSONObject("captions")
            ?.optJSONObject("playerCaptionsTracklistRenderer")
            ?.optJSONArray("captionTracks")
            ?: JSONArray()

        if (tracks.length() == 0) {
            return YouTubeVideo(videoId, title, channel, description, length, null, "No captions available for this video")
        }

        val track = pickTrack(tracks, preferredLangs)
        val baseUrl = track.getString("baseUrl").replace("&fmt=srv3", "")
        val lang = track.optString("languageCode")
        val generated = track.optString("kind") == "asr"
        val xml = Http.get(baseUrl, if (cookie != null) mapOf("Cookie" to cookie) else emptyMap())
        val text = parseTimedText(xml)
        if (text.isBlank()) {
            return YouTubeVideo(videoId, title, channel, description, length, null,
                "Caption track was empty (YouTube may now require a PO token for this video)")
        }
        val note = (if (generated) "Auto-generated captions" else "Captions") + " ($lang)"
        return YouTubeVideo(videoId, title, channel, description, length, text, note)
    }

    private fun innertubePlayer(videoId: String, apiKey: String, cookie: String?): JSONObject {
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "ANDROID")
                .put("clientVersion", "20.10.38")
                .put("hl", "en")))
            .put("videoId", videoId)
            .toString()
        val req = Request.Builder().url(PLAYER + apiKey)
            .header("User-Agent", "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip")
            .header("Accept-Language", "en-US,en;q=0.9")
            .apply { if (cookie != null) header("Cookie", cookie) }
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val json = Http.client.newCall(req).execute().use { it.bodyOrThrow(PLAYER) }
        return JSONObject(json)
    }

    /** Manual captions in a preferred language, then generated in a preferred language, then anything. */
    private fun pickTrack(tracks: JSONArray, preferred: List<String>): JSONObject {
        val all = (0 until tracks.length()).map { tracks.getJSONObject(it) }
        fun langOk(t: JSONObject) = preferred.any { t.optString("languageCode").startsWith(it) }
        fun manual(t: JSONObject) = t.optString("kind") != "asr"
        return all.firstOrNull { langOk(it) && manual(it) }
            ?: all.firstOrNull { langOk(it) }
            ?: all.firstOrNull { manual(it) }
            ?: all.first()
    }

    /** Handles both the classic `<text start dur>` format and the srv3 `<p t d>` format. */
    private fun parseTimedText(xml: String): String {
        val matches = textTag.findAll(xml).toList().ifEmpty { pTag.findAll(xml).toList() }
        return matches.map { m ->
            Html.unescape(Html.unescape(m.groupValues[1].replace(Regex("<[^>]+>"), "")))
                .replace('\n', ' ').trim()
        }.filter { it.isNotEmpty() }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
