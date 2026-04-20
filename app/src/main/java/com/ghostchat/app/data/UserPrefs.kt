package com.ghostchat.app.data

import android.content.Context

class UserPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("ghostchat", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(value) {
            prefs.edit().putString(KEY_SERVER_URL, value).apply()
        }

    companion object {
        private const val KEY_SERVER_URL = "server_url"
        private const val DEFAULT_URL = "wss://your-server.example/ws"
    }
}
