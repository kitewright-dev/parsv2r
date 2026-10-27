package com.v2ray.ang.util

import com.sun.net.httpserver.HttpServer
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.handler.SubscriptionPanel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

/**
 * Fetches a subscription from a real local HTTP server that answers the way a Marzban-style
 * panel does, so the header capture is tested through OkHttp itself and not just the parser.
 */
class HttpUtilPanelHeadersTest {

    private lateinit var server: HttpServer
    private val body = "dmxlc3M6Ly91dWlkQDEuMi4zLjQ6NDQzP3NlY3VyaXR5PXRscyNERQ==" // base64 of one vless link
    private var seenUserAgent: String? = null

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/sub/abc") { ex ->
            seenUserAgent = ex.requestHeaders.getFirst("User-Agent")
            ex.responseHeaders.add("Subscription-Userinfo", "upload=100; download=200; total=1000; expire=1900000000")
            ex.responseHeaders.add("Profile-Update-Interval", "1")
            ex.responseHeaders.add("Profile-Title", "base64:2YbZiNiv2qnYsw==")
            ex.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.createContext("/old") { ex ->
            ex.responseHeaders.add("Location", "/sub/abc")
            ex.responseHeaders.add("Profile-Title", "from the redirect, must not win")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    @Test
    fun bodyAndPanelHeadersComeBackTogether() {
        val response = HttpUtil.getUrlResponseWithUserAgent(UrlContentRequest(url = url("/sub/abc"), timeout = 5000))
        assertEquals(body, response.body)
        assertEquals("1", response.headers["profile-update-interval"], "names are lower-cased")
        assertTrue(seenUserAgent.orEmpty().startsWith("v2rayNG/"), "default user agent kept: $seenUserAgent")

        val info = SubscriptionPanel.parse(response.headers, listOf(response.body), now = 1L)
        assertNotNull(info)
        assertEquals(300L, info!!.usedBytes)
        assertEquals(1000L, info.total)
        assertEquals(1900000000L, info.expire)
        assertEquals(1, info.updateIntervalHours)
        assertEquals("نودکس", info.title)
    }

    @Test
    fun headersAreTakenFromTheFinalResponseAfterARedirect() {
        val response = HttpUtil.getUrlResponseWithUserAgent(UrlContentRequest(url = url("/old"), timeout = 5000))
        assertEquals(body, response.body)
        assertEquals("base64:2YbZiNiv2qnYsw==", response.headers["profile-title"])
    }

    @Test
    fun theOldBodyOnlyCallStillReturnsTheBody() {
        assertEquals(body, HttpUtil.getUrlContentWithUserAgent(UrlContentRequest(url = url("/sub/abc"), timeout = 5000)))
    }
}
