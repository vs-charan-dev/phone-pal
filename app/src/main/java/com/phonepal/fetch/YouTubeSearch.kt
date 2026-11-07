package com.phonepal.fetch

import android.net.Uri

/**
 * The YouTube app never exposes the video URL to other apps, but its media session gives the
 * exact title + channel. Searching for those returns the video as the first result in practice.
 */
object YouTubeSearch {
    private val firstVideo = Regex("\"videoRenderer\":\\{\"videoId\":\"([A-Za-z0-9_-]{11})\"")
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    private val anyVideo =Regex("\"videoId\":\"([A-Za-z0-9_-]{11})\"")

    fun findVideoId(title: String, channel: String?): String? {
        val q = listOfNotNull(title, channel).joinToString(" ")
        // Desktop UA: the mobile page encodes its data differently and the regex finds nothing.
        val html = Http.get(
            "https://www.youtube.com/results?search_query=" + Uri.encode(q),
            mapOf("User-Agent" to DESKTOP_UA),
        )
        return (firstVideo.find(html) ?: anyVideo.find(html))?.groupValues?.get(1)
    }
}
