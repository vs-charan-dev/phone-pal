package com.phonepal.data

import android.net.Uri

data class ParsedLink(
    val url: String?,
    val source: Source,
    val youtubeId: String? = null,
    val tweetId: String? = null,
    val instagramCode: String? = null,
)

object LinkParser {
    private val urlRegex = Regex("""https?://[^\s<>"]+""")
    private val ytIdRegex = Regex("""^[A-Za-z0-9_-]{11}$""")
    private val xHosts = setOf("x.com", "twitter.com", "fxtwitter.com", "vxtwitter.com", "fixupx.com")
    private val igHosts = setOf("instagram.com", "instagr.am", "ddinstagram.com")
    private val igKinds = setOf("reel", "reels", "p", "tv")
    private val igCodeRegex = Regex("""^[A-Za-z0-9_-]{5,}$""")

    fun parse(text: String): ParsedLink {
        val raw = urlRegex.find(text)?.value?.trimEnd('.', ',', ')', ']', '!', '?', '\'')
            ?: return ParsedLink(null, Source.NOTE)
        val uri = Uri.parse(raw)
        val host = (uri.host ?: "").lowercase().removePrefix("www.").removePrefix("m.").removePrefix("mobile.")

        youtubeId(uri, host)?.let { id ->
            return ParsedLink("https://www.youtube.com/watch?v=$id", Source.YOUTUBE, youtubeId = id)
        }
        if (host in xHosts) {
            val seg = uri.pathSegments
            val i = seg.indexOf("status")
            if (i >= 0 && i + 1 < seg.size) {
                val id = seg[i + 1].takeWhile { it.isDigit() }
                val user = if (i > 0) seg[0] else "i"
                if (id.isNotEmpty()) return ParsedLink("https://x.com/$user/status/$id", Source.X, tweetId = id)
            }
            return ParsedLink(raw, Source.X)
        }
        if (host in igHosts) {
            val seg = uri.pathSegments
            // instagram.com/reel/CODE, /p/CODE, /tv/CODE, and /user/reel/CODE
            val i = seg.indexOfFirst { it in igKinds }
            val code = if (i >= 0) seg.getOrNull(i + 1) else null
            if (code != null && igCodeRegex.matches(code)) {
                val kind = if (seg[i] == "p") "p" else "reel"
                return ParsedLink("https://www.instagram.com/$kind/$code/", Source.INSTAGRAM, instagramCode = code)
            }
            return ParsedLink(raw, Source.INSTAGRAM)
        }
        return ParsedLink(raw, Source.WEB)
    }

    private fun youtubeId(uri: Uri, host: String): String? {
        val seg = uri.pathSegments
        val id = when (host) {
            "youtu.be" -> seg.firstOrNull()
            "youtube.com", "music.youtube.com" -> when {
                seg.firstOrNull() == "watch" -> uri.getQueryParameter("v")
                seg.size >= 2 && seg[0] in setOf("shorts", "live", "embed", "v") -> seg[1]
                else -> null
            }
            else -> null
        }
        return id?.takeIf { ytIdRegex.matches(it) }
    }
}
