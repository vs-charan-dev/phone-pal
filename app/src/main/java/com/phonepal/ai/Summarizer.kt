package com.phonepal.ai

import com.phonepal.data.Item
import com.phonepal.data.Source
import com.phonepal.fetch.Http
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class Summary(
    val title: String?,
    val summary: String,
    val keyPoints: List<String>,
    val contentIdeas: List<String>,
    val hooks: List<String>,
    val tags: List<String>,
    val category: String?,
)

/** A failure that retrying won't fix (bad key, unknown model, blocked content). */
class PermanentAiException(msg: String) : Exception(msg)

/**
 * Turns a saved item (transcript / post / page) into an organized note for a content creator,
 * using the Gemini API (key from Google AI Studio) over plain REST.
 */
class Summarizer(private val apiKey: String, private val model: String, private val creatorProfile: String) {

    private val client: OkHttpClient = Http.client.newBuilder()
        .readTimeout(5, TimeUnit.MINUTES)
        .callTimeout(6, TimeUnit.MINUTES)
        .build()

    fun summarize(item: Item): Summary = parse(generate(systemPrompt(), userPrompt(item), json = true))

    /** A short, honest weekly reflection from the Insights numbers. Plain text. */
    fun reflect(stats: String): String = generate(
        """
        You are a blunt but kind coach helping someone understand how they spend time on their phone,
        so they can measure and improve. They are a content creator, so consuming content is partly
        research, but mindless scrolling is not. From the stats, write:
        1) 2-3 sentences on what the numbers say (be specific, use the numbers),
        2) what looks like research vs. drift,
        3) exactly 3 small, concrete changes for next week.
        Plain text, no markdown headings, under 180 words.
        """.trimIndent() + if (creatorProfile.isNotBlank()) "\n\nAbout them: $creatorProfile" else "",
        stats,
        json = false,
    ).trim()

    /**
     * Writes a transcript of a short video (an Instagram reel) by sending the video itself to Gemini.
     * Small files go inline; bigger ones are uploaded through the Files API first.
     */
    fun transcribe(video: ByteArray, mime: String): String {
        val media = if (video.size <= INLINE_LIMIT) {
            JSONObject().put("inline_data", JSONObject()
                .put("mime_type", mime)
                .put("data", android.util.Base64.encodeToString(video, android.util.Base64.NO_WRAP)))
        } else {
            JSONObject().put("file_data", JSONObject().put("mime_type", mime).put("file_uri", upload(video, mime)))
        }
        val parts = JSONArray()
            .put(media)
            .put(JSONObject().put("text", TRANSCRIBE_PROMPT))
        return generate(
            "You transcribe short social media videos accurately. Plain text only.",
            parts,
            json = false,
        ).trim()
    }

    /** Gemini Files API resumable upload; waits until the video is processed. Returns the file URI. */
    private fun upload(bytes: ByteArray, mime: String): String {
        val start = Request.Builder()
            .url("https://generativelanguage.googleapis.com/upload/v1beta/files")
            .header("x-goog-api-key", apiKey)
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", bytes.size.toString())
            .header("X-Goog-Upload-Header-Content-Type", mime)
            .post(JSONObject().put("file", JSONObject().put("display_name", "phonepal-reel")).toString()
                .toRequestBody("application/json".toMediaType()))
            .build()
        val uploadUrl = client.newCall(start).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Gemini upload start failed: HTTP ${r.code} ${r.body?.string()?.take(200)}")
            r.header("X-Goog-Upload-URL") ?: throw IOException("Gemini upload: no upload URL")
        }
        val send = Request.Builder().url(uploadUrl)
            .header("X-Goog-Upload-Offset", "0")
            .header("X-Goog-Upload-Command", "upload, finalize")
            .post(bytes.toRequestBody(mime.toMediaType()))
            .build()
        var file = client.newCall(send).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("Gemini upload failed: HTTP ${r.code} ${t.take(200)}")
            JSONObject(t).getJSONObject("file")
        }
        // Videos are processed before they can be used.
        repeat(30) {
            when (file.optString("state")) {
                "ACTIVE", "" -> return file.getString("uri")
                "FAILED" -> throw PermanentAiException("Gemini could not process the video")
            }
            Thread.sleep(2000)
            val get = Request.Builder().url("$BASE/${file.getString("name")}").header("x-goog-api-key", apiKey).build()
            file = client.newCall(get).execute().use { r -> JSONObject(r.body?.string().orEmpty()) }
        }
        throw IOException("Gemini is still processing the video; will retry")
    }

    private fun generate(system: String, user: String, json: Boolean): String =
        generate(system, JSONArray().put(JSONObject().put("text", user)), json)

    private fun generate(system: String, userParts: JSONArray, json: Boolean): String {
        val config = JSONObject()
        if (json) config.put("responseMimeType", "application/json")
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("parts", userParts)))
            .put("generationConfig", config)

        val req = Request.Builder()
            .url("$BASE/models/${model.removePrefix("models/")}:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val json = client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull() ?: text.take(300)
                when (resp.code) {
                    429, 500, 502, 503, 504 -> throw IOException("Gemini ${resp.code}: $msg")
                    404 -> throw PermanentAiException("Model \"$model\" not found. Pick another in Settings → Load my models. ($msg)")
                    else -> throw PermanentAiException("Gemini ${resp.code}: $msg")
                }
            }
            JSONObject(text)
        }

        json.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotBlank() }?.let {
            throw PermanentAiException("Gemini blocked this item ($it)")
        }
        val cand = json.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw IOException("Gemini returned no answer")
        val parts = cand.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val out = (0 until parts.length())
            .map { parts.getJSONObject(it) }
            .filter { !it.optBoolean("thought", false) }
            .joinToString("") { it.optString("text") }
        if (out.isBlank()) {
            throw PermanentAiException("Gemini returned an empty answer (finishReason: ${cand.optString("finishReason")})")
        }
        return out
    }

    private fun systemPrompt(): String = buildString {
        append(
            """
            You turn things the user saved while browsing (YouTube videos, Instagram reels, X posts, web pages, notes) into
            an organized personal knowledge base. The user is a content creator: they save things because
            they found them interesting and want to reuse the ideas later in their own content.

            Reply with ONE JSON object and nothing else, using exactly these keys:
            {
              "title": "short descriptive title (keep the original title if it is already clear)",
              "summary": "3-6 sentence summary of what this is actually about",
              "key_points": ["the most important specific ideas, facts, numbers or arguments (3-8 items)"],
              "content_ideas": ["concrete content ideas the user could make from this, each one sentence with an angle (3-6 items)"],
              "hooks": ["2-4 punchy opening lines / hooks inspired by this"],
              "tags": ["3-8 short lowercase topic tags"],
              "category": "one broad category, e.g. tech, business, fitness, finance, productivity, entertainment, education"
            }

            Be specific to this item; never pad with generic advice. If the source text is missing or thin
            (for example no transcript), work from the title and description and say so in the summary.
            Write in the same language as the source unless it is mixed, then use English.
            """.trimIndent()
        )
        if (creatorProfile.isNotBlank()) {
            append("\n\nAbout the user (tailor content ideas to this):\n")
            append(creatorProfile)
        }
    }

    private fun userPrompt(item: Item): String = buildString {
        append("Source type: ").append(
            when (item.source) {
                Source.YOUTUBE -> "YouTube video"
                Source.X -> "X (Twitter) post"
                Source.WEB -> "Web page"
                Source.INSTAGRAM -> "Instagram post / reel (text is the caption plus a transcript of the video)"
                Source.APP -> "Text read from the screen of another app (e.g. Instagram, Reddit, LinkedIn); it may include UI labels, ignore those"
                Source.NOTE -> "Text the user shared"
            }
        ).append('\n')
        item.url?.let { append("URL: ").append(it).append('\n') }
        item.title?.let { append("Title: ").append(it).append('\n') }
        item.author?.let { append("Author/channel: ").append(it).append('\n') }
        item.durationSec?.let { append("Length: ").append(it / 60).append(" min\n") }
        item.contentNote?.let { append("Note about the text: ").append(it).append('\n') }
        if (item.sharedText.isNotBlank() && item.source != Source.NOTE) {
            append("What the share sheet contained: ").append(item.sharedText.take(500)).append('\n')
        }
        append("\n<source_text>\n")
        append(item.contentText?.ifBlank { null } ?: "(no text could be fetched)")
        append("\n</source_text>")
    }

    private fun parse(raw: String): Summary {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) throw PermanentAiException("Gemini did not return JSON: ${raw.take(200)}")
        val o = JSONObject(raw.substring(start, end + 1))
        fun list(key: String): List<String> {
            val a = o.optJSONArray(key) ?: JSONArray()
            return (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() }
        }
        return Summary(
            title = o.optString("title").ifBlank { null },
            summary = o.optString("summary"),
            keyPoints = list("key_points"),
            contentIdeas = list("content_ideas"),
            hooks = list("hooks"),
            tags = list("tags").map { it.lowercase().replace(",", " ") },
            category = o.optString("category").ifBlank { null },
        )
    }

    companion object {
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta"

        /** Inline request limit is ~20 MB after base64 (+33%), so stay well under it. */
        private const val INLINE_LIMIT = 14 * 1024 * 1024

        private val TRANSCRIBE_PROMPT = """
            Transcribe everything that is said in this video, word for word, in the language it is spoken.
            If there are several speakers, start a new line for each one.
            Then add a line "On-screen text:" with any important text shown on screen (skip if none).
            If nobody speaks, write "(no speech)" and then briefly describe what the video shows.
            Output only the transcript, no commentary.
        """.trimIndent()

        /** Models this key can call generateContent on, e.g. "gemini-flash-latest". */
        fun listModels(apiKey: String): List<String> {
            val req = Request.Builder().url("$BASE/models?pageSize=1000").header("x-goog-api-key", apiKey).build()
            val text = Http.client.newCall(req).execute().use { resp ->
                val t = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching { JSONObject(t).getJSONObject("error").getString("message") }.getOrNull() ?: "HTTP ${resp.code}"
                    throw IOException(msg)
                }
                t
            }
            val arr = JSONObject(text).optJSONArray("models") ?: JSONArray()
            return (0 until arr.length()).map { arr.getJSONObject(it) }
                .filter { m ->
                    val methods = m.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                    (0 until methods.length()).any { methods.getString(it) == "generateContent" }
                }
                .map { it.getString("name").removePrefix("models/") }
                .filter { it.startsWith("gemini") && !it.contains("tts") && !it.contains("image") && !it.contains("embedding") }
                .sorted()
        }
    }
}
