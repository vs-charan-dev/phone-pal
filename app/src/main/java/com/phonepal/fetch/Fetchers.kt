package com.phonepal.fetch

import android.net.Uri
import org.json.JSONObject

data class FetchedContent(
    val title: String?,
    val author: String?,
    val text: String?,
    val note: String?,
    val durationSec: Long? = null,
)

object TweetFetcher {
    /** Uses X's public oEmbed endpoint (no login, no API key). Returns the post text + author. */
    fun fetch(url: String): FetchedContent {
        val json = JSONObject(Http.get("https://publish.twitter.com/oembed?omit_script=true&dnt=true&url=" + Uri.encode(url)))
        val author = json.optString("author_name").ifBlank { null }
        val handle = json.optString("author_url").substringAfterLast('/').ifBlank { null }
        val html = json.optString("html")
        // The post body is inside the first <p>…</p> of the blockquote.
        val body = Regex("(?s)<p[^>]*>(.*?)</p>").find(html)?.groupValues?.get(1) ?: html
        val text = Html.toText(body.replace(Regex("(?i)<br\\s*/?>"), "\n"))
        val who = listOfNotNull(author, handle?.let { "@$it" }).joinToString(" ")
        return FetchedContent(
            title = text.lineSequence().firstOrNull()?.take(90),
            author = who.ifBlank { null },
            text = text,
            note = "Post text via oEmbed (images/videos and threads are not included)",
        )
    }
}

object WebFetcher {
    private const val MAX_CHARS = 60_000

    fun fetch(url: String): FetchedContent {
        val html = Http.get(url)
        fun meta(prop: String): String? =
            Regex("(?is)<meta[^>]+(?:property|name)=[\"']$prop[\"'][^>]*content=[\"']([^\"']*)[\"']").find(html)?.groupValues?.get(1)
                ?: Regex("(?is)<meta[^>]+content=[\"']([^\"']*)[\"'][^>]*(?:property|name)=[\"']$prop[\"']").find(html)?.groupValues?.get(1)

        val title = meta("og:title") ?: Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)
        val desc = meta("og:description") ?: meta("description")
        val site = meta("og:site_name") ?: Uri.parse(url).host
        val bodyHtml = Regex("(?is)<article[^>]*>(.*?)</article>").find(html)?.groupValues?.get(1)
            ?: Regex("(?is)<body[^>]*>(.*)</body>").find(html)?.groupValues?.get(1)
            ?: html
        val body = Html.toText(bodyHtml)
        val truncated = body.length > MAX_CHARS
        val text = listOfNotNull(desc?.let { Html.unescape(it) }, body.take(MAX_CHARS)).joinToString("\n\n")
        return FetchedContent(
            title = title?.let { Html.unescape(it).trim() },
            author = site,
            text = text,
            note = if (truncated) "Page text (first ${MAX_CHARS / 1000}k characters of ${body.length / 1000}k)" else "Page text",
        )
    }
}
