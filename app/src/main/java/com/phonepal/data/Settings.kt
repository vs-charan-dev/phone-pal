package com.phonepal.data

import android.content.Context
import android.content.SharedPreferences

class Settings(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Gemini API key from Google AI Studio (aistudio.google.com/apikey). */
    var apiKey: String
        get() = prefs.getString("gemini_api_key", "") ?: ""
        set(v) = prefs.edit().putString("gemini_api_key", v.trim()).apply()

    var model: String
        get() = prefs.getString("gemini_model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = prefs.edit().putString("gemini_model", v.trim().removePrefix("models/").ifEmpty { DEFAULT_MODEL }).apply()

    /** A few lines about you and your content; shapes the "content ideas" section. */
    var creatorProfile: String
        get() = prefs.getString("creator_profile", "") ?: ""
        set(v) = prefs.edit().putString("creator_profile", v.trim()).apply()

    // Phase 2: tracking switches (all need the Accessibility service to be on)
    var showBubble: Boolean
        get() = prefs.getBoolean(KEY_BUBBLE, true)
        set(v) = prefs.edit().putBoolean(KEY_BUBBLE, v).apply()

    var autoSaveLikes: Boolean
        get() = prefs.getBoolean("auto_save_likes", true)
        set(v) = prefs.edit().putBoolean("auto_save_likes", v).apply()

    var trackReading: Boolean
        get() = prefs.getBoolean("track_reading", true)
        set(v) = prefs.edit().putBoolean("track_reading", v).apply()

    var bubbleX: Int
        get() = prefs.getInt("bubble_x", -1)
        set(v) = prefs.edit().putInt("bubble_x", v).apply()

    var bubbleY: Int
        get() = prefs.getInt("bubble_y", -1)
        set(v) = prefs.edit().putInt("bubble_y", v).apply()

    companion object {
        /** Google's alias that always points at the current Flash model. */
        const val DEFAULT_MODEL = "gemini-flash-latest"
        const val KEY_BUBBLE = "show_bubble"
    }
}
