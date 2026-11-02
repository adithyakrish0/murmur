package com.murmur.keyboard.data

import android.content.Context
import android.content.SharedPreferences

class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("murmur_settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_API_KEY, value).apply()
        }

    var selectedModel: String
        get() = prefs.getString(KEY_MODEL, MODEL_TURBO) ?: MODEL_TURBO
        set(value) {
            prefs.edit().putString(KEY_MODEL, value).apply()
        }

    var hapticFeedbackEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC, true)
        set(value) {
            prefs.edit().putBoolean(KEY_HAPTIC, value).apply()
        }

    companion object {
        const val KEY_API_KEY = "api_key"
        const val KEY_MODEL = "selected_model"
        const val KEY_HAPTIC = "haptic_feedback"

        const val MODEL_TURBO = "whisper-large-v3-turbo"
        const val MODEL_LARGE = "whisper-large-v3"
    }
}
