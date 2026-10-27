package com.v2ray.ang.handler

import java.net.URLDecoder
import java.util.Locale

/**
 * What a subscription panel says about itself, taken from the response the panel sends with
 * the server list. Every field is optional: a panel sends some, all or none of them.
 *
 * [expire] is a Unix time in seconds, as panels send it. [fetchedAt] is when the values were read.
 */
data class SubscriptionPanelInfo(
    val upload: Long? = null,
    val download: Long? = null,
    val total: Long? = null,
    val expire: Long? = null,
    val title: String? = null,
    val updateIntervalHours: Int? = null,
    val announce: String? = null,
    val fetchedAt: Long = 0L,
) {
    /** Bytes used so far, or null when the panel did not report usage. */
    val usedBytes: Long?
        get() = if (upload == null && download == null) null else (upload ?: 0L) + (download ?: 0L)

    /** True when the panel reported traffic or an expiry date, so there is something to show. */
    val hasUsage: Boolean
        get() = usedBytes != null || (total ?: 0L) > 0L || (expire ?: 0L) > 0L

    val isEmpty: Boolean
        get() = !hasUsage && title == null && updateIntervalHours == null &&
                announce == null
}

/** How a subscription stands, in the order the app should warn about it. */
enum class SubscriptionUsageState {
    /** The panel reported nothing about traffic or expiry. */
    NONE,
    OK,
    /** 90% or more of the traffic is used. */
    LOW,
    /** Three days or less until it expires. */
    EXPIRING,
    EXHAUSTED,
    EXPIRED,
}

/**
 * Reads the subscription headers that panels such as Marzban, 3X-UI, Remnawave and Happ-aware
 * panels send, and makes the decisions built on them. Pure Kotlin with no Android types, so all
 * of it runs in a plain JVM test.
 *
 * The parameter names and formats follow Happ's published list: each one may come as an HTTP
 * header, or as a line `#name: value` in the body of the subscription.
 */
object SubscriptionPanel {

    const val HEADER_USERINFO = "subscription-userinfo"
    const val HEADER_TITLE = "profile-title"
    const val HEADER_UPDATE_INTERVAL = "profile-update-interval"
    const val HEADER_ANNOUNCE = "announce"

    private val KNOWN = setOf(
        HEADER_USERINFO,
        HEADER_TITLE,
        HEADER_UPDATE_INTERVAL,
        HEADER_ANNOUNCE,
    )

    const val MAX_TITLE = 25
    const val MAX_ANNOUNCE = 200

    /** Longest auto-update interval taken from a panel: 30 days. */
    const val MAX_INTERVAL_HOURS = 720

    const val DAY_MILLIS = 86_400_000L
    private const val EXPIRING_DAYS = 3
    private const val LOW_FRACTION = 0.9

    /** Panels that send a Unix time in milliseconds instead of seconds go above this. */
    private const val MILLIS_THRESHOLD = 100_000_000_000L

    /**
     * Builds the panel info from the response headers and body. Headers win over body lines.
     * Returns null when neither carries any of the known parameters.
     *
     * @param headers response headers; names are matched case-insensitively
     * @param bodies the response body, raw and (if it was base64) decoded; both are scanned
     * @param now the current time in milliseconds, stored as [SubscriptionPanelInfo.fetchedAt]
     */
    fun parse(headers: Map<String, String>, bodies: List<String?>, now: Long): SubscriptionPanelInfo? {
        val values = linkedMapOf<String, String>()
        bodies.forEach { body -> bodyParameters(body).forEach { (k, v) -> values.putIfAbsent(k, v) } }
        headers.forEach { (name, value) ->
            val key = name.trim().lowercase(Locale.ROOT)
            if (key in KNOWN && value.isNotBlank()) values[key] = value
        }
        if (values.isEmpty()) return null

        val usage = values[HEADER_USERINFO]?.let(::parseUserInfo).orEmpty()
        val info = SubscriptionPanelInfo(
            upload = usage["upload"],
            download = usage["download"],
            total = usage["total"]?.takeIf { it > 0L },
            expire = usage["expire"]?.let(::normalizeExpire)?.takeIf { it > 0L },
            title = values[HEADER_TITLE]?.let(::decodeValue)?.let { cleanText(it, MAX_TITLE) },
            updateIntervalHours = values[HEADER_UPDATE_INTERVAL]?.let(::parseIntervalHours),
            announce = values[HEADER_ANNOUNCE]?.let(::decodeValue)?.let { cleanText(it, MAX_ANNOUNCE) },
            fetchedAt = now,
        )
        return info.takeUnless { it.isEmpty }
    }

    /** Lines of the form `#name: value` (or `#name:value`) for the known parameter names. */
    internal fun bodyParameters(body: String?): Map<String, String> {
        if (body.isNullOrEmpty()) return emptyMap()
        val result = linkedMapOf<String, String>()
        body.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (!line.startsWith("#")) return@forEach
            val colon = line.indexOf(':')
            if (colon <= 1) return@forEach
            val key = line.substring(1, colon).trim().lowercase(Locale.ROOT)
            val value = line.substring(colon + 1).trim()
            if (key in KNOWN && value.isNotEmpty()) result.putIfAbsent(key, value)
        }
        return result
    }

    /** `upload=1; download=2; total=3; expire=4` into a map. Unparsable parts are skipped. */
    internal fun parseUserInfo(raw: String): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        raw.split(';', ',').forEach { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) return@forEach
            val key = part.substring(0, eq).trim().lowercase(Locale.ROOT)
            val number = part.substring(eq + 1).trim().toBigDecimalOrNull() ?: return@forEach
            if (number.signum() < 0) return@forEach
            val value = runCatching { number.toLong() }.getOrNull() ?: return@forEach
            result[key] = value
        }
        return result
    }

    private fun normalizeExpire(value: Long): Long =
        if (value >= MILLIS_THRESHOLD) value / 1000L else value

    /** Whole hours from 1 to [MAX_INTERVAL_HOURS]; anything else is ignored. */
    internal fun parseIntervalHours(raw: String): Int? =
        raw.trim().toIntOrNull()?.takeIf { it in 1..MAX_INTERVAL_HOURS }

    /** Plain text, or `base64:<value>` holding UTF-8 text. */
    internal fun decodeValue(raw: String): String? {
        val value = raw.trim()
        if (value.startsWith("base64:", ignoreCase = true)) {
            return Base64Text.decode(value.substring("base64:".length))
        }
        return value
    }

    private fun cleanText(text: String, max: Int): String? {
        val cleaned = text
            .replace("\r\n", "\n")
            .filter { it == '\n' || !it.isISOControl() }
            .trim()
        if (cleaned.isEmpty()) return null
        return if (cleaned.codePointCount(0, cleaned.length) <= max) {
            cleaned
        } else {
            cleaned.substring(0, cleaned.offsetByCodePoints(0, max)).trimEnd() + "…"
        }
    }

    // ---------------------------------------------------------------------
    // Decisions
    // ---------------------------------------------------------------------

    /** Where a subscription stands at [nowMillis]. */
    fun usageState(info: SubscriptionPanelInfo?, nowMillis: Long): SubscriptionUsageState {
        if (info == null || !info.hasUsage) return SubscriptionUsageState.NONE
        val expireMillis = info.expire?.takeIf { it > 0L }?.times(1000L)
        val total = info.total?.takeIf { it > 0L }
        val used = info.usedBytes ?: 0L
        return when {
            expireMillis != null && expireMillis <= nowMillis -> SubscriptionUsageState.EXPIRED
            total != null && used >= total -> SubscriptionUsageState.EXHAUSTED
            expireMillis != null && expireMillis - nowMillis <= EXPIRING_DAYS * DAY_MILLIS ->
                SubscriptionUsageState.EXPIRING
            total != null && used >= total * LOW_FRACTION -> SubscriptionUsageState.LOW
            else -> SubscriptionUsageState.OK
        }
    }

    /** Whole days left until expiry, counting a part day as a day; null when there is no expiry. */
    fun daysLeft(info: SubscriptionPanelInfo?, nowMillis: Long): Int? {
        val expireMillis = info?.expire?.takeIf { it > 0L }?.times(1000L) ?: return null
        val left = expireMillis - nowMillis
        if (left <= 0L) return 0
        return ((left + DAY_MILLIS - 1) / DAY_MILLIS).toInt()
    }

    /** Fraction of the traffic used, 0..1, or null when the plan is unlimited or unknown. */
    fun usedFraction(info: SubscriptionPanelInfo?): Float? {
        val total = info?.total?.takeIf { it > 0L } ?: return null
        val used = info.usedBytes ?: return null
        return (used.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * The auto-update interval in minutes, or null when the subscription should not auto-update.
     * When [followPanel] is on and the panel sent an interval, the panel's interval is used;
     * otherwise the user's own switch and interval decide.
     */
    fun scheduleMinutes(
        autoUpdate: Boolean,
        userIntervalMinutes: Long,
        followPanel: Boolean,
        panelIntervalHours: Int?,
        minMinutes: Long,
    ): Long? = when {
        followPanel && panelIntervalHours != null -> maxOf(panelIntervalHours * 60L, minMinutes)
        autoUpdate -> maxOf(userIntervalMinutes, minMinutes)
        else -> null
    }

    /**
     * Whether a subscription is due for the refresh done when the app opens.
     *
     * @param lastUpdated the last successful update in milliseconds, or a non-positive value
     * @param minAgeMillis how old the last update must be
     */
    fun dueOnOpen(enabled: Boolean, url: String, lastUpdated: Long, nowMillis: Long, minAgeMillis: Long): Boolean {
        if (!enabled || url.isBlank()) return false
        if (lastUpdated <= 0L) return true
        val age = nowMillis - lastUpdated
        return age < 0L || age >= minAgeMillis
    }

    /**
     * Whether the refresh on open should fetch a subscription that [dueOnOpen] found due.
     * One that was never fetched (just added, e.g. from a happ:// link) is fetched even with the
     * setting off; any one waits [retryMillis] after the last try so a dead link is not hammered.
     *
     * @param lastTry when the refresh on open last tried this subscription, or null
     */
    fun fetchOnOpen(lastUpdated: Long, settingOn: Boolean, lastTry: Long?, nowMillis: Long, retryMillis: Long): Boolean {
        val retryAllowed = lastTry == null || nowMillis - lastTry !in 0 until retryMillis
        return (lastUpdated <= 0L || settingOn) && retryAllowed
    }

    // ---------------------------------------------------------------------
    // Names and links
    // ---------------------------------------------------------------------

    private val NAME_SUFFIX_KEYS = setOf(
        "serverdescription",
        "fragment",
        "resolve-address",
        "host",
        "insecure",
    )

    /**
     * Happ appends options to a name after a `?`, as in `MyVPN?fragment=…` or
     * `Germany?serverDescription=<base64>`. Splits those off when every part after the last `?`
     * is `key=value` with a key Happ defines, so ordinary names that contain `?` stay as they are.
     *
     * @return the name without the suffix, and the decoded server description if one was given
     */
    fun splitName(name: String): Pair<String, String?> {
        val q = name.lastIndexOf('?')
        if (q <= 0) return name to null
        val suffix = name.substring(q + 1)
        val params = suffix.split('&').map { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) return name to null
            part.substring(0, eq).trim().lowercase(Locale.ROOT) to part.substring(eq + 1).trim()
        }
        if (params.isEmpty() || params.any { it.first !in NAME_SUFFIX_KEYS }) return name to null
        val base = name.substring(0, q).trim()
        if (base.isEmpty()) return name to null
        val description = params.firstOrNull { it.first == "serverdescription" }?.second
            ?.let { Base64Text.decode(it) ?: it }
            ?.let { cleanText(it, 30) }
        return base to description
    }

    /**
     * The subscription link inside a `happ://add/<link>` deep link, or null for anything else.
     * The link may be raw or percent-encoded; only http and https links are returned.
     */
    fun happAddTarget(deepLink: String?): String? {
        val text = deepLink?.trim() ?: return null
        val prefix = "happ://add/"
        if (!text.startsWith(prefix, ignoreCase = true)) return null
        var target = text.substring(prefix.length).trim()
        if (target.startsWith("http%3a", ignoreCase = true) || target.startsWith("https%3a", ignoreCase = true)) {
            target = runCatching { URLDecoder.decode(target, "UTF-8") }.getOrNull() ?: return null
        }
        val lower = target.lowercase(Locale.ROOT)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        if (target.length <= lower.indexOf("://") + 3 || target.any { it.isWhitespace() }) return null
        return target
    }

    // ---------------------------------------------------------------------
    // Formatting
    // ---------------------------------------------------------------------

    /** 1536 into "1.5 KB"; binary units, as panels count. */
    fun formatBytes(bytes: Long, locale: Locale = Locale.getDefault()): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
        var value = bytes.coerceAtLeast(0L).toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        val pattern = when {
            unit == 0 -> "%.0f %s"
            value >= 100.0 -> "%.0f %s"
            value >= 10.0 -> "%.1f %s"
            else -> "%.2f %s"
        }
        return String.format(locale, pattern, value, units[unit])
    }
}

/** A small base64 decoder (standard and URL-safe, padding optional) that needs no Android APIs. */
internal object Base64Text {
    private val table = IntArray(128) { -1 }.also { t ->
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".forEachIndexed { i, c -> t[c.code] = i }
        t['-'.code] = 62
        t['_'.code] = 63
    }

    /** UTF-8 text from base64, or null when the input is not valid base64. */
    fun decode(input: String): String? {
        val clean = input.trim().trimEnd('=').filterNot { it == '\n' || it == '\r' }
        if (clean.isEmpty() || clean.length % 4 == 1) return null
        val out = java.io.ByteArrayOutputStream(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = if (c.code < 128) table[c.code] else -1
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        val bytes = out.toByteArray()
        val decoder = Charsets.UTF_8.newDecoder()
        return runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrNull()
    }
}
