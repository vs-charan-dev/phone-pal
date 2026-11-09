package com.phonepal.ai

import com.phonepal.data.Item
import com.phonepal.data.Settings
import com.phonepal.data.Source
import com.phonepal.fetch.Http
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** The File Search store is gone (deleted, or the key belongs to another project): rebuild it. */
class StoreMissingException(msg: String) : IOException(msg)

/** One library item an answer was based on. */
data class Citation(val itemId: Long?, val title: String)

data class Answer(val text: String, val citations: List<Citation>)

/** One message of the conversation, for follow-up questions. */
data class ChatTurn(val fromUser: Boolean, val text: String)

/**
 * "Ask your library": Gemini File Search (Google-hosted RAG) over plain REST.
 *
 * Each finished item becomes one text document in a File Search store (title, summary, key
 * points, your note, then the full transcript / page text). Google chunks and embeds it once.
 * A question is a normal generateContent call with the fileSearch tool: Google finds the closest
 * chunks and only those go to the model, which answers with citations back to the items.
 */
class FileSearch(private val settings: Settings) {

    private val apiKey get() = settings.apiKey

    private val client: OkHttpClient = Http.client.newBuilder()
        .readTimeout(3, TimeUnit.MINUTES)
        .callTimeout(4, TimeUnit.MINUTES)
        .build()

    /** The store for the current API key, or null if none was made yet (or the key changed). */
    fun storeName(): String? {
        val p = settings.prefs
        return p.getString(KEY_STORE, null)?.takeIf { p.getString(KEY_STORE_FOR, null) == keyId() }
    }

    fun forgetStore() {
        settings.prefs.edit().remove(KEY_STORE).remove(KEY_STORE_FOR).apply()
    }

    fun ensureStore(): String {
        storeName()?.let { return it }
        val req = Request.Builder().url("$BASE/fileSearchStores")
            .header("x-goog-api-key", apiKey)
            .post(JSONObject().put("displayName", "PhonePal library").toString().toRequestBody(JSON))
            .build()
        val name = exec(req).getString("name")
        settings.prefs.edit().putString(KEY_STORE, name).putString(KEY_STORE_FOR, keyId()).apply()
        return name
    }

    /** Uploads one item as a document and waits until it is chunked and embedded. Returns the document name. */
    fun upload(store: String, item: Item): String {
        val bytes = document(item).toByteArray(Charsets.UTF_8)
        val meta = JSONObject()
            .put("displayName", "#${item.id} ${item.title ?: item.url ?: "Untitled"}".take(500))
            .put("mimeType", "text/plain")
            .put(
                "customMetadata", JSONArray()
                    .put(JSONObject().put("key", "item_id").put("numericValue", item.id))
                    .put(JSONObject().put("key", "source").put("stringValue", item.source.name))
                    .put(JSONObject().put("key", "saved_at").put("numericValue", item.createdAt / 1000))
            )
        val start = Request.Builder()
            .url("$UPLOAD_BASE/$store:uploadToFileSearchStore")
            .header("x-goog-api-key", apiKey)
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", bytes.size.toString())
            .header("X-Goog-Upload-Header-Content-Type", "text/plain")
            .post(meta.toString().toRequestBody(JSON))
            .build()
        val uploadUrl = client.newCall(start).execute().use { r ->
            if (!r.isSuccessful) fail(r.code, r.body?.string().orEmpty(), "File Search upload", storeCall = true)
            r.header("X-Goog-Upload-URL") ?: throw IOException("File Search upload: no upload URL")
        }
        val send = Request.Builder().url(uploadUrl)
            .header("X-Goog-Upload-Offset", "0")
            .header("X-Goog-Upload-Command", "upload, finalize")
            .post(bytes.toRequestBody("text/plain".toMediaType()))
            .build()
        var op = exec(send)
        // The finished upload returns a long-running operation; indexing takes a few seconds.
        repeat(60) {
            if (op.optBoolean("done")) {
                op.optJSONObject("error")?.let { e ->
                    throw PermanentAiException("Indexing failed: ${e.optString("message")}")
                }
                return op.optJSONObject("response")?.optString("documentName")?.ifBlank { null }
                    ?: throw IOException("File Search: no document name in ${op.toString().take(200)}")
            }
            val name = op.optString("name").ifBlank { throw IOException("File Search: no operation in ${op.toString().take(200)}") }
            Thread.sleep(2000)
            op = exec(Request.Builder().url("$BASE/$name").header("x-goog-api-key", apiKey).build())
        }
        throw IOException("File Search is still indexing; will retry")
    }

    /** Removes a document from the store. Already gone counts as done. */
    fun deleteDoc(docName: String) {
        if (docName.isBlank()) return
        val req = Request.Builder().url("$BASE/$docName?force=true")
            .header("x-goog-api-key", apiKey)
            .delete()
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful && r.code != 404) fail(r.code, r.body?.string().orEmpty(), "File Search delete")
        }
    }

    /** Answers the last user message in [history] from the library. [only] narrows the search to one source. */
    fun ask(history: List<ChatTurn>, only: Source?): Answer {
        val store = storeName() ?: throw PermanentAiException("Nothing is indexed yet. Save a few things and wait for them to finish.")
        val contents = JSONArray()
        history.forEach { t ->
            contents.put(JSONObject()
                .put("role", if (t.fromUser) "user" else "model")
                .put("parts", JSONArray().put(JSONObject().put("text", t.text))))
        }
        val fileSearch = JSONObject().put("fileSearchStoreNames", JSONArray().put(store))
        only?.let { fileSearch.put("metadataFilter", "source = \"${it.name}\"") }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt()))))
            .put("contents", contents)
            .put("tools", JSONArray().put(JSONObject().put("fileSearch", fileSearch)))
        val req = Request.Builder()
            .url("$BASE/models/${settings.model.removePrefix("models/")}:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody(JSON))
            .build()
        val json = exec(req)

        json.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotBlank() }?.let {
            throw PermanentAiException("Gemini blocked this question ($it)")
        }
        val cand = json.optJSONArray("candidates")?.optJSONObject(0) ?: throw IOException("Gemini returned no answer")
        val parts = cand.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val text = (0 until parts.length()).map { parts.getJSONObject(it) }
            .filter { !it.optBoolean("thought", false) }
            .joinToString("") { it.optString("text") }
            .trim()
        if (text.isBlank()) throw PermanentAiException("Gemini returned an empty answer (finishReason: ${cand.optString("finishReason")})")
        return Answer(text, citations(cand))
    }

    private fun citations(cand: JSONObject): List<Citation> {
        val chunks = cand.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks") ?: return emptyList()
        return (0 until chunks.length()).mapNotNull { chunks.optJSONObject(it)?.optJSONObject("retrievedContext") }
            .map { ctx ->
                val title = ctx.optString("title")
                var id: Long? = null
                ctx.optJSONArray("customMetadata")?.let { md ->
                    for (i in 0 until md.length()) {
                        val m = md.optJSONObject(i) ?: continue
                        if (m.optString("key") != "item_id") continue
                        id = when {
                            m.has("numericValue") -> m.optDouble("numericValue").toLong()
                            m.has("stringValue") -> m.optString("stringValue").toLongOrNull()
                            else -> null
                        }
                    }
                }
                // Fallback: the document's display name starts with "#<id> ".
                if (id == null) id = TITLE_ID.find(title)?.groupValues?.get(1)?.toLongOrNull()
                Citation(id, title.replace(TITLE_ID, "").trim().ifBlank { "Saved item" })
            }
            .distinctBy { it.itemId ?: it.title }
    }

    private fun systemPrompt(): String = buildString {
        append(
            """
            You answer questions from the user's personal library: things they saved while browsing
            (YouTube videos, Instagram reels, X posts, web pages, notes). Each document has a summary,
            key points, content ideas, the user's own note, and the full transcript or page text.

            Use the file search tool and answer only from what it returns. Be specific: name the item
            (title or creator) each point comes from, and quote short phrases from transcripts when the
            exact wording matters. If the library has nothing relevant, say so plainly instead of
            answering from general knowledge. Keep answers compact; use short bullet lists when listing
            several items.

            Formatting (it is shown in a phone chat): simple markdown only. Use "## " for a heading when
            the answer has sections, "- " for bullets, "1. " for steps, **bold** for item names and
            labels. No tables, no horizontal rules (---), no nested emphasis, at most one blank line
            between blocks.
            """.trimIndent()
        )
        if (settings.creatorProfile.isNotBlank()) append("\n\nAbout the user: ").append(settings.creatorProfile)
    }

    /** POSTs/GETs and returns the JSON body, mapping HTTP errors to retryable vs. permanent failures. */
    private fun exec(req: Request): JSONObject = client.newCall(req).execute().use { r ->
        val text = r.body?.string().orEmpty()
        if (!r.isSuccessful) fail(r.code, text, "Gemini")
        if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    /** [storeCall]: the request addressed the store itself, so a 404 means the store is gone. */
    private fun fail(code: Int, body: String, what: String, storeCall: Boolean = false): Nothing {
        val msg = runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull() ?: body.take(300)
        when {
            code == 429 || code >= 500 -> throw IOException("$what $code: $msg")
            code == 404 && (storeCall || msg.contains("fileSearchStores", ignoreCase = true)) -> throw StoreMissingException(msg)
            code == 404 -> throw PermanentAiException("$what 404: $msg (check the model in Settings)")
            else -> throw PermanentAiException("$what $code: $msg")
        }
    }

    private fun keyId(): String = Integer.toHexString(apiKey.hashCode())

    companion object {
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        private const val UPLOAD_BASE = "https://generativelanguage.googleapis.com/upload/v1beta"
        private const val KEY_STORE = "rag_store"
        private const val KEY_STORE_FOR = "rag_store_key"
        private val JSON = "application/json".toMediaType()
        private val TITLE_ID = Regex("^#(\\d+)\\s*")

        /** The text that gets indexed: the organized summary first, then the full source text. */
        fun document(item: Item): String = buildString {
            appendLine("# ${item.title ?: item.url ?: "Untitled"}")
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(item.createdAt))
            appendLine(listOfNotNull(sourceName(item.source), item.author, "saved $date").joinToString(" · "))
            item.url?.let { appendLine("URL: $it") }
            item.category?.let { appendLine("Category: $it") }
            if (item.tagList.isNotEmpty()) appendLine("Tags: ${item.tagList.joinToString(", ")}")
            item.summary?.let { appendLine().appendLine("## Summary").appendLine(it) }
            list("Key points", item.keyPoints)
            list("Content ideas", item.contentIdeas)
            list("Hooks", item.hooks)
            item.myNote?.takeIf { it.isNotBlank() }?.let { appendLine().appendLine("## My note").appendLine(it) }
            item.contentText?.takeIf { it.isNotBlank() }?.let {
                appendLine().appendLine("## ${item.contentNote ?: "Source text"}").appendLine(it)
            }
        }

        private fun StringBuilder.list(title: String, lines: String?) {
            val l = Item.lines(lines)
            if (l.isEmpty()) return
            appendLine().appendLine("## $title")
            l.forEach { appendLine("- $it") }
        }

        private fun sourceName(s: Source) = when (s) {
            Source.YOUTUBE -> "YouTube video"
            Source.X -> "X post"
            Source.WEB -> "Web page"
            Source.APP -> "Read from an app"
            Source.NOTE -> "Note"
            Source.INSTAGRAM -> "Instagram reel/post"
        }
    }
}
