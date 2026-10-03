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
    private const val CHANNEL_ID = "messages"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                setShowBadge(true)
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
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
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

    /** Server-provided text is shown as plain text only: strip control characters, cap length. */
    private fun clean(s: String) = s.filter { !it.isISOControl() }.take(64).trim()
}
