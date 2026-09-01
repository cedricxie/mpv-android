package `is`.xyz.mpv

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class WebDavProxyServer(
    private val client: WebDavClient,
    private val targetUrl: String,
) : Closeable {
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

                client.executeMediaRequest(targetUrl, range).use { response ->
                    writeLine(output, "HTTP/1.1 ${response.code} ${reason(response.code)}")
                    FORWARDED_HEADERS.forEach { name ->
                        response.header(name)?.let { writeLine(output, "$name: $it") }
                    }
                    writeLine(output, "Connection: close")
                    writeLine(output, "")
                    if (method == "GET")
                        response.body?.byteStream()?.use { body -> body.copyTo(output) }
                    output.flush()
                }
            } catch (_: Exception) {
                runCatching { sendError(output, 502, "Bad Gateway") }
            }
        }
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
        private const val MAX_HEADER_COUNT = 64
        private const val MAX_LINE_LENGTH = 8192
        private val FORWARDED_HEADERS = listOf(
            "Accept-Ranges",
            "Content-Length",
            "Content-Range",
            "Content-Type",
            "ETag",
            "Last-Modified",
        )
    }
}
