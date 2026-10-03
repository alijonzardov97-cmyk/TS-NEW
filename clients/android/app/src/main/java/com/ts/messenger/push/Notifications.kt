package com.ts.messenger.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ts.messenger.MainActivity
import com.ts.messenger.R
import com.ts.messenger.net.PushPayload

object Notifications {
    private const val CHANNEL_ID = "messages_v2"

    /** True while the app is on screen; the background service stays quiet then. */
    @Volatile var appVisible = false

    private val claimed = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) = size > 200
    }

    /** True the first time a key is seen: the app and the background service must not both notify. */
    fun claim(key: String): Boolean = synchronized(claimed) {
        if (claimed.containsKey(key)) false else { claimed[key] = true; true }
    }

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        // The first channel ("messages") was created without an explicit sound and Android never
        // lets an app change a channel afterwards, so a new id carries the sound and vibration.
        nm.deleteNotificationChannel("messages")
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                setShowBadge(true)
                enableVibration(true)
                setSound(
                    android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION),
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            },
        )
    }

    /**
     * Shows a notification for a push. The payload only carries metadata. On the lock screen the
     * sender is hidden (VISIBILITY_PRIVATE with a generic public version).
     */
    fun showNewMessage(context: Context, payload: PushPayload?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)
        val sender = payload?.senderName?.let(::clean)?.takeIf { it.isNotEmpty() }
        val open = PendingIntent.getActivity(
            context, (payload?.channelId ?: "").hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_CHAT_CHANNEL, payload?.channelId ?: ""),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_new_message))
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(sender ?: context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_new_message))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        val id = (payload?.channelId ?: "").hashCode()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    const val EXTRA_CHAT_CHANNEL = "ts_chat_channel"
    const val EXTRA_CALL_CHANNEL = "ts_call_channel"
    private const val CALL_CHANNEL_ID = "calls"
    private const val CALL_NOTIFICATION_ID = 7001

    /** Rings: shown for a call that arrives through the socket or through a push. */
    fun showIncomingCall(context: Context, name: String?, channelId: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CALL_CHANNEL_ID, context.getString(R.string.notif_call_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                enableVibration(true)
            },
        )
        val who = name?.let(::clean)?.takeIf { it.isNotEmpty() }
        val open = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_CALL_CHANNEL, channelId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val publicVersion = NotificationCompat.Builder(context, CALL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_incoming_call))
            .build()
        val n = NotificationCompat.Builder(context, CALL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(who ?: context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_incoming_call))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(60_000)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(CALL_NOTIFICATION_ID, n) }
    }

    fun cancelCall(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(CALL_NOTIFICATION_ID) }
    }

    /** Server-provided text is shown as plain text only: strip control characters, cap length. */
    private fun clean(s: String) = s.filter { !it.isISOControl() }.take(64).trim()
}
