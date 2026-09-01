package `is`.xyz.mpv

import android.net.Uri
import android.util.Xml
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

data class WebDavEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long?,
    val contentType: String?,
)

data class WebDavServerCertificate(
    val fingerprint: String,
    val pem: String,
)

class CertificateTrustRequired(val fingerprint: String) : Exception()

class WebDavClient(private val config: WebDavConfig) {
    val authorizationHeader: String = Credentials.basic(config.username, config.password)
    private val serverUri = URI(config.serverUrl)

    init {
        require(serverUri.scheme.equals("https", ignoreCase = true)) {
            "WebDAV 必须使用 HTTPS"
        }
        require(!serverUri.host.isNullOrBlank()) { "WebDAV 服务器地址无效" }
    }

    private val client: OkHttpClient by lazy {
        if (config.serverUrl.startsWith("https://", ignoreCase = true)) {
            val fingerprint = config.certificateFingerprint
                ?: throw CertificateTrustRequired(probeCertificate().fingerprint)
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
            return parseDirectory(body, path)
        }
    }

    private fun parseDirectory(input: InputStream, currentPath: String): List<WebDavEntry> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            setInput(input, null)
        }
        val entries = mutableListOf<WebDavEntry>()
        val normalizedCurrent = WebDavConfigStore.normalizeRoot(currentPath)
        var href: String? = null
        var isDirectory = false
        var size: Long? = null
        var contentType: String? = null

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "response" -> {
                        href = null
                        isDirectory = false
                        size = null
                        contentType = null
                    }
                    "href" -> href = parser.nextText()
                    "collection" -> isDirectory = true
                    "getcontentlength" -> size = parser.nextText().toLongOrNull()
                    "getcontenttype" -> contentType = parser.nextText()
                }
                XmlPullParser.END_TAG -> if (parser.name.equals("response", ignoreCase = true)) {
                    val decodedPath = href?.let { URI(it).path }
                    if (decodedPath != null && isAllowedPath(decodedPath)) {
                        val normalizedPath = if (isDirectory) {
                            WebDavConfigStore.normalizeRoot(decodedPath)
                        } else decodedPath
                        val name = decodedPath.trimEnd('/').substringAfterLast('/')
                        if (normalizedPath != normalizedCurrent && name.isNotBlank()) {
                            entries.add(WebDavEntry(name, normalizedPath, isDirectory, size, contentType))
                        }
                    }
                }
            }
            parser.next()
        }
        return entries.sortedWith(
            compareBy<WebDavEntry> { !it.isDirectory }.thenBy { it.name.lowercase() }
        )
    }

    fun readText(url: String): String {
        require(isAllowedUrl(url)) { "WebDAV URL 不属于已配置的 NAS 目录" }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authorizationHeader)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            return response.body?.string() ?: ""
        }
    }

    fun probeCertificate(): WebDavServerCertificate {
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
            return WebDavServerCertificate(
                fingerprint = fingerprint(certificate),
                pem = certificatePem(certificate),
            )
        }
    }

    fun isAllowedUrl(url: String): Boolean = runCatching {
        val candidate = URI(url)
        candidate.scheme.equals(serverUri.scheme, ignoreCase = true) &&
            candidate.host.equals(serverUri.host, ignoreCase = true) &&
            effectivePort(candidate) == effectivePort(serverUri) &&
            candidate.userInfo == null &&
            candidate.fragment == null &&
            isAllowedPath(candidate.path ?: "/")
    }.getOrDefault(false)

    fun urlForPath(path: String): String {
        require(isAllowedPath(path)) { "WebDAV 路径超出已配置的 NAS 根目录" }
        val encodedPath = Uri.encode(if (path.startsWith('/')) path else "/$path", "/")
        return config.serverUrl.trimEnd('/') + encodedPath
    }

    private fun requestBuilder(path: String): Request.Builder = Request.Builder()
        .url(urlForPath(path))
        .header("Authorization", authorizationHeader)

    private fun isAllowedPath(path: String): Boolean {
        val normalizedPath = "/${path.trimStart('/')}"
        if (normalizedPath.split('/').any { it == "." || it == ".." }) return false
        val root = WebDavConfigStore.normalizeRoot(config.rootPath)
        return root == "/" || normalizedPath.startsWith(root)
    }

    private fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }

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

    private fun certificatePem(certificate: X509Certificate): String {
        val encoded = android.util.Base64.encodeToString(certificate.encoded, android.util.Base64.NO_WRAP)
        return buildString {
            appendLine("-----BEGIN CERTIFICATE-----")
            encoded.chunked(64).forEach(::appendLine)
            appendLine("-----END CERTIFICATE-----")
        }
    }

    companion object {
        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
    }
}
