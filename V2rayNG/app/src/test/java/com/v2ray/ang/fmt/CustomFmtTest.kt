package com.v2ray.ang.fmt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CustomFmtTest {

    private fun config(outbounds: String) =
        """{"inbounds":[],"outbounds":[$outbounds],"routing":{}}"""

    @Test
    fun anXdriveConfigSaysXdrive() {
        // The shape 3X-UI's JSON subscription serves: no share link exists for XDRIVE
        val json = config(
            """{"protocol":"vless","settings":{"vnext":[{"address":"drive","port":443,"users":[{"id":"x"}]}]},
               "streamSettings":{"network":"xdrive","security":"none",
               "xdriveSettings":{"service":"google-drive","remoteFolder":"f","secrets":["a","b","c"]}}},
               {"protocol":"freedom","tag":"direct"}"""
        )
        assertEquals("xdrive", CustomFmt.transportOf(json))
    }

    @Test
    fun theNewerMethodKeyIsRead() {
        val json = config("""{"protocol":"vless","streamSettings":{"method":"xdrive"}}""")
        assertEquals("xdrive", CustomFmt.transportOf(json))
    }

    @Test
    fun aMasqueOutboundSaysMasque() {
        val json = config(
            """{"protocol":"freedom","tag":"direct"},
               {"protocol":"masque","settings":{"address":"example.com","port":443},
               "streamSettings":{"method":"masque","security":"tls"}}"""
        )
        assertEquals("masque", CustomFmt.transportOf(json))
        val profile = CustomFmt.parse(json)
        assertEquals("example.com", profile.server)
        assertEquals("443", profile.serverPort)
        assertEquals("masque", profile.network)
    }

    @Test
    fun internalNamesBecomeTheOnesPeopleKnow() {
        assertEquals("xhttp", CustomFmt.transportOf(config("""{"protocol":"vless","streamSettings":{"network":"splithttp"}}""")))
        assertEquals("ws", CustomFmt.transportOf(config("""{"protocol":"vless","streamSettings":{"network":"websocket"}}""")))
    }

    @Test
    fun plainTcpAndBrokenJsonGiveNothing() {
        assertNull(CustomFmt.transportOf(config("""{"protocol":"vless","streamSettings":{"network":"raw"}}""")))
        assertNull(CustomFmt.transportOf(config("""{"protocol":"vless"}""")))
        assertNull(CustomFmt.transportOf(config("""{"protocol":"freedom"}""")))
        assertNull(CustomFmt.transportOf("not json"))
        assertNull(CustomFmt.transportOf("""{"outbounds":"nope"}"""))
    }
}
