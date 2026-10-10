package com.hamurcuabi.usagelimit.cloud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.hamurcuabi.usagelimit.MainActivity
import com.hamurcuabi.usagelimit.R

/** Ebeveynin telefonuna gelen olay bildirimlerini gösterir. */
class PushService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        val parentUid = ParentCloud.uid ?: return
        ParentCloud.saveToken(parentUid, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Uygulama arka plandayken bildirimi sistem gösterir; buraya yalnızca açıkken düşer.
        val title = message.notification?.title ?: message.data["title"] ?: return
        val body = message.notification?.body ?: message.data["body"] ?: ""
        ensureChannel(this)

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        /** Cloud Function bildirimi bu kanala gönderir; adı functions/index.js ile aynı olmalı. */
        const val CHANNEL = "child_events"

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Çocuk olayları", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }
}
