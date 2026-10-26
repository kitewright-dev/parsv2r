package com.v2ray.ang.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class XDriveRepairTest {

    private fun config(outbound: String) = JsonParser.parseString(
        """{"outbounds":[$outbound,{"tag":"direct","protocol":"freedom"}]}"""
    ).asJsonObject

    private fun JsonObject.proxy() = getAsJsonArray("outbounds")[0].asJsonObject

    // the outbound 3X-UI's JSON subscription writes for an XDRIVE inbound while subJsonMux is empty
    private val panelOutbound = """
        {"tag":"proxy","protocol":"vless",
         "settings":{"vnext":[{"address":"panel.example","port":443,
           "users":[{"id":"5f0c3a2e-8d21-4b7e-9a51-2c6f0e9b7d10","encryption":"mlkem768x25519plus.native.0rtt.abc","flow":""}]}]},
         "streamSettings":{"network":"xdrive","security":"none",
           "xdriveSettings":{"service":"google-drive","remoteFolder":"f","secrets":["a","b","c"]}}}
    """.trimIndent()

    @Test
    fun addsTheMuxThePanelLeftOut() {
        val root = config(panelOutbound)
        val report = XDriveRepair.repair(root)
        assertEquals(JsonParser.parseString("""{"enabled":true}"""), root.proxy().get("mux"))
        assertEquals(1, report.outbounds)
        assertEquals(1, report.muxFixed)
        assertEquals(1, report.flowRemoved)
        assertEquals(0, report.missingEncryption)
        assertTrue(report.changed)
    }

    @Test
    fun turnsEveryRefusedMuxSettingIntoOneTheCoreTakes() {
        val root = config(panelOutbound.replace(
            "\"tag\":\"proxy\",",
            "\"tag\":\"proxy\",\"mux\":{\"enabled\":false,\"concurrency\":-1,\"xudpConcurrency\":-1,\"xudpProxyUDP443\":\"skip\"},"
        ))
        XDriveRepair.repair(root)
        assertEquals(
            JsonParser.parseString("""{"enabled":true,"concurrency":0,"xudpConcurrency":0,"xudpProxyUDP443":"reject"}"""),
            root.proxy().get("mux")
        )
    }

    @Test
    fun replacesEnabledWrittenAsAStringWithABoolean() {
        val root = config(panelOutbound.replace("\"tag\":\"proxy\",", "\"tag\":\"proxy\",\"mux\":{\"enabled\":\"true\"},"))
        val report = XDriveRepair.repair(root)
        assertTrue(root.proxy().getAsJsonObject("mux").get("enabled").asJsonPrimitive.isBoolean)
        assertEquals(1, report.muxFixed)
    }

    @Test
    fun keepsAValidMuxAsItIs() {
        val mux = """{"enabled":true,"concurrency":4,"xudpConcurrency":8,"xudpProxyUDP443":"allow"}"""
        val root = config(panelOutbound.replace(",\"flow\":\"\"", "").replace("\"tag\":\"proxy\",", "\"tag\":\"proxy\",\"mux\":$mux,"))
        val before = root.deepCopy()
        val report = XDriveRepair.repair(root)
        assertEquals(before, root)
        assertFalse(report.changed)
        assertEquals(1, report.outbounds)
    }

    @Test
    fun readsTheNewerMethodKeyAndTheFlatVlessSettings() {
        val root = config("""
            {"protocol":"vless",
             "settings":{"address":"x","port":443,"id":"u","encryption":"mlkem768x25519plus.native.0rtt.k","flow":"xtls-rprx-vision","testpre":2},
             "streamSettings":{"method":"XDRIVE","xdriveSettings":{"service":"local","remoteFolder":"/tmp/x"}}}
        """.trimIndent())
        val report = XDriveRepair.repair(root)
        val settings = root.proxy().getAsJsonObject("settings")
        assertNull(settings.get("flow"))
        assertNull(settings.get("testpre"))
        assertEquals(1, report.muxFixed)
        assertEquals(1, report.testpreRemoved)
    }

    @Test
    fun leavesOtherTransportsAlone() {
        val ws = panelOutbound.replace("\"network\":\"xdrive\"", "\"network\":\"ws\"")
        val root = config(ws)
        val before = root.deepCopy()
        val report = XDriveRepair.repair(root)
        assertEquals(before, root)
        assertEquals(0, report.outbounds)
    }

    @Test
    fun doesNotForceMuxOnVlessReverse() {
        val root = config(panelOutbound.replace(",\"flow\":\"\"", ",\"reverse\":{\"tag\":\"r\"}"))
        val report = XDriveRepair.repair(root)
        assertNull(root.proxy().get("mux"))
        assertEquals(0, report.muxFixed)
    }

    @Test
    fun countsOutboundsWithoutEncryptionWithoutTouchingThem() {
        val none = config(panelOutbound.replace("mlkem768x25519plus.native.0rtt.abc", "none"))
        assertEquals(1, XDriveRepair.repair(none).missingEncryption)
        assertEquals("none", none.proxy().getAsJsonObject("settings").getAsJsonArray("vnext")[0].asJsonObject
            .getAsJsonArray("users")[0].asJsonObject.get("encryption").asString)

        val absent = config(panelOutbound.replace("\"encryption\":\"mlkem768x25519plus.native.0rtt.abc\",", ""))
        assertEquals(1, XDriveRepair.repair(absent).missingEncryption)
    }

    @Test
    fun ignoresAConfigWithoutOutbounds() {
        val root = JsonParser.parseString("""{"inbounds":[]}""").asJsonObject
        assertEquals(XDriveRepair.Report(), XDriveRepair.repair(root))
    }
}
