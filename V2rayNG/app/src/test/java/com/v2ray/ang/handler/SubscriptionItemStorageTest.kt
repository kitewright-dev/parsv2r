package com.v2ray.ang.handler

import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.util.JsonUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Subscriptions saved by older versions must load with the new fields at their defaults. */
class SubscriptionItemStorageTest {

    @Test
    fun aSubscriptionSavedBeforePanelInfoLoadsWithDefaults() {
        val old = """{"remarks":"Nodex","url":"https://p.example/sub/x","enabled":true,"addedTime":1,
            "lastUpdated":5,"autoUpdate":false,"updateInterval":1440,"allowInsecureUrl":false}"""
        val item = JsonUtil.fromJsonSafe(old, SubscriptionItem::class.java)
        assertNotNull(item)
        item!!
        assertEquals("Nodex", item.remarks)
        assertNull(item.panelInfo)
        assertTrue(item.followPanelInterval, "panels are followed unless the user opts out")
        assertFalse(item.autoTitle)
        assertNull(item.reminderState)
        assertEquals(-1L, item.reminderDay)
        assertNull(SubscriptionUpdater.intervalMinutes(item), "no panel interval and auto-update off")
    }

    @Test
    fun panelInfoSurvivesARoundTrip() {
        val item = SubscriptionItem(remarks = "a", url = "https://p.example/sub/x")
        item.panelInfo = SubscriptionPanelInfo(
            upload = 1, download = 2, total = 10, expire = 1_900_000_000,
            title = "نودکس", updateIntervalHours = 1, announce = "سلام", fetchedAt = 7,
        )
        val back = JsonUtil.fromJsonSafe(JsonUtil.toJson(item), SubscriptionItem::class.java)!!
        assertEquals(item.panelInfo, back.panelInfo)
        assertEquals(60L, SubscriptionUpdater.intervalMinutes(back))

        back.followPanelInterval = false
        assertNull(SubscriptionUpdater.intervalMinutes(back), "opted out and own auto-update off")
        back.autoUpdate = true
        assertEquals(1440L, SubscriptionUpdater.intervalMinutes(back))
    }
}
