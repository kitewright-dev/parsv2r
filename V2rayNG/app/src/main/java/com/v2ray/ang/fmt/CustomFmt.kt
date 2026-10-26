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
        val nested = if (outbound?.getServerAddress() == null) nestedEndpointOf(str) else null
        config.server = outbound?.getServerAddress() ?: nested?.first
        config.serverPort = (outbound?.getServerPort() ?: nested?.second)?.toString()
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

    /**
     * The address and port of the first proxy outbound when they sit in settings.vnext[0] or
     * settings.servers[0], the classic shapes 3X-UI's JSON subscription and the XDRIVE form
     * write. The bean above reads only the flat settings.address, so those cards had no host.
     */
    internal fun nestedEndpointOf(str: String): Pair<String, Int?>? {
        val root = runCatching { JsonParser.parseString(str) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val outbounds = root.get("outbounds")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val proxy = outbounds.asSequence()
            .mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            .firstOrNull { ob -> ob.string("protocol")?.lowercase()?.let { it !in NOT_PROXY } == true }
            ?: return null
        val settings = proxy.get("settings")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val server = sequenceOf("vnext", "servers")
            .mapNotNull { key -> settings.get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull() }
            .mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            .firstOrNull() ?: return null
        val address = server.string("address")?.takeIf { it.isNotBlank() } ?: return null
        val port = server.get("port")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
        return address to port
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}
