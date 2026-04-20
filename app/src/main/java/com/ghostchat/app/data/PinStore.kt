package com.ghostchat.app.data

import android.content.Context

class PinStore(context: Context) {
    private val prefs = context.getSharedPreferences("ghostchat_pins", Context.MODE_PRIVATE)

    fun get(host: String): ByteArray? {
        val hex = prefs.getString(host.lowercase(), null) ?: return null
        return hex.hexToBytesOrNull()
    }

    fun put(host: String, pin: ByteArray) {
        prefs.edit().putString(host.lowercase(), pin.toHex()).apply()
    }

    fun remove(host: String) {
        prefs.edit().remove(host.lowercase()).apply()
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { "%02x".format(it) }

    private fun String.hexToBytesOrNull(): ByteArray? {
        if (length % 2 != 0) return null
        return runCatching {
            ByteArray(length / 2) { i ->
                ((this[i * 2].digitToInt(16) shl 4) or this[i * 2 + 1].digitToInt(16)).toByte()
            }
        }.getOrNull()
    }
}
