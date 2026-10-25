package com.v2ray.ang.fmt

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil

object CustomFmt : FmtBase() {
    /**
     * Parses a JSON string into a ProfileItem object.
     *
     * @param str the JSON string to parse
     * @return the parsed ProfileItem object, or null if parsing fails
     */
    fun parse(str: String): ProfileItem {
        val config = ProfileItem.create(EConfigType.CUSTOM)

        val fullConfig = JsonUtil.fromJson(str, V2rayConfig::class.java)
        val outbound = fullConfig?.getProxyOutbound()
            ?: fullConfig?.outbounds?.firstOrNull { it.protocol.equals(MASQUE, true) }

        config.remarks = fullConfig?.remarks ?: System.currentTimeMillis().toString()
        config.server = outbound?.getServerAddress()
        config.serverPort = outbound?.getServerPort()?.toString()
        config.network = transportOf(str)

        return config
    }

    private const val MASQUE = "masque"
    private val NOT_PROXY = setOf("freedom", "blackhole", "dns", "loopback")

    /**
     * The transport of the first proxy outbound, read from the raw json so the server list
     * can say what a JSON config really is. The bean above has no field for "method" (the
     * key newer configs use instead of "network") and none for the transports that only
     * travel as JSON, XDRIVE and MASQUE, so those cards used to read just "CUSTOM".
     * Returns null for plain tcp/raw, which the card already leaves out.
     */
    internal fun transportOf(str: String): String? {
        val root = runCatching { JsonParser.parseString(str) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val outbounds = root.get("outbounds")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val proxy = outbounds.asSequence()
            .mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            .firstOrNull { ob -> ob.string("protocol")?.lowercase()?.let { it !in NOT_PROXY } == true }
            ?: return null
        if (proxy.string("protocol").equals(MASQUE, true)) return MASQUE
        val stream = proxy.get("streamSettings")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val transport = (stream.string("method") ?: stream.string("network"))?.lowercase() ?: return null
        return when (transport) {
            "", "tcp", "raw" -> null
            "splithttp" -> "xhttp"
            "websocket" -> "ws"
            else -> transport
        }
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}
