package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ServerProtocolDescriptionTest {

    @Test
    fun aJsonProfileNamesItsTransport() {
        val xdrive = ProfileItem.create(EConfigType.CUSTOM).apply { network = "xdrive" }
        assertEquals("CUSTOM / xdrive", serverProtocolDescription(xdrive))
    }

    @Test
    fun aJsonProfileWithoutATransportSaysCustom() {
        assertEquals("CUSTOM", serverProtocolDescription(ProfileItem.create(EConfigType.CUSTOM)))
        assertEquals("CUSTOM", serverProtocolDescription(ProfileItem.create(EConfigType.CUSTOM).apply { network = " " }))
    }

    @Test
    fun groupsAndChainsStayAsTheyWere() {
        val group = ProfileItem.create(EConfigType.POLICYGROUP).apply { network = "ws" }
        assertEquals("POLICYGROUP", serverProtocolDescription(group))
    }

    @Test
    fun aVlessProfileKeepsItsFullLine() {
        val vless = ProfileItem.create(EConfigType.VLESS).apply { network = "xhttp"; security = "tls" }
        assertEquals("VLESS / xhttp / tls", serverProtocolDescription(vless))
    }
}
