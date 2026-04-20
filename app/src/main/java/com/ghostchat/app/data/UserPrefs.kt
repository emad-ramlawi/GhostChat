package com.ghostchat.app.data

import android.content.Context
import java.util.UUID

class UserPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("ghostchat", Context.MODE_PRIVATE)

    val userId: String
        get() {
            val existing = prefs.getString(KEY_USER_ID, null)
            if (existing != null) return existing
            val fresh = UUID.randomUUID().toString().replace("-", "").take(12)
            prefs.edit().putString(KEY_USER_ID, fresh).apply()
            return fresh
        }

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(value) {
            prefs.edit().putString(KEY_SERVER_URL, value).apply()
        }

    companion object {
        private const val KEY_USER_ID = "user_id"
        private const val KEY_SERVER_URL = "server_url"
        private const val DEFAULT_URL = "ws://10.0.2.2:8080/ws"
    }
}
