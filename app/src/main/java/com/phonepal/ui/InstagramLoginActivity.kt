package com.phonepal.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.phonepal.fetch.InstagramSession
import com.phonepal.work.InstagramBackfill
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Instagram's own login page in a WebView. You type your password into Instagram, not PhonePal. */
class InstagramLoginActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var status: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CookieManager.getInstance().setAcceptCookie(true)

        status = TextView(this).apply { setPadding(32, 24, 32, 8) }
        val done = Button(this).apply {
            text = "Done"
            setOnClickListener { finishLogin() }
        }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) = updateStatus()
            }
            loadUrl("https://www.instagram.com/accounts/login/")
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(status, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(done, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        updateStatus()
    }

    private fun updateStatus() {
        status.text = if (InstagramSession.isLoggedIn()) "Logged in. Tap Done." else "Log in to Instagram, then tap Done."
    }

    private fun finishLogin() {
        CookieManager.getInstance().flush()
        if (InstagramSession.isLoggedIn()) {
            val app = applicationContext
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { InstagramBackfill.retryUntranscribed(app) }
            Toast.makeText(this, "Instagram connected. Re-reading your saved reels…", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Not logged in", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else finishLogin()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
