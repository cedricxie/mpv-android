package `is`.xyz.mpv

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import android.util.Log

class WebDavProxyServer(
    private val client: WebDavClient,
    private val targetUrl: String,
    private val onStreamFailure: (String) -> Unit = {},
    private val requestMedia: (String?, String?) -> okhttp3.Response = { range, validator ->
        client.executeMediaRequest(targetUrl, range, validator)
    },
    private val waitForRetry: (Long) -> Unit = { Thread.sleep(it) },
    private val logRetry: (String) -> Unit = { Log.w(TAG, it); Unit },
) : Closeable {
    internal data class ByteRange(val start: Long, val endInclusive: Long?)
    private val running = AtomicBoolean(true)
    private val token = UUID.randomUUID().toString().replace("-", "")
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool()

    val playbackUrl: String = "http://127.0.0.1:${server.localPort}/$token/media"

    init {
        executor.execute {
            while (running.get()) {
                try {
                    val socket = server.accept()
                    executor.execute { handle(socket) }
                } catch (_: Exception) {
                    if (running.get()) continue
                    break
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use {
            it.soTimeout = 15_000
            val input = it.getInputStream()
            val output = it.getOutputStream()
            var responseStarted = false
            try {
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ', limit = 3)
                if (parts.size != 3 || parts[1] != "/$token/media") {
                    sendError(output, 404, "Not Found")
                    return
                }
                val method = parts[0].uppercase()
                if (method != "GET" && method != "HEAD") {
                    sendError(output, 405, "Method Not Allowed")
                    return
                }

                var range: String? = null
                for (index in 0 until MAX_HEADER_COUNT) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator > 0 && line.substring(0, separator).equals("Range", true))
                        range = line.substring(separator + 1).trim()
                }

                val response = requestMedia(range, null)
                response.use {
                    writeLine(output, "HTTP/1.1 ${response.code} ${reason(response.code)}")
                    FORWARDED_HEADERS.forEach { name ->
                        response.header(name)?.let { writeLine(output, "$name: $it") }
                    }
                    writeLine(output, "Connection: close")
                    writeLine(output, "")
                    responseStarted = true
                    if (method == "GET") {
                        if (response.isSuccessful)
                            streamWithRecovery(response, range, output)
                        else
                            response.body?.byteStream()?.use { body -> body.copyTo(output) }
                    }
                    output.flush()
                }
            } catch (error: Exception) {
                Log.e(TAG, "WebDAV media stream failed", error)
                onStreamFailure(error.message ?: error.javaClass.simpleName)
                if (!responseStarted)
                    runCatching { sendError(output, 502, "Bad Gateway") }
            }
        }
    }

    internal fun streamWithRecovery(
        initialResponse: okhttp3.Response,
        requestedRange: String?,
        output: OutputStream,
    ) {
        val originalRange = parseByteRange(requestedRange)
        val initialStart = if (initialResponse.code == 206)
            parseContentRangeStart(initialResponse.header("Content-Range"))
                ?: throw IOException("NAS returned an invalid byte range")
        else 0L
        val validator = initialResponse.header("ETag")?.takeUnless { it.startsWith("W/") }
            ?: initialResponse.header("Last-Modified")
        val expectedBytes = initialResponse.body?.contentLength()?.takeIf { it >= 0L }
        val resumeEnd = expectedBytes?.let { initialStart + it - 1L }
            ?: originalRange?.endInclusive
        val buffer = ByteArray(STREAM_BUFFER_SIZE)
        var response = initialResponse
        var transferred = 0L
        var lastFailure: Exception? = null

        for (attempt in 0..MAX_STREAM_RETRIES) {
            var inputFailed = false
            response.body?.byteStream()?.use { input ->
                while (expectedBytes == null || transferred < expectedBytes) {
                    val count = try {
                        val remaining = expectedBytes?.let { it - transferred }
                        input.read(buffer, 0, remaining?.coerceAtMost(buffer.size.toLong())?.toInt() ?: buffer.size)
                    } catch (error: IOException) {
                        lastFailure = error
                        inputFailed = true
                        break
                    }
                    if (count == -1) {
                        inputFailed = expectedBytes != null && transferred < expectedBytes
                        if (inputFailed)
                            lastFailure = IOException("NAS closed the stream before all bytes arrived")
                        break
                    }
                    try {
                        output.write(buffer, 0, count)
                    } catch (_: IOException) {
                        // mpv closed this request, usually because it issued a new seek. This is
                        // normal and must not be treated as an upstream NAS failure.
                        return
                    }
                    transferred += count
                }
            }

            if (!inputFailed || (expectedBytes != null && transferred >= expectedBytes))
                return
            if (attempt == MAX_STREAM_RETRIES)
                break

            val nextStart = initialStart + transferred
            val retryRange = formatResumeRange(nextStart, resumeEnd)
            val retryNumber = attempt + 1
            logRetry("WebDAV stream interrupted; retry $retryNumber/$MAX_STREAM_RETRIES from byte $nextStart")
            waitForRetry(RETRY_DELAYS_MS[attempt])
            response.close()
            response = reconnect(retryRange, validator)
            if (response.code != 206 ||
                parseContentRangeStart(response.header("Content-Range")) != nextStart
            ) {
                val code = response.code
                response.close()
                throw IOException("NAS could not resume the stream (HTTP $code)")
            }
        }
        response.close()
        throw IOException("NAS stream recovery failed after $MAX_STREAM_RETRIES retries", lastFailure)
    }

    private fun reconnect(range: String, validator: String?): okhttp3.Response {
        var lastFailure: Exception? = null
        for (attempt in 0 until MAX_STREAM_RETRIES) {
            if (!running.get()) throw IOException("Media proxy was closed")
            try {
                return requestMedia(range, validator)
            } catch (error: IOException) {
                lastFailure = error
                if (attempt < MAX_STREAM_RETRIES - 1)
                    waitForRetry(RETRY_DELAYS_MS[attempt])
            }
        }
        throw IOException("Could not reconnect to NAS", lastFailure)
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (buffer.size() <= MAX_LINE_LENGTH) {
            val value = input.read()
            if (value == -1)
                return if (buffer.size() == 0) null else buffer.toString(StandardCharsets.ISO_8859_1.name())
            if (value == '\n'.code)
                return buffer.toString(StandardCharsets.ISO_8859_1.name())
            if (value != '\r'.code)
                buffer.write(value)
        }
        throw IllegalArgumentException("HTTP header line is too long")
    }

    private fun sendError(output: OutputStream, code: Int, message: String) {
        val body = "$code $message\n".toByteArray(StandardCharsets.UTF_8)
        writeLine(output, "HTTP/1.1 $code $message")
        writeLine(output, "Content-Type: text/plain; charset=utf-8")
        writeLine(output, "Content-Length: ${body.size}")
        writeLine(output, "Connection: close")
        writeLine(output, "")
        output.write(body)
        output.flush()
    }

    private fun writeLine(output: OutputStream, value: String) {
        output.write((value + "\r\n").toByteArray(StandardCharsets.ISO_8859_1))
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        206 -> "Partial Content"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        416 -> "Range Not Satisfiable"
        else -> "Upstream Response"
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { server.close() }
        executor.shutdownNow()
    }

    companion object {
        private const val TAG = "MpvStudyWebDavProxy"
        private const val MAX_HEADER_COUNT = 64
        private const val MAX_LINE_LENGTH = 8192
        private const val MAX_STREAM_RETRIES = 3
        private const val STREAM_BUFFER_SIZE = 64 * 1024
        private val RETRY_DELAYS_MS = longArrayOf(250L, 500L, 1000L)
        private val BYTE_RANGE = Regex("^bytes=(\\d+)-(\\d*)$", RegexOption.IGNORE_CASE)
        private val CONTENT_RANGE = Regex("^bytes\\s+(\\d+)-\\d+/", RegexOption.IGNORE_CASE)
        private val FORWARDED_HEADERS = listOf(
            "Accept-Ranges",
            "Content-Length",
            "Content-Range",
            "Content-Type",
            "ETag",
            "Last-Modified",
        )

        internal fun parseByteRange(value: String?): ByteRange? {
            val match = value?.trim()?.let(BYTE_RANGE::matchEntire) ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val end = match.groupValues[2].takeIf(String::isNotEmpty)?.toLongOrNull()
            if (end != null && end < start) return null
            return ByteRange(start, end)
        }

        internal fun parseContentRangeStart(value: String?): Long? =
            value?.trim()?.let(CONTENT_RANGE::find)?.groupValues?.get(1)?.toLongOrNull()

        internal fun formatResumeRange(start: Long, endInclusive: Long?): String =
            "bytes=$start-${endInclusive ?: ""}"
    }
}
