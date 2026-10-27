package com.v2ray.ang.dto.entities

import com.v2ray.ang.handler.SubscriptionPanelInfo

data class SubscriptionItem(
    var remarks: String = "",
    var url: String = "",
    var enabled: Boolean = true,
    val addedTime: Long = System.currentTimeMillis(),
    var lastUpdated: Long = -1,
    var autoUpdate: Boolean = false,
    var updateInterval: Long = 1440, // in minutes, default to 24 hours
    var prevProfile: String? = null,
    var nextProfile: String? = null,
    var filter: String? = null,
    var allowInsecureUrl: Boolean = false,
    var userAgent: String? = null,
    var requestHeaders: String? = null,
    var overrideAddress: String? = null,
    var overridePort: Int? = null,
    /** What the panel last reported about this subscription: traffic, expiry, name. */
    var panelInfo: SubscriptionPanelInfo? = null,
    /** Auto-update on the interval the panel asks for, when it asks for one. */
    var followPanelInterval: Boolean = true,
    /** The name was made up at import, so the panel's own title may replace it. */
    var autoTitle: Boolean = false,
)

