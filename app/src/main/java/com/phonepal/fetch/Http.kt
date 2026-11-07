package com.phonepal.fetch

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    fun Response.bodyOrThrow(url: String): String {
        if (code == 429) throw IOException("Rate limited (429) by ${request.url.host}")
        if (!isSuccessful) throw IOException("HTTP $code from $url")
        return body?.string() ?: ""
    }
}

/** Minimal HTML → text helpers (no extra dependency). */
object Html {
    private val entity = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")
    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "#39" to "'",
        "nbsp" to " ", "mdash" to "—", "ndash" to "–", "hellip" to "…", "rsquo" to "’", "lsquo" to "‘",
        "rdquo" to "”", "ldquo" to "“",
    )

    fun unescape(s: String): String = entity.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
            else -> named[e]
        } ?: m.value
    }

    fun toText(html: String): String = unescape(
        html.replace(Regex("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>|</(p|div|li|h[1-6]|tr)>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
    ).replace(Regex("[ \\t\\u00A0]+"), " ")
        .replace(Regex("\\n\\s*\\n+"), "\n\n")
        .trim()
}
