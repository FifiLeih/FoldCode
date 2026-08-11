package dev.foldcode.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPreviewBrowserTest {
    @Test
    fun addressBarAcceptsWebAddressesAndLocalPreviewPorts() {
        assertEquals("https://example.com", browserAddressToUrl("example.com"))
        assertEquals("http://localhost:3000", browserAddressToUrl("localhost:3000"))
        assertEquals("http://127.0.0.1:5173", browserAddressToUrl("127.0.0.1:5173"))
        assertEquals("https://www.raspberrypi.com", browserAddressToUrl("https://www.raspberrypi.com"))
    }

    @Test
    fun addressBarSearchesPlainTextAndRejectsNonWebSchemes() {
        assertEquals(
            "https://www.google.com/search?q=pico%20sdk",
            browserAddressToUrl("pico sdk"),
        )
        assertNull(browserAddressToUrl("javascript:alert(1)"))
        assertNull(browserAddressToUrl("file:///data/local/tmp/file.html"))
    }

    @Test
    fun localPreviewDetectionDoesNotTreatInternetPagesAsPreviewServers() {
        assertTrue(isLocalWebAddress("http://localhost:3000"))
        assertTrue(isLocalWebAddress("http://127.0.0.1:5173/app"))
        assertFalse(isLocalWebAddress("https://www.google.com"))
    }

    @Test
    fun staleLocalhostFailureCannotHideReplacementInternetPage() {
        val activePage = "https://www.google.com"

        assertFalse(isCurrentWebNavigation(activePage, "http://127.0.0.1:3000"))
        assertTrue(isCurrentWebNavigation(activePage, activePage))
    }

    @Test
    fun webViewUrlNormalizationKeepsCallbacksAttachedToTheirNavigation() {
        assertTrue(
            isCurrentWebNavigation(
                "http://127.0.0.1:3000",
                "http://127.0.0.1:3000/",
            ),
        )
        assertTrue(
            isCurrentWebNavigation(
                "https://EXAMPLE.com:443/app#editor",
                "https://example.com/app",
            ),
        )
        assertFalse(
            isCurrentWebNavigation(
                "http://127.0.0.1:3000/app",
                "http://127.0.0.1:3000/other",
            ),
        )
    }
}
