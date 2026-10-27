package com.v2ray.ang.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.handler.SubscriptionPanel
import com.v2ray.ang.handler.SubscriptionPanelInfo
import com.v2ray.ang.handler.SubscriptionUsageState
import java.util.Locale

/**
 * What a subscription panel reported: traffic used against the plan, time left, the panel's
 * announcement, and its support and website links.
 *
 * @param compact the one-block version for the top of the main screen
 * @param onOpenLink opens a support or website link
 */
@Composable
fun SubscriptionPanelBlock(
    info: SubscriptionPanelInfo,
    nowMillis: Long,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val state = SubscriptionPanel.usageState(info, nowMillis)
    val alert = state == SubscriptionUsageState.EXPIRED ||
            state == SubscriptionUsageState.EXHAUSTED
    val warn = alert || state == SubscriptionUsageState.LOW || state == SubscriptionUsageState.EXPIRING
    val accent = when {
        alert -> scheme.error
        warn -> WarnAmber
        else -> scheme.secondary
    }
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

    Column(modifier = modifier) {
        if (info.hasUsage) {
            val usageText = usageLine(info, locale)
            val timeText = timeLine(info, state, nowMillis, locale)
            val fraction = SubscriptionPanel.usedFraction(info)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = usageText.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (timeText != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (warn) accent else scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            if (fraction != null) {
                Spacer(Modifier.height(6.dp))
                val usageDescription = stringResource(R.string.sub_panel_usage_description)
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (compact) 4.dp else 6.dp)
                        .clip(RoundedCornerShape(50))
                        .semantics {
                            contentDescription = usageDescription
                            stateDescription = "${(fraction * 100).toInt()}%"
                        },
                    color = accent,
                    trackColor = scheme.surfaceContainerHighest,
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
        }

        info.announce?.let { announce ->
            Spacer(Modifier.height(if (info.hasUsage) 8.dp else 0.dp))
            Text(
                text = announce,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = if (compact) 2 else 6,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (info.supportUrl != null || info.webPageUrl != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                info.supportUrl?.let { url ->
                    PanelLinkButton(
                        label = stringResource(R.string.sub_panel_support),
                        telegram = isTelegram(url),
                        onClick = { onOpenLink(url) },
                    )
                }
                info.webPageUrl?.let { url ->
                    PanelLinkButton(
                        label = stringResource(R.string.sub_panel_website),
                        telegram = isTelegram(url),
                        onClick = { onOpenLink(url) },
                    )
                }
            }
        }
    }
}

/** The main-screen strip: the same block on a rounded card under the group tabs. */
@Composable
fun SubscriptionPanelStrip(
    info: SubscriptionPanelInfo,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    val now = remember(info) { System.currentTimeMillis() }
    SubscriptionPanelBlock(
        info = info,
        nowMillis = now,
        onOpenLink = onOpenLink,
        compact = true,
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.surfaceContainerLow)
            .border(1.dp, scheme.outlineVariant.copy(alpha = 0.6f), shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

@Composable
private fun PanelLinkButton(label: String, telegram: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        if (telegram) {
            Icon(
                painter = painterResource(R.drawable.ic_telegram_24dp),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(text = label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun usageLine(info: SubscriptionPanelInfo, locale: Locale): String? {
    val total = info.total
    if (info.usedBytes == null && total == null) return null
    val used = SubscriptionPanel.formatBytes(info.usedBytes ?: 0L, locale)
    return if (total != null) {
        val left = SubscriptionPanel.formatBytes((total - (info.usedBytes ?: 0L)).coerceAtLeast(0L), locale)
        stringResource(R.string.sub_panel_used_of, used, SubscriptionPanel.formatBytes(total, locale)) +
                " · " + stringResource(R.string.sub_panel_left, left)
    } else {
        stringResource(R.string.sub_panel_used_unlimited, used)
    }
}

@Composable
private fun timeLine(
    info: SubscriptionPanelInfo,
    state: SubscriptionUsageState,
    now: Long,
    locale: Locale,
): String? = when (state) {
    SubscriptionUsageState.EXPIRED -> stringResource(R.string.sub_panel_expired)
    SubscriptionUsageState.EXHAUSTED -> stringResource(R.string.sub_panel_exhausted)
    else -> {
        val days = SubscriptionPanel.daysLeft(info, now)
        if (days == null) {
            if (info.total != null || info.usedBytes != null) stringResource(R.string.sub_panel_no_expiry) else null
        } else if (days > 30) {
            stringResource(R.string.sub_panel_ends_on, formatDate(info.expire!! * 1000L, locale))
        } else {
            pluralStringResource(R.plurals.sub_panel_days_left, days, days)
        }
    }
}

/** A short date; in Persian the Solar Hijri calendar, which is what people there read. */
private fun formatDate(millis: Long, locale: Locale): String {
    val uLocale = if (locale.language == "fa") {
        android.icu.util.ULocale("fa_IR@calendar=persian")
    } else {
        android.icu.util.ULocale.forLocale(locale)
    }
    return android.icu.text.DateFormat
        .getDateInstance(android.icu.text.DateFormat.MEDIUM, uLocale)
        .format(java.util.Date(millis))
}

private fun isTelegram(url: String): Boolean {
    val lower = url.lowercase(Locale.ROOT)
    return lower.startsWith("tg://") || lower.startsWith("https://t.me/") || lower.startsWith("http://t.me/") ||
            lower.startsWith("https://telegram.me/")
}

private val WarnAmber = Color(0xFFE0922A)
