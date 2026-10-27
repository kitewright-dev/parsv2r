package com.v2ray.ang.handler

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.helper.NotificationHelper
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.util.LogUtil
import java.util.TimeZone

/**
 * Posts a notification when a subscription is about to run out: three days or less left, 90% of
 * the traffic used, expired, or used up. Low and expiring remind at most once a day; expired and
 * used up remind once. The decision itself is [SubscriptionPanel.shouldRemind].
 */
object SubscriptionReminder {

    /** Checks one subscription and posts a reminder if one is due. Safe to call often. */
    fun check(context: Context, subId: String, nowMillis: Long = System.currentTimeMillis()) {
        if (!MmkvManager.decodeSettingsBool(AppConfig.PREF_SUB_REMINDER_ENABLED, true)) return
        val sub = MmkvManager.decodeSubscription(subId) ?: return
        if (!sub.enabled) return

        val state = SubscriptionPanel.usageState(sub.panelInfo, nowMillis)
        val today = localDay(nowMillis)

        if (state == SubscriptionUsageState.NONE || state == SubscriptionUsageState.OK) {
            // Back to normal (renewed or topped up): forget the last reminder so the next one fires.
            if (sub.reminderState != null) {
                sub.reminderState = null
                sub.reminderDay = -1
                MmkvManager.encodeSubscription(subId, sub)
            }
            return
        }
        if (!SubscriptionPanel.shouldRemind(state, sub.reminderState, sub.reminderDay, today)) return

        val text = message(context, sub, state, nowMillis) ?: return
        val open = PendingIntent.getActivity(
            context,
            subId.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        NotificationHelper.notifyReminder(
            context = context,
            tag = "sub_$subId",
            title = context.getString(R.string.sub_reminder_title),
            content = text,
            contentIntent = open,
        )
        sub.reminderState = state.name
        sub.reminderDay = today
        MmkvManager.encodeSubscription(subId, sub)
        LogUtil.i(AppConfig.TAG, "SubscriptionReminder: posted $state for ${sub.remarks}")
    }

    private fun message(context: Context, sub: SubscriptionItem, state: SubscriptionUsageState, now: Long): String? {
        val info = sub.panelInfo ?: return null
        val name = sub.remarks
        return when (state) {
            SubscriptionUsageState.EXPIRED -> context.getString(R.string.sub_reminder_expired, name)
            SubscriptionUsageState.EXHAUSTED -> context.getString(R.string.sub_reminder_exhausted, name)
            SubscriptionUsageState.EXPIRING -> {
                val days = SubscriptionPanel.daysLeft(info, now) ?: return null
                context.resources.getQuantityString(R.plurals.sub_reminder_expiring, days, name, days)
            }

            SubscriptionUsageState.LOW -> {
                val total = info.total ?: return null
                val left = (total - (info.usedBytes ?: 0L)).coerceAtLeast(0L)
                context.getString(
                    R.string.sub_reminder_low,
                    name,
                    SubscriptionPanel.formatBytes(left),
                    SubscriptionPanel.formatBytes(total),
                )
            }

            SubscriptionUsageState.NONE, SubscriptionUsageState.OK -> null
        }
    }

    private fun localDay(nowMillis: Long): Long =
        (nowMillis + TimeZone.getDefault().getOffset(nowMillis)) / SubscriptionPanel.DAY_MILLIS
}
