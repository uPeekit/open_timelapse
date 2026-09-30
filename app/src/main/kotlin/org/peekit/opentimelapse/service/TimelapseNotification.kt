package org.peekit.opentimelapse.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.peekit.opentimelapse.MainActivity
import org.peekit.opentimelapse.R

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
            Intent(context, MainActivity::class.java),
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
            .setColor(BRAND_PINK)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    fun update(status: String, frames: Int) {
        manager?.notify(ID, build(status, frames))
    }

    /**
     * The outcome, posted once the ongoing notification has been removed.
     *
     * Without it the end of a session or a calibration was silent: the foreground
     * notification is removed on stop, so the only record was in the app - which the user
     * had to think to open. Deliberately not ongoing, and auto-cancelling: a finished run is
     * news, not a running state, and it must be dismissible.
     */
    fun result(text: String) {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager?.notify(
            RESULT_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setContentTitle("OpenTimelapse")
                .setContentText(text)
                // The calibration result is a sentence, not a word; let it wrap when expanded.
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setColor(BRAND_PINK)
                .setSmallIcon(R.drawable.ic_notification)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build(),
        )
    }

    companion object {
        const val ID = 1

        /** Matches the app theme's pink primary (Theme.kt). */
        private val BRAND_PINK = 0xFFC2185B.toInt()

        /** Separate from [ID], which is being removed at the moment the result is posted. */
        private const val RESULT_ID = 3
        private const val CHANNEL_ID = "timelapse"
    }
}
