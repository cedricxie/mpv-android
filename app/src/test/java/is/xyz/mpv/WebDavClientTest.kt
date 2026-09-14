package `is`.xyz.mpv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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

    @Test
    fun derivesReadableProfileNameFromServerAddress() {
        assertEquals(
            "100.107.181.105",
            WebDavConfigStore.defaultProfileName("https://100.107.181.105:5006"),
        )
        assertEquals(
            "nas-cn.taila7a829.ts.net",
            WebDavConfigStore.defaultProfileName("https://nas-cn.taila7a829.ts.net:5006"),
        )
        assertEquals("NAS", WebDavConfigStore.defaultProfileName("not a URL"))
    }
}
