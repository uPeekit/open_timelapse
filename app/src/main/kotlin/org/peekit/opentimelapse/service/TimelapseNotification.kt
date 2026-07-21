package org.peekit.opentimelapse.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.peekit.opentimelapse.spike.SpikeActivity

/** The persistent notification: current status, frames so far, and a Stop action. */
class TimelapseNotification(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Timelapse", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
    }

    fun build(status: String, frames: Int): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, SpikeActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            context,
            1,
            Intent(context, TimelapseService::class.java).setAction(TimelapseService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(if (frames > 0) "Timelapse - $frames frames" else "Timelapse")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    fun update(status: String, frames: Int) {
        manager?.notify(ID, build(status, frames))
    }

    companion object {
        const val ID = 1
        private const val CHANNEL_ID = "timelapse"
    }
}
