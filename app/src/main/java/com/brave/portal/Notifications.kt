package com.brave.portal

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging

/** Notification channels, display and Firebase topic subscription. */
object Notifications {
    const val EXTRA_URL = "url"                       // same key the server sends in the FCM data payload
    const val CH_GENERAL = "brave_general"
    const val CH_CLASSES = "brave_classes"
    const val CH_REMINDERS = "brave_reminders"

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_GENERAL, "Announcements & updates", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Important BRAVE announcements and portal updates" },
                NotificationChannel(CH_CLASSES, "New classes & resources", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "New classes, modules and learning resources" },
                NotificationChannel(CH_REMINDERS, "Live class reminders", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Upcoming live classes and task reminders" }
            )
        )
    }

    /** Subscribes this install to the members topic. Safe no-op if Firebase is not configured. */
    fun subscribe(ctx: Context) {
        try {
            if (FirebaseApp.getApps(ctx).isEmpty()) return
            FirebaseMessaging.getInstance().subscribeToTopic(BuildConfig.FCM_TOPIC)
        } catch (_: Exception) { /* never crash because of push */ }
    }

    fun channelFor(name: String?): String = when (name) {
        "classes" -> CH_CLASSES
        "reminders" -> CH_REMINDERS
        else -> CH_GENERAL
    }

    @SuppressLint("MissingPermission")
    fun show(ctx: Context, title: String, body: String, url: String?, channelId: String) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        createChannels(ctx)

        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            PortalUrls.safeInternalUrl(url)?.let { putExtra(EXTRA_URL, it) }   // only portal URLs
        }
        val id = (System.currentTimeMillis() and 0x7fffffff).toInt()
        val pending = PendingIntent.getActivity(
            ctx, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(R.drawable.ic_stat_brave)
            .setColor(ContextCompat.getColor(ctx, R.color.brave_gold))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try { nm.notify(id, notification) } catch (_: SecurityException) { }
    }
}
