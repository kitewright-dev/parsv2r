package com.v2ray.ang.util

import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.handler.SubscriptionPanel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Fetches a subscription from a real local HTTP server that answers the way a Marzban-style
 * panel does, so the header capture is tested through OkHttp itself and not just the parser.
 */
class HttpUtilPanelHeadersTest {

    private lateinit var server: ServerSocket
    private val body = "dmxlc3M6Ly91dWlkQDEuMi4zLjQ6NDQzP3NlY3VyaXR5PXRscyNERQ==" // base64 of one vless link
    @Volatile
    private var seenUserAgent: String? = null

    @BeforeEach
    fun start() {
        // A few lines of HTTP/1.1 on a plain socket: java.net is all an Android unit test has.
        server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                runCatching { serve(socket) }
                runCatching { socket.close() }
            }
        }
    }

    private fun serve(socket: Socket) {
        val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val path = reader.readLine().orEmpty().split(' ').getOrNull(1).orEmpty()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("User-Agent:", ignoreCase = true)) seenUserAgent = line.substringAfter(':').trim()
        }
        val response = when (path) {
            "/sub/abc" -> buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Subscription-Userinfo: upload=100; download=200; total=1000; expire=1900000000\r\n")
                append("Profile-Update-Interval: 1\r\n")
                append("Profile-Title: base64:2YbZiNiv2qnYsw==\r\n")
                append("Content-Type: text/plain; charset=utf-8\r\n")
                append("Content-Length: ${body.length}\r\n")
                append("Connection: close\r\n\r\n")
                append(body)
            }

            "/old" -> "HTTP/1.1 302 Found\r\nLocation: /sub/abc\r\n" +
                    "Profile-Title: from the redirect, must not win\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"

            else -> "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        }
        socket.getOutputStream().apply {
            write(response.toByteArray(Charsets.UTF_8))
            flush()
        }
    }

    @AfterEach
    fun stop() {
        server.close()
    }

    private fun url(path: String) = "http://127.0.0.1:${server.localPort}$path"

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
