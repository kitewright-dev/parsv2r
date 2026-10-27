package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class SubscriptionPanelTest {

    private val now = 1_800_000_000_000L // ms
    private val day = SubscriptionPanel.DAY_MILLIS

    // ---------------- parsing ----------------

    @Test
    fun readsTheHeadersAMarzbanStylePanelSends() {
        val info = SubscriptionPanel.parse(
            headers = mapOf(
                "Subscription-Userinfo" to "upload=1073741824; download=2147483648; total=10737418240; expire=1800864000",
                "Profile-Update-Interval" to "1",
                "Profile-Title" to "base64:2YbZiNiv2qnYsw==",
                "Support-Url" to "https://t.me/parsv2r",
                "Profile-Web-Page-Url" to "https://example.com/sub/abc",
                "Content-Type" to "text/plain",
            ),
            bodies = listOf("dmxlc3M6Ly8="),
            now = now,
        )
        assertNotNull(info)
        info!!
        assertEquals(1073741824L, info.upload)
        assertEquals(2147483648L, info.download)
        assertEquals(3221225472L, info.usedBytes)
        assertEquals(10737418240L, info.total)
        assertEquals(1800864000L, info.expire)
        assertEquals(1, info.updateIntervalHours)
        assertEquals("نودکس", info.title)
        assertEquals("https://t.me/parsv2r", info.supportUrl)
        assertEquals("https://example.com/sub/abc", info.webPageUrl)
        assertEquals(now, info.fetchedAt)
        assertTrue(info.hasUsage)
    }

    @Test
    fun returnsNullWhenThePanelSendsNothingKnown() {
        assertNull(SubscriptionPanel.parse(mapOf("Content-Type" to "text/plain"), listOf("vless://x", null), now))
        assertNull(SubscriptionPanel.parse(emptyMap(), emptyList(), now))
        // Known names but every value unusable.
        assertNull(
            SubscriptionPanel.parse(
                mapOf("profile-update-interval" to "0", "support-url" to "javascript:alert(1)"),
                emptyList(),
                now,
            )
        )
    }

    @Test
    fun readsHashLinesFromTheBodyAndHeadersWin() {
        val body = """
            #profile-title: Body Name
            #announce: base64:2LPYsdmI24zYsyDYotm+2K/bjNiqINi02K8=
            #profile-update-interval:6
            #not-a-known-key: x
            vless://abc@1.2.3.4:443?security=tls#node
        """.trimIndent()
        val info = SubscriptionPanel.parse(mapOf("profile-title" to "Header Name"), listOf(body), now)!!
        assertEquals("Header Name", info.title)
        assertEquals("سرویس آپدیت شد", info.announce)
        assertEquals(6, info.updateIntervalHours)
    }

    @Test
    fun scansTheDecodedBodyToo() {
        val info = SubscriptionPanel.parse(emptyMap(), listOf("c29tZWJhc2U2NA==", "#profile-title: Decoded\nvless://x"), now)
        assertEquals("Decoded", info?.title)
    }

    @Test
    fun userInfoToleratesSpacingCaseMissingPartsAndJunk() {
        assertEquals(
            mapOf("upload" to 5L, "download" to 7L, "total" to 0L),
            SubscriptionPanel.parseUserInfo(" Upload = 5 ;download=7;total=0; expire= ; bad ; x=-3; y=abc"),
        )
        // Some panels send floats.
        assertEquals(mapOf("download" to 12L), SubscriptionPanel.parseUserInfo("download=12.0"))
    }

    @Test
    fun zeroTotalMeansUnlimitedAndZeroExpireMeansNoExpiry() {
        val info = SubscriptionPanel.parse(
            mapOf("subscription-userinfo" to "upload=0; download=100; total=0; expire=0"),
            emptyList(),
            now,
        )!!
        assertNull(info.total)
        assertNull(info.expire)
        assertEquals(100L, info.usedBytes)
        assertNull(SubscriptionPanel.usedFraction(info))
        assertNull(SubscriptionPanel.daysLeft(info, now))
        assertEquals(SubscriptionUsageState.OK, SubscriptionPanel.usageState(info, now))
    }

    @Test
    fun expireInMillisecondsIsConvertedToSeconds() {
        val info = SubscriptionPanel.parse(
            mapOf("subscription-userinfo" to "expire=1800864000000"),
            emptyList(),
            now,
        )!!
        assertEquals(1800864000L, info.expire)
    }

    @Test
    fun intervalIsWholeHoursInRange() {
        assertEquals(1, SubscriptionPanel.parseIntervalHours(" 1 "))
        assertEquals(720, SubscriptionPanel.parseIntervalHours("720"))
        assertNull(SubscriptionPanel.parseIntervalHours("0"))
        assertNull(SubscriptionPanel.parseIntervalHours("-2"))
        assertNull(SubscriptionPanel.parseIntervalHours("721"))
        assertNull(SubscriptionPanel.parseIntervalHours("1.5"))
        assertNull(SubscriptionPanel.parseIntervalHours("soon"))
    }

    @Test
    fun valuesArePlainOrBase64() {
        assertEquals("Happ the best", SubscriptionPanel.decodeValue("base64:SGFwcCB0aGUgYmVzdA=="))
        assertEquals("Happ the best", SubscriptionPanel.decodeValue("BASE64:SGFwcCB0aGUgYmVzdA"))
        assertEquals("plain text", SubscriptionPanel.decodeValue("  plain text "))
        assertNull(SubscriptionPanel.decodeValue("base64:!!!not base64"))
        // A base64-looking value without the prefix stays as it is.
        assertEquals("SGFwcA==", SubscriptionPanel.decodeValue("SGFwcA=="))
    }

    @Test
    fun textIsCleanedAndCut() {
        val long = "x".repeat(40)
        val info = SubscriptionPanel.parse(
            mapOf("profile-title" to long, "announce" to "line1\r\nline2\u0007"),
            emptyList(),
            now,
        )!!
        assertEquals("x".repeat(SubscriptionPanel.MAX_TITLE) + "…", info.title)
        assertEquals("line1\nline2", info.announce)
    }

    @Test
    fun onlySafeLinksAreKept() {
        assertEquals("https://t.me/x", SubscriptionPanel.safeUrl("https://t.me/x"))
        assertEquals("http://a.b/c", SubscriptionPanel.safeUrl(" http://a.b/c "))
        assertEquals("tg://resolve?domain=parsv2r", SubscriptionPanel.safeUrl("tg://resolve?domain=parsv2r"))
        assertNull(SubscriptionPanel.safeUrl("javascript:alert(1)"))
        assertNull(SubscriptionPanel.safeUrl("intent://x#Intent;end"))
        assertNull(SubscriptionPanel.safeUrl("https://"))
        assertNull(SubscriptionPanel.safeUrl("https://a b"))
        assertNull(SubscriptionPanel.safeUrl(""))
    }

    // ---------------- state ----------------

    private fun info(used: Long? = null, total: Long? = null, expireMillis: Long? = null) =
        SubscriptionPanelInfo(
            download = used,
            total = total,
            expire = expireMillis?.div(1000L),
        )

    @Test
    fun usageStatesInPriorityOrder() {
        assertEquals(SubscriptionUsageState.NONE, SubscriptionPanel.usageState(null, now))
        assertEquals(SubscriptionUsageState.NONE, SubscriptionPanel.usageState(SubscriptionPanelInfo(title = "t"), now))
        assertEquals(SubscriptionUsageState.OK, SubscriptionPanel.usageState(info(used = 10, total = 100), now))
        assertEquals(SubscriptionUsageState.OK, SubscriptionPanel.usageState(info(used = 89, total = 100), now), "below 90%")
        assertEquals(SubscriptionUsageState.LOW, SubscriptionPanel.usageState(info(used = 90, total = 100), now))
        assertEquals(SubscriptionUsageState.EXHAUSTED, SubscriptionPanel.usageState(info(used = 100, total = 100), now))
        assertEquals(
            SubscriptionUsageState.EXPIRING,
            SubscriptionPanel.usageState(info(used = 95, total = 100, expireMillis = now + 2 * day), now),
        )
        assertEquals(
            SubscriptionUsageState.OK,
            SubscriptionPanel.usageState(info(expireMillis = now + 4 * day), now),
        )
        // Expired outranks used up.
        assertEquals(
            SubscriptionUsageState.EXPIRED,
            SubscriptionPanel.usageState(info(used = 200, total = 100, expireMillis = now - 1000), now),
        )
        assertEquals(SubscriptionUsageState.EXPIRED, SubscriptionPanel.usageState(info(expireMillis = now), now))
    }

    @Test
    fun daysLeftCountsAPartDayAsADay() {
        assertEquals(1, SubscriptionPanel.daysLeft(info(expireMillis = now + 1000), now))
        assertEquals(1, SubscriptionPanel.daysLeft(info(expireMillis = now + day), now))
        assertEquals(2, SubscriptionPanel.daysLeft(info(expireMillis = now + day + 1000), now))
        assertEquals(0, SubscriptionPanel.daysLeft(info(expireMillis = now - day), now))
        assertNull(SubscriptionPanel.daysLeft(info(used = 1), now))
    }

    @Test
    fun usedFractionIsClamped() {
        assertEquals(0.25f, SubscriptionPanel.usedFraction(info(used = 25, total = 100)))
        assertEquals(1f, SubscriptionPanel.usedFraction(info(used = 300, total = 100)))
        assertNull(SubscriptionPanel.usedFraction(info(total = 100)))
    }

    @Test
    fun remindersFireOncePerDayOrOncePerState() {
        val low = SubscriptionUsageState.LOW
        assertTrue(SubscriptionPanel.shouldRemind(low, null, -1, 100))
        assertFalse(SubscriptionPanel.shouldRemind(low, "LOW", 100, 100), "already reminded today")
        assertTrue(SubscriptionPanel.shouldRemind(low, "LOW", 99, 100), "next day")
        assertTrue(SubscriptionPanel.shouldRemind(SubscriptionUsageState.EXPIRING, "LOW", 100, 100), "state changed")

        val expired = SubscriptionUsageState.EXPIRED
        assertTrue(SubscriptionPanel.shouldRemind(expired, "EXPIRING", 100, 100))
        assertFalse(SubscriptionPanel.shouldRemind(expired, "EXPIRED", 1, 100), "only once")
        assertFalse(SubscriptionPanel.shouldRemind(SubscriptionUsageState.EXHAUSTED, "EXHAUSTED", 1, 100))

        assertFalse(SubscriptionPanel.shouldRemind(SubscriptionUsageState.OK, null, -1, 100))
        assertFalse(SubscriptionPanel.shouldRemind(SubscriptionUsageState.NONE, null, -1, 100))
    }

    @Test
    fun scheduleFollowsThePanelThenTheUser() {
        fun s(auto: Boolean, user: Long, follow: Boolean, panel: Int?) =
            SubscriptionPanel.scheduleMinutes(auto, user, follow, panel, minMinutes = 15)

        assertEquals(60L, s(auto = false, user = 1440, follow = true, panel = 1))
        assertEquals(60L, s(auto = true, user = 1440, follow = true, panel = 1), "panel wins while followed")
        assertEquals(1440L, s(auto = true, user = 1440, follow = false, panel = 1), "user opted out of the panel")
        assertEquals(1440L, s(auto = true, user = 1440, follow = true, panel = null))
        assertEquals(15L, s(auto = true, user = 5, follow = true, panel = null), "floor")
        assertNull(s(auto = false, user = 1440, follow = true, panel = null))
        assertNull(s(auto = false, user = 1440, follow = false, panel = 3))
    }

    @Test
    fun openRefreshSkipsDisabledEmptyAndFresh() {
        val min = 5 * 60_000L
        assertTrue(SubscriptionPanel.dueOnOpen(true, "https://a", -1, now, min), "never updated")
        assertTrue(SubscriptionPanel.dueOnOpen(true, "https://a", now - min, now, min))
        assertFalse(SubscriptionPanel.dueOnOpen(true, "https://a", now - 1000, now, min), "just updated")
        assertTrue(SubscriptionPanel.dueOnOpen(true, "https://a", now + day, now, min), "clock moved back")
        assertFalse(SubscriptionPanel.dueOnOpen(false, "https://a", -1, now, min))
        assertFalse(SubscriptionPanel.dueOnOpen(true, " ", -1, now, min))
    }

    @Test
    fun openRefreshFetchesNewOnesAlwaysAndWaitsBetweenTries() {
        val retry = 120_000L
        fun f(last: Long, on: Boolean, tried: Long?) = SubscriptionPanel.fetchOnOpen(last, on, tried, now, retry)

        assertTrue(f(last = -1, on = false, tried = null), "new subscription even with the setting off")
        assertTrue(f(last = now - day, on = true, tried = null))
        assertFalse(f(last = now - day, on = false, tried = null), "setting off")
        assertFalse(f(last = -1, on = true, tried = now - 1000), "tried a moment ago")
        assertTrue(f(last = -1, on = true, tried = now - retry), "retry window passed")
        assertTrue(f(last = now - day, on = true, tried = now + day), "clock moved back")
    }

    // ---------------- names and links ----------------

    @Test
    fun splitsHappNameSuffixes() {
        assertEquals("Germany" to "Happ the best", SubscriptionPanel.splitName("Germany?serverDescription=SGFwcCB0aGUgYmVzdA=="))
        assertEquals("MyVPN" to null, SubscriptionPanel.splitName("MyVPN?fragment=80-250,10-100,tlshello"))
        assertEquals("MyVPN" to null, SubscriptionPanel.splitName("MyVPN?resolve-address=visa.com&host=my.com"))
        // Ordinary names with a question mark stay whole.
        assertEquals("Fast?" to null, SubscriptionPanel.splitName("Fast?"))
        assertEquals("Why? Because" to null, SubscriptionPanel.splitName("Why? Because"))
        assertEquals("a?b=c" to null, SubscriptionPanel.splitName("a?b=c"))
        assertEquals("?fragment=1" to null, SubscriptionPanel.splitName("?fragment=1"))
        assertEquals("plain" to null, SubscriptionPanel.splitName("plain"))
        // A description that is not base64 is kept as text.
        assertEquals("DE" to "Fast line", SubscriptionPanel.splitName("DE?serverDescription=Fast line"))
    }

    @Test
    fun happAddLinksGiveTheSubscriptionUrl() {
        assertEquals("https://p.example/sub/abc", SubscriptionPanel.happAddTarget("happ://add/https://p.example/sub/abc"))
        assertEquals("https://p.example/sub/abc", SubscriptionPanel.happAddTarget("HAPP://add/https%3A%2F%2Fp.example%2Fsub%2Fabc"))
        assertEquals("http://1.2.3.4:2096/sub/x#Name", SubscriptionPanel.happAddTarget("happ://add/http://1.2.3.4:2096/sub/x#Name"))
        assertNull(SubscriptionPanel.happAddTarget("happ://crypt3/AAAA"))
        assertNull(SubscriptionPanel.happAddTarget("happ://add/vless://x@y:1"))
        assertNull(SubscriptionPanel.happAddTarget("happ://add/"))
        assertNull(SubscriptionPanel.happAddTarget("happ://add/https://"))
        assertNull(SubscriptionPanel.happAddTarget("v2rayng://install-sub?url=x"))
        assertNull(SubscriptionPanel.happAddTarget(null))
    }

    // ---------------- formatting ----------------

    @Test
    fun bytesUseBinaryUnits() {
        val en = Locale.US
        assertEquals("0 B", SubscriptionPanel.formatBytes(0, en))
        assertEquals("1023 B", SubscriptionPanel.formatBytes(1023, en))
        assertEquals("1.50 KB", SubscriptionPanel.formatBytes(1536, en))
        assertEquals("10.0 GB", SubscriptionPanel.formatBytes(10L * 1024 * 1024 * 1024, en))
        assertEquals("200 GB", SubscriptionPanel.formatBytes(200L * 1024 * 1024 * 1024, en))
        assertEquals("0 B", SubscriptionPanel.formatBytes(-5, en))
    }

    @Test
    fun base64DecoderHandlesBothAlphabetsAndRejectsJunk() {
        assertEquals("Hapx", Base64Text.decode("SGFwcA=="))
        assertEquals("Happ", Base64Text.decode("SGFwcA"))
        assertEquals("ü?", Base64Text.decode("w7w_"))
        assertEquals("ü?", Base64Text.decode("w7w/"))
        assertNull(Base64Text.decode("A"))
        assertNull(Base64Text.decode(""))
        assertNull(Base64Text.decode("ab$="))
        assertNull(Base64Text.decode("/w=="), "not UTF-8")
    }
}
