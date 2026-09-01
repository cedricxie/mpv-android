package `is`.xyz.mpv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavClientTest {
    private val client = WebDavClient(
        WebDavConfig(
            serverUrl = "https://nas.example:5006",
            rootPath = "/Data/TV Shows/",
            username = "user",
            password = "secret",
            certificateFingerprint = "fingerprint",
        )
    )

    @Test
    fun allowsOnlyConfiguredOriginAndRoot() {
        assertTrue(client.isAllowedUrl("https://nas.example:5006/Data/TV%20Shows/Episode.avi"))
        assertFalse(client.isAllowedUrl("http://nas.example:5006/Data/TV%20Shows/Episode.avi"))
        assertFalse(client.isAllowedUrl("https://evil.example:5006/Data/TV%20Shows/Episode.avi"))
        assertFalse(client.isAllowedUrl("https://nas.example:5006/Other/Episode.avi"))
    }

    @Test
    fun rejectsTraversalOutsideRoot() {
        assertFalse(client.isAllowedUrl("https://nas.example:5006/Data/TV%20Shows/../private.txt"))
        assertFalse(client.isAllowedUrl("https://nas.example:5006/Data/TV%20Shows/%2e%2e/private.txt"))
    }
}
