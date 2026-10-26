package com.v2ray.ang.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Brings the XDRIVE outbounds of a JSON config in line with what Xray 26.10.17+ (#7182)
 * accepts, so a config the core would refuse as a whole still starts.
 *
 * The core only takes XDRIVE under VLESS with Mux.Cool carrying every connection, and with
 * no "flow" or "testpre". Panels miss this: 3X-UI's JSON subscription leaves "mux" out
 * unless its operator set subJsonMux, which is empty by default, and the core then stops
 * on load with "the xdrive transport needs "mux" enabled". Mux and flow are client-side
 * choices, so they can be fixed here. VLESS "encryption" cannot: it is the server's key.
 */
object XDriveRepair {

    data class Report(
        /** XDRIVE outbounds found. */
        val outbounds: Int = 0,
        /** Outbounds whose mux was missing, off or out of range and is now on. */
        val muxFixed: Int = 0,
        /** VLESS users whose "flow" was removed. */
        val flowRemoved: Int = 0,
        /** "testpre" values removed. */
        val testpreRemoved: Int = 0,
        /** Outbounds without VLESS encryption, which no client-side change can fix. */
        val missingEncryption: Int = 0,
    ) {
        val changed: Boolean get() = muxFixed + flowRemoved + testpreRemoved > 0
    }

    private const val XDRIVE = "xdrive"

    /** Repairs [root] in place and reports what it found and changed. */
    fun repair(root: JsonObject): Report {
        val outbounds = root.array("outbounds") ?: return Report()
        var report = Report()
        for (element in outbounds) {
            val outbound = element.asObjectOrNull() ?: continue
            if (!isXDrive(outbound)) continue
            report = report.copy(outbounds = report.outbounds + 1)
            if (!outbound.string("protocol").equals("vless", ignoreCase = true)) continue

            val settings = outbound.obj("settings")
            val users = vlessUsers(settings)
            // VLESS reverse opens a mux of its own, the core leaves those outbounds alone too
            val isReverse = users.any { it.has("reverse") } || settings?.has("reverse") == true

            var flows = 0
            var testpres = 0
            var encrypted = users.isNotEmpty()
            for (user in users) {
                if (user.remove("flow") != null) flows++
                if (user.remove("testpre") != null) testpres++
                val encryption = user.string("encryption")
                if (encryption.isNullOrBlank() || encryption.equals("none", ignoreCase = true)) encrypted = false
            }

            val muxFixed = !isReverse && ensureMux(outbound)
            report = report.copy(
                muxFixed = report.muxFixed + if (muxFixed) 1 else 0,
                flowRemoved = report.flowRemoved + flows,
                testpreRemoved = report.testpreRemoved + testpres,
                missingEncryption = report.missingEncryption + if (encrypted) 0 else 1,
            )
        }
        return report
    }

    /** True when [outbound] travels over XDRIVE, whichever key the config names it with. */
    fun isXDrive(outbound: JsonObject): Boolean {
        val stream = outbound.obj("streamSettings") ?: return false
        val transport = stream.string("method") ?: stream.string("network")
        return transport.equals(XDRIVE, ignoreCase = true)
    }

    /**
     * The VLESS accounts of an outbound in either shape the core reads: the classic
     * settings.vnext[].users[], or the flat settings that carry "id" themselves.
     */
    private fun vlessUsers(settings: JsonObject?): List<JsonObject> {
        settings ?: return emptyList()
        val vnext = settings.array("vnext")
        if (vnext != null) {
            return vnext.mapNotNull { it.asObjectOrNull() }
                .flatMap { server -> server.array("users")?.mapNotNull { it.asObjectOrNull() }.orEmpty() }
        }
        return if (settings.has("id")) listOf(settings) else emptyList()
    }

    /** Leaves a mux the core accepts on [outbound]; returns whether anything had to change. */
    private fun ensureMux(outbound: JsonObject): Boolean {
        val mux = outbound.obj("mux")
        if (mux == null) {
            outbound.add("mux", JsonObject().apply { addProperty("enabled", true) })
            return true
        }
        var changed = false
        if (!mux.isTrue("enabled")) {
            mux.addProperty("enabled", true)
            changed = true
        }
        // 0 is the core's default (8 and 16), only a negative value disables that lane
        for (key in arrayOf("concurrency", "xudpConcurrency")) {
            val value = mux.int(key)
            if (value != null && value < 0) {
                mux.addProperty(key, 0)
                changed = true
            }
        }
        if (mux.string("xudpProxyUDP443").equals("skip", ignoreCase = true)) {
            mux.addProperty("xudpProxyUDP443", "reject")
            changed = true
        }
        return changed
    }

    private fun JsonElement.asObjectOrNull(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.asObjectOrNull()

    private fun JsonObject.array(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    // only a JSON true counts, the core's parser refuses "true" written as a string
    private fun JsonObject.isTrue(key: String): Boolean =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true

    private fun JsonObject.int(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
}
