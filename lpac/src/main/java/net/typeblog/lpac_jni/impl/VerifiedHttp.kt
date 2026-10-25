package net.typeblog.lpac_jni.impl

import net.typeblog.lpac_jni.HttpInterface
import java.net.URL
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.*

/** System Web PKI plus the card's advertised GSMA CI roots; hostname checking remains enabled. */
class VerifiedHttp : HttpInterface {
    private var socketFactory: SSLSocketFactory? = null
    override fun usePublicKeyIds(pkids: Array<String>) {
        fun managers(store: KeyStore?): List<X509TrustManager> = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }.trustManagers.filterIsInstance<X509TrustManager>()
        val trusted = managers(null) + managers(keyIdToKeystore(pkids.map { it.lowercase() }.toTypedArray()))
        val manager = object : X509TrustManager {
            override fun getAcceptedIssuers() = trusted.flatMap { it.acceptedIssuers.toList() }.toTypedArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, auth: String) { throw java.security.cert.CertificateException("Client trust unsupported") }
            override fun checkServerTrusted(chain: Array<X509Certificate>, auth: String) {
                for (tm in trusted) try { tm.checkServerTrusted(chain, auth); return } catch (_: java.security.cert.CertificateException) { }
                throw java.security.cert.CertificateException("SM-DP+ certificate chain not trusted")
            }
        }
        socketFactory = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }.socketFactory
    }
    override fun transmit(url: String, tx: ByteArray, headers: Array<String>): HttpInterface.HttpResponse {
        val target = URL(url)
        require(target.protocol == "https" && target.userInfo == null) { "SM-DP+ requires HTTPS" }
        val conn = target.openConnection() as HttpsURLConnection
        try {
            socketFactory?.let { conn.sslSocketFactory = it }
            conn.connectTimeout = 15000; conn.readTimeout = 45000
            conn.instanceFollowRedirects = false; conn.requestMethod = "POST"; conn.doOutput = true
            for (header in headers) { val pair = header.split(':', limit = 2); require(pair.size == 2); conn.setRequestProperty(pair[0], pair[1].trim()) }
            conn.outputStream.use { it.write(tx) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { it.readNBytes(4 * 1024 * 1024 + 1) } ?: byteArrayOf()
            require(body.size <= 4 * 1024 * 1024) { "SM-DP+ response too large" }
            return HttpInterface.HttpResponse(code, body)
        } finally { conn.disconnect() }
    }
}
