package `is`.xyz.mpv

import android.net.Uri
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import javax.xml.parsers.DocumentBuilderFactory

data class WebDavEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long?,
    val contentType: String?,
)

class CertificateTrustRequired(val fingerprint: String) : Exception()

class WebDavClient(private val config: WebDavConfig) {
    val authorizationHeader: String = Credentials.basic(config.username, config.password)

    private val client: OkHttpClient by lazy {
        if (config.serverUrl.startsWith("https://", ignoreCase = true)) {
            val fingerprint = config.certificateFingerprint
                ?: throw CertificateTrustRequired(probeCertificateFingerprint())
            pinnedClient(fingerprint)
        } else {
            baseClient().build()
        }
    }

    fun list(path: String): List<WebDavEntry> {
        val request = requestBuilder(path)
            .header("Depth", "1")
            .method("PROPFIND", "".toRequestBody(XML_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401) throw IllegalArgumentException("用户名或密码错误")
            if (response.code != 207) throw IllegalStateException("HTTP ${response.code}")
            val body = response.body?.byteStream() ?: return emptyList()
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                runCatching {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                }
                runCatching {
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                }
                runCatching {
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                }
            }
            val document = factory.newDocumentBuilder().parse(body)
            val responses = document.getElementsByTagNameNS("DAV:", "response")
            val normalizedCurrent = WebDavConfigStore.normalizeRoot(path)
            return buildList {
                for (index in 0 until responses.length) {
                    val element = responses.item(index) as? Element ?: continue
                    val href = element.getElementsByTagNameNS("DAV:", "href")
                        .item(0)?.textContent ?: continue
                    val decodedPath = Uri.decode(href)
                    val isDirectory = element
                        .getElementsByTagNameNS("DAV:", "collection").length > 0
                    val normalizedPath = if (isDirectory) {
                        WebDavConfigStore.normalizeRoot(decodedPath)
                    } else decodedPath
                    if (normalizedPath == normalizedCurrent) continue
                    val name = decodedPath.trimEnd('/').substringAfterLast('/')
                    if (name.isBlank()) continue
                    val sizeText = element.getElementsByTagNameNS("DAV:", "getcontentlength")
                        .item(0)?.textContent
                    val contentType = element.getElementsByTagNameNS("DAV:", "getcontenttype")
                        .item(0)?.textContent
                    add(WebDavEntry(name, normalizedPath, isDirectory, sizeText?.toLongOrNull(), contentType))
                }
            }.sortedWith(compareBy<WebDavEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
        }
    }

    fun readText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authorizationHeader)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            return response.body?.string() ?: ""
        }
    }

    fun probeCertificateFingerprint(): String {
        val trustManager = trustAllManager()
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }
        val uri = URI(config.serverUrl)
        val port = if (uri.port > 0) uri.port else 443
        val socket = sslContext.socketFactory.createSocket(uri.host, port) as SSLSocket
        socket.soTimeout = 10_000
        socket.use {
            it.startHandshake()
            val certificate = it.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: throw IllegalStateException("NAS 没有提供 TLS 证书")
            return fingerprint(certificate)
        }
    }

    fun urlForPath(path: String): String {
        val encodedPath = Uri.encode(if (path.startsWith('/')) path else "/$path", "/")
        return config.serverUrl.trimEnd('/') + encodedPath
    }

    private fun requestBuilder(path: String): Request.Builder = Request.Builder()
        .url(urlForPath(path))
        .header("Authorization", authorizationHeader)

    private fun pinnedClient(expectedFingerprint: String): OkHttpClient {
        val trustManager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val actual = chain.firstOrNull()?.let(::fingerprint)
                if (!actual.equals(expectedFingerprint, ignoreCase = true)) {
                    throw java.security.cert.CertificateException("NAS 证书已经改变")
                }
            }
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }
        return baseClient()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    private fun baseClient() = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)

    private fun trustAllManager() = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
    }

    private fun fingerprint(certificate: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    companion object {
        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
    }
}
