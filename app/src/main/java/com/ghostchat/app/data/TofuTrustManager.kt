package com.ghostchat.app.data

import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager

class PinMismatchException(
    val host: String,
    val expectedPin: ByteArray,
    val actualPin: ByteArray
) : CertificateException("TOFU pin mismatch for $host")

class TofuTrustManager(
    private val pinStore: PinStore
) : X509ExtendedTrustManager() {

    private val systemDefault: X509ExtendedTrustManager = run {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        tmf.trustManagers.filterIsInstance<X509ExtendedTrustManager>().firstOrNull()
            ?: error("No system X509ExtendedTrustManager available")
    }

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
        socket: Socket?
    ) {
        systemDefault.checkServerTrusted(chain, authType, socket)
        val host = (socket?.remoteSocketAddress as? InetSocketAddress)?.hostString
        verifyPin(host, chain)
    }

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
        engine: SSLEngine?
    ) {
        systemDefault.checkServerTrusted(chain, authType, engine)
        verifyPin(engine?.peerHost, chain)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        systemDefault.checkServerTrusted(chain, authType)
    }

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
        socket: Socket?
    ) = systemDefault.checkClientTrusted(chain, authType, socket)

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
        engine: SSLEngine?
    ) = systemDefault.checkClientTrusted(chain, authType, engine)

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        systemDefault.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = systemDefault.acceptedIssuers

    private fun verifyPin(host: String?, chain: Array<out X509Certificate>) {
        if (host.isNullOrBlank()) {
            throw CertificateException("TOFU: missing SNI/peer host; refusing to pin-anonymous connection")
        }
        if (chain.isEmpty()) throw CertificateException("Empty cert chain")
        val leafSpki = sha256(chain[0].publicKey.encoded)
        val stored = pinStore.get(host)
        if (stored == null) {
            pinStore.put(host, leafSpki)
            return
        }
        if (!MessageDigest.isEqual(stored, leafSpki)) {
            throw PinMismatchException(host, stored, leafSpki)
        }
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)
}
