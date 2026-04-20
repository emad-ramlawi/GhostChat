package com.ghostchat.app.data

import android.content.Context
import android.util.Base64
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Box

class Crypto(context: Context) {
    private val sodium = LazySodiumAndroid(SodiumAndroid())
    private val prefs = context.getSharedPreferences("ghostchat_keys", Context.MODE_PRIVATE)

    val publicKey: ByteArray
    private val secretKey: ByteArray

    val userId: String
        get() = encode(publicKey)

    init {
        val pubEnc = prefs.getString(KEY_PUB, null)
        val secEnc = prefs.getString(KEY_SEC, null)
        if (pubEnc != null && secEnc != null) {
            publicKey = decode(pubEnc) ?: error("corrupt pubkey")
            secretKey = decode(secEnc) ?: error("corrupt seckey")
        } else {
            val pub = ByteArray(Box.PUBLICKEYBYTES)
            val sec = ByteArray(Box.SECRETKEYBYTES)
            check(sodium.cryptoBoxKeypair(pub, sec)) { "keypair generation failed" }
            publicKey = pub
            secretKey = sec
            prefs.edit()
                .putString(KEY_PUB, encode(pub))
                .putString(KEY_SEC, encode(sec))
                .apply()
        }
    }

    fun encryptTo(recipientId: String, plaintext: String): String? {
        val theirPub = decodeId(recipientId) ?: return null
        val msg = plaintext.toByteArray(Charsets.UTF_8)
        val nonce = sodium.randomBytesBuf(Box.NONCEBYTES)
        val ct = ByteArray(msg.size + Box.MACBYTES)
        if (!sodium.cryptoBoxEasy(ct, msg, msg.size.toLong(), nonce, theirPub, secretKey)) return null
        val combined = ByteArray(nonce.size + ct.size)
        System.arraycopy(nonce, 0, combined, 0, nonce.size)
        System.arraycopy(ct, 0, combined, nonce.size, ct.size)
        return encode(combined)
    }

    fun decryptFrom(senderId: String, wireBody: String): String? {
        val theirPub = decodeId(senderId) ?: return null
        val combined = decode(wireBody) ?: return null
        if (combined.size <= Box.NONCEBYTES + Box.MACBYTES) return null
        val nonce = combined.copyOfRange(0, Box.NONCEBYTES)
        val ct = combined.copyOfRange(Box.NONCEBYTES, combined.size)
        val pt = ByteArray(ct.size - Box.MACBYTES)
        if (!sodium.cryptoBoxOpenEasy(pt, ct, ct.size.toLong(), nonce, theirPub, secretKey)) return null
        return String(pt, Charsets.UTF_8)
    }

    private fun decodeId(id: String): ByteArray? =
        decode(id)?.takeIf { it.size == Box.PUBLICKEYBYTES }

    private fun encode(b: ByteArray): String =
        Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun decode(s: String): ByteArray? = runCatching {
        Base64.decode(s.trim(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }.getOrNull()

    companion object {
        private const val KEY_PUB = "pub"
        private const val KEY_SEC = "sec"
    }
}
