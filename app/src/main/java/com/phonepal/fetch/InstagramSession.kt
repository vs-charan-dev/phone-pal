package com.phonepal.fetch

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings

/**
 * Your own Instagram login, made in PhonePal's login screen (a normal Instagram web page in a
 * WebView). PhonePal never sees the password; it only reuses the cookies the WebView keeps,
 * the same way a browser would, to open reels you saved.
 */
object InstagramSession {
    private const val ORIGIN = "https://www.instagram.com"

    fun cookies(): String? = runCatching { CookieManager.getInstance().getCookie(ORIGIN) }.getOrNull()

    fun isLoggedIn(): Boolean = cookies()?.contains("sessionid=") == true

    /** Same browser identity as the WebView that made the login, so Instagram sees one device. */
    fun userAgent(context: Context): String =
        runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault(Http.BROWSER_UA)

    fun logOut() {
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
    }
}
