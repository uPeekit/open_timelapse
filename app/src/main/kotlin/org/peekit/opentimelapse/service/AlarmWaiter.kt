package org.peekit.opentimelapse.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.peekit.opentimelapse.core.engine.Waiter

/**
 * Waits in a way that survives doze.
 *
 * A bare `delay()` is not a timer once the screen is off: the CPU sleeps and a long wait
 * either drifts badly or never fires. Short waits therefore run under the session wakelock,
 * and long ones hand over to an exact alarm so the CPU can sleep for the interval and still
 * be woken on time.
 *
 * The foreground service stays alive throughout, so the alarm only has to wake the CPU - it
 * never has to start a service from the background.
 */
class AlarmWaiter(
    private val context: Context,
    private val wakeLock: SessionWakeLock,
) : Waiter {

    private val alarms = context.getSystemService(AlarmManager::class.java)

    override suspend fun sleep(durationMs: Long) {
        if (durationMs > 0) awaitUntil(System.currentTimeMillis() + durationMs)
    }

    override suspend fun awaitUntil(epochMs: Long) {
        val remaining = epochMs - System.currentTimeMillis()
        if (remaining <= 0) return

        if (remaining <= SHORT_WAIT_MS || !canScheduleExact()) {
            // Re-arm before every short wait: this is the path that keeps the CPU up, and
            // an expired wakelock turns delay() into an unreliable timer.
            wakeLock.acquire()
            delay(remaining)
            return
        }

        // Long wait: let the CPU sleep, and have the alarm bring it back.
        wakeLock.release()
        try {
            awaitAlarm(epochMs)
        } finally {
            wakeLock.acquire()
        }

        // Alarms may fire slightly early; finish the remainder precisely.
        val overshoot = epochMs - System.currentTimeMillis()
        if (overshoot > 0) delay(overshoot)
    }

    private fun canScheduleExact(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarms?.canScheduleExactAlarms() == true
        } else {
            alarms != null
        }

    private suspend fun awaitAlarm(epochMs: Long) = suspendCancellableCoroutine { continuation ->
        val action = "$ACTION_PREFIX.${counter.incrementAndGet()}"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receivedContext: Context?, intent: Intent?) {
                runCatching { context.unregisterReceiver(this) }
                if (continuation.isActive) continuation.resume(Unit)
            }
        }

        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

        val pending = PendingIntent.getBroadcast(
            context,
            0,
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        continuation.invokeOnCancellation {
            runCatching { context.unregisterReceiver(receiver) }
            runCatching { alarms?.cancel(pending) }
        }

        runCatching {
            // setAlarmClock, not setExactAndAllowWhileIdle.
            //
            // Measured on ColorOS: setExactAndAllowWhileIdle was granted as exact
            // (exactAllowReason=policy_permission) and still handed a 1m47s window, because
            // the app-standby bucket defers it - something the battery-optimisation
            // exemption does not cover. At a 150s interval that produced frames in pairs:
            // an alarm ~100s late, then the following slot already due.
            //
            // setAlarmClock is the one kind the system will not defer, at the cost of an
            // alarm icon in the status bar. A timelapse genuinely is a user-scheduled,
            // time-critical event, so that is an honest use of it rather than a loophole.
            val show = PendingIntent.getActivity(
                context,
                0,
                Intent(context, org.peekit.opentimelapse.MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarms?.setAlarmClock(AlarmManager.AlarmClockInfo(epochMs, show), pending)
        }.onFailure {
            runCatching { context.unregisterReceiver(receiver) }
            if (continuation.isActive) continuation.resume(Unit)
        }
    }

    private companion object {
        /** Below this, holding the CPU is cheaper than the alarm round trip. */
        const val SHORT_WAIT_MS = 60_000L
        const val ACTION_PREFIX = "org.peekit.opentimelapse.ALARM"
        val counter = AtomicInteger(0)
    }
}
