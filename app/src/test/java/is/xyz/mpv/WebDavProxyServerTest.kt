package `is`.xyz.mpv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.buffer
import okio.source

class WebDavProxyServerTest {
    private val client = WebDavClient(WebDavConfig(
        serverUrl = "https://nas.example:5006", rootPath = "/", username = "user",
        password = "secret", certificateFingerprint = "fingerprint",
    ))

    private fun response(code: Int, text: String, length: Long, range: String? = null): Response {
        val body = object : ResponseBody() {
            private val input = ByteArrayInputStream(text.toByteArray()).source().buffer()
            override fun contentType() = null
            override fun contentLength() = length
            override fun source() = input
        }
        return Response.Builder().request(Request.Builder().url("https://nas.example:5006/video").build())
            .protocol(Protocol.HTTP_1_1).code(code).message("test").body(body)
            .header("ETag", "\"same-file\"")
            .apply { if (range != null) header("Content-Range", range) }.build()
    }

    @Test
    fun resumesTruncatedBodyWithoutDuplicatingBytes() {
        val output = ByteArrayOutputStream()
        var requested: String? = null
        WebDavProxyServer(client, "https://nas.example:5006/video", requestMedia = { range, validator ->
            requested = range
            assertEquals("\"same-file\"", validator)
            response(206, "fghij", 5, "bytes 5-9/10")
        }, waitForRetry = {}, logRetry = {}).use { proxy ->
            response(200, "abcde", 10).use { proxy.streamWithRecovery(it, null, output) }
        }
        assertEquals("bytes=5-9", requested)
        assertEquals("abcdefghij", output.toString("UTF-8"))
    }

    @Test(expected = IOException::class)
    fun rejectsResumeFromWrongBytePosition() {
        WebDavProxyServer(client, "https://nas.example:5006/video", requestMedia = { _, _ ->
            response(206, "fghij", 5, "bytes 0-4/10")
        }, waitForRetry = {}, logRetry = {}).use { proxy ->
            response(200, "abcde", 10).use { proxy.streamWithRecovery(it, null, ByteArrayOutputStream()) }
        }
    }

    @Test
    fun retriesConnectionFailureBeforeContinuingBody() {
        var attempts = 0
        val output = ByteArrayOutputStream()
        WebDavProxyServer(client, "https://nas.example:5006/video", requestMedia = { _, _ ->
            if (++attempts == 1) throw IOException("temporary disconnect")
            response(206, "fghij", 5, "bytes 5-9/10")
        }, waitForRetry = {}, logRetry = {}).use { proxy ->
            response(200, "abcde", 10).use { proxy.streamWithRecovery(it, null, output) }
        }
        assertEquals(2, attempts)
        assertEquals("abcdefghij", output.toString("UTF-8"))
    }

    @Test(expected = IOException::class)
    fun rejectsServerIgnoringResumeRange() {
        WebDavProxyServer(client, "https://nas.example:5006/video", requestMedia = { _, _ ->
            response(200, "abcdefghij", 10)
        }, waitForRetry = {}, logRetry = {}).use { proxy ->
            response(200, "abcde", 10).use { proxy.streamWithRecovery(it, null, ByteArrayOutputStream()) }
        }
    }

    @Test
    fun downstreamCloseDoesNotReconnectToNas() {
        val output = object : java.io.OutputStream() {
            override fun write(value: Int) { throw IOException("player closed request") }
        }
        WebDavProxyServer(client, "https://nas.example:5006/video", requestMedia = { _, _ ->
            throw AssertionError("A player seek must not reconnect the old stream")
        }, waitForRetry = {}, logRetry = {}).use { proxy ->
            response(200, "abcdefghij", 10).use { proxy.streamWithRecovery(it, null, output) }
        }
    }

    @Test
    fun parsesSingleOpenEndedRange() {
        assertEquals(
            WebDavProxyServer.ByteRange(1234L, null),
            WebDavProxyServer.parseByteRange("bytes=1234-"),
        )
    }

    @Test
    fun parsesBoundedRangeAndRejectsInvalidRanges() {
        assertEquals(
            WebDavProxyServer.ByteRange(10L, 99L),
            WebDavProxyServer.parseByteRange("bytes=10-99"),
        )
        assertNull(WebDavProxyServer.parseByteRange("bytes=99-10"))
        assertNull(WebDavProxyServer.parseByteRange("bytes=1-2,4-5"))
    }

    @Test
    fun parsesContentRangeStartAndBuildsResumeRange() {
        assertEquals(4096L, WebDavProxyServer.parseContentRangeStart("bytes 4096-8191/10000"))
        assertEquals("bytes=4096-", WebDavProxyServer.formatResumeRange(4096L, null))
        assertEquals("bytes=4096-8191", WebDavProxyServer.formatResumeRange(4096L, 8191L))
    }
}
