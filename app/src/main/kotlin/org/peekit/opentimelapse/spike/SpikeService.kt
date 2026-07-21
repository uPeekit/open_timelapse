package org.peekit.opentimelapse.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Long-lived foreground service that hosts the probes.
 *
 * It stays running for the whole spike so probe 1 measures a *background* launch from an
 * already-established service - which is the situation the real timelapse loop is in -
 * rather than one that inherits a fresh start's temporary privileges.
 */
class SpikeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForeground()
        SpikeLog.log("SpikeService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val which = intent?.getStringExtra(EXTRA_WHICH)
        val delayMs = intent?.getLongExtra(EXTRA_DELAY, 0L) ?: 0L
        if (which != null) runProbe(which, delayMs, intent.extras)
        return START_STICKY
    }

    fun runProbe(which: String, delayMs: Long, params: Bundle? = null) {
        scope.launch {
            if (delayMs > 0) {
                SpikeLog.log("waiting ${delayMs}ms before '$which' - put the phone to sleep now")
                delay(delayMs)
            }
            Probes.run(applicationContext, which, params)
        }
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        SpikeLog.log("SpikeService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "OpenTimelapse spike", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenTimelapse spike")
            .setContentText("Running Phase 0 probes")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val EXTRA_WHICH = "which"
        const val EXTRA_DELAY = "delay"

        private const val CHANNEL_ID = "spike"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var instance: SpikeService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, SpikeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
