package io.github.romanvht.byedpi.lab

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import io.github.romanvht.byedpi.R

/**
 * Постоянное уведомление о ходе AI-подбора.
 *
 * Оно не запускает foreground-сервис: работа идёт в [LabActivity], а уведомление
 * показывает состояние и позволяет остановить подбор, не возвращаясь в приложение.
 */
object LabNotifications {

    const val CHANNEL_ID = "ai_lab"
    const val NOTIFICATION_ID = 0x1AB
    const val ACTION_STOP = "io.github.romanvht.byedpi.lab.action.STOP"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.lab_notif_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.lab_notif_channel_desc)
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun update(
        context: Context,
        title: String,
        text: String,
        bigText: String,
        percent: Int?,
        indeterminate: Boolean,
        ongoing: Boolean
    ) {
        ensureChannel(context)
        val openIntent = Intent(context, LabActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            context,
            41,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPending = PendingIntent.getActivity(
            context,
            42,
            Intent(context, LabActivity::class.java).apply {
                action = ACTION_STOP
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(openPending)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setShowWhen(true)

        if (ongoing) {
            builder.addAction(0, context.getString(R.string.lab_notif_stop), stopPending)
        }

        if (percent != null) {
            builder.setProgress(100, percent.coerceIn(0, 100), false)
        } else {
            builder.setProgress(0, 0, indeterminate)
        }

        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.notify(NOTIFICATION_ID, builder.build())
        }
    }

    fun cancel(context: Context) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.cancel(NOTIFICATION_ID)
        }
    }
}
