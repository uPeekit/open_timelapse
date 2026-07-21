package org.peekit.opentimelapse.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.peekit.opentimelapse.TimelapseApp
import org.peekit.opentimelapse.core.engine.CapturedMedia
import org.peekit.opentimelapse.core.engine.CycleRunner
import org.peekit.opentimelapse.core.engine.EngineEvent
import org.peekit.opentimelapse.core.engine.EventSink
import org.peekit.opentimelapse.core.engine.FrameFileResult
import org.peekit.opentimelapse.core.engine.FrameStore
import org.peekit.opentimelapse.core.engine.StartRejection
import org.peekit.opentimelapse.core.engine.TimelapseEngine
import org.peekit.opentimelapse.core.model.TimelapseConfig

/**
 * Hosts a timelapse session for its whole lifetime.
 *
 * Running as a foreground service throughout is what keeps the process alive and lets
 * [AlarmWaiter] merely wake the CPU, rather than having to start a service from the
 * background - which modern Android would refuse.
 */
class TimelapseService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var app: TimelapseApp
    private lateinit var notification: TimelapseNotification
    private lateinit var wakeLock: SessionWakeLock

    private var sessionJob: Job? = null
    private var engine: TimelapseEngine? = null
    private var framesCaptured = 0

    /** The snapshot the engine reads at the start of each cycle. */
    @Volatile
    private var liveConfig: TimelapseConfig = TimelapseConfig()

    override fun onCreate() {
        super.onCreate()
        app = application as TimelapseApp
        notification = TimelapseNotification(this)
        wakeLock = SessionWakeLock(this)
        notification.ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stop()
            ACTION_SINGLE_CYCLE -> launchSession(singleCycle = true)
            else -> launchSession(singleCycle = false)
        }
        return START_NOT_STICKY
    }

    private fun launchSession(singleCycle: Boolean) {
        if (sessionJob?.isActive == true) return

        framesCaptured = 0
        goForeground(if (singleCycle) "Single cycle" else "Starting...", 0)
        wakeLock.acquire()
        app.log.clear()

        val waiter = AlarmWaiter(this, wakeLock)
        val sink = statusReportingSink()
        val runner = CycleRunner(app.actuator, NoOpFrameStore, app.clock, waiter, sink)
        val timelapseEngine = TimelapseEngine(runner, app.actuator, app.clock, waiter, sink)
        engine = timelapseEngine

        sessionJob = scope.launch {
            // Loaded before anything reads it, so the first cycle cannot run on defaults.
            liveConfig = app.configRepository.current()
            val watcher = launch {
                app.configRepository.config.collect { liveConfig = it }
            }

            try {
                if (!ensureCameraResolved()) return@launch

                timelapseEngine.preflight(liveConfig)?.let { rejection ->
                    report(explain(rejection))
                    return@launch
                }

                if (singleCycle) {
                    val outcome = runner.run(liveConfig.normalized(), frameIndex = 1)
                    report(
                        if (outcome.captured) "Single cycle captured a frame"
                        else "Single cycle failed at ${outcome.failedStep}: ${outcome.detail}"
                    )
                } else {
                    val summary = timelapseEngine.runSession(sessionId()) { liveConfig }
                    report("Finished (${summary.stopReason}): ${summary.framesCaptured} frames")
                }
            } finally {
                watcher.cancel()
                stopSelfSafely()
            }
        }
    }

    /**
     * Fills in the camera package the first time a session runs, so the app works on an
     * untouched install without the user typing a package name.
     */
    private suspend fun ensureCameraResolved(): Boolean {
        if (liveConfig.shutter.packageName.isNotBlank()) return true

        val resolved = app.cameraResolver.resolveDefault()
        if (resolved == null) {
            report("No camera app answered IMAGE_CAPTURE on this device.")
            return false
        }

        app.configRepository.update { stored ->
            stored.copy(shutter = stored.shutter.copy(packageName = resolved))
        }
        liveConfig = app.configRepository.current()
        app.log.message("Camera app detected: $resolved")
        return true
    }

    private fun stop() {
        engine?.requestStop()
        sessionJob?.cancel()
        stopSelfSafely()
    }

    private fun report(text: String) {
        app.log.message(text)
        notification.update(text, framesCaptured)
    }

    private fun stopSelfSafely() {
        wakeLock.release()
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    /** Mirrors engine events into the log and keeps the notification current. */
    private fun statusReportingSink() = EventSink { event ->
        app.log.emit(event)
        when (event) {
            is EngineEvent.FrameCaptured -> {
                framesCaptured++
                notification.update("Running - frame ${event.index} captured", framesCaptured)
            }

            is EngineEvent.StepStarted -> notification.update("${event.step}...", framesCaptured)

            else -> Unit
        }
    }

    private fun explain(rejection: StartRejection): String = when (rejection) {
        StartRejection.DEVICE_SECURE_LOCK ->
            "Your lock screen needs a PIN, pattern or password, which a swipe cannot pass. " +
                "Set the lock to Swipe, or use Awake mode."
    }

    private fun goForeground(status: String, frames: Int) {
        val built = notification.build(status, frames)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                TimelapseNotification.ID,
                built,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(TimelapseNotification.ID, built)
        }
    }

    private fun sessionId(): String = "s${System.currentTimeMillis()}"

    override fun onDestroy() {
        wakeLock.release()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Frame filing arrives in Phase 4; until then frames keep the camera's own names. */
    private object NoOpFrameStore : FrameStore {
        override suspend fun fileFrame(media: List<CapturedMedia>, index: Int) =
            FrameFileResult(ok = true, paths = media.map { it.uri })
    }

    companion object {
        const val ACTION_START = "org.peekit.opentimelapse.START"
        const val ACTION_STOP = "org.peekit.opentimelapse.STOP"
        const val ACTION_SINGLE_CYCLE = "org.peekit.opentimelapse.SINGLE_CYCLE"

        fun send(context: Context, action: String) {
            val intent = Intent(context, TimelapseService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
