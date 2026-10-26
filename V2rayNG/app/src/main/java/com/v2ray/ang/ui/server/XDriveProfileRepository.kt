package com.v2ray.ang.ui.server

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.CustomFmt
import com.v2ray.ang.fmt.XDriveFmt
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager

/** Saves XDRIVE profiles as JSON (CUSTOM) profiles, the same way the JSON editor does. */
object XDriveProfileRepository : XDriveProfileStore {

    override fun loadRaw(guid: String): String? = MmkvManager.decodeServerRaw(guid)

    override fun defaultSocksPort(): Int = SettingsManager.getSocksPort()

    override fun delete(guid: String): Boolean {
        if (guid.isEmpty() || guid == MmkvManager.getSelectServer()) return false
        MmkvManager.removeServer(guid)
        return true
    }

    override fun save(guid: String, subscriptionId: String, draft: XDriveFmt.Draft, socksPort: Int?): String {
        val content = XDriveFmt.build(draft, socksPort ?: defaultSocksPort())
        val parsed = CustomFmt.parse(content)
        val config = MmkvManager.decodeServerConfig(guid)
            ?: ProfileItem.create(EConfigType.CUSTOM).apply { this.subscriptionId = subscriptionId }
        config.remarks = draft.remarks.trim()
        config.server = parsed.server
        config.serverPort = parsed.serverPort
        config.network = parsed.network
        config.description = AngConfigManager.generateDescription(config)
        val savedGuid = MmkvManager.encodeServerConfig(guid, config)
        MmkvManager.encodeServerRaw(savedGuid, content)
        return savedGuid
    }
}
