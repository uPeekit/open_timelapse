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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.peekit.opentimelapse.TimelapseApp
import org.peekit.opentimelapse.core.engine.CapturedMedia
import org.peekit.opentimelapse.core.engine.CalibrationCalculator
import org.peekit.opentimelapse.core.engine.CalibrationCollector
import org.peekit.opentimelapse.core.engine.CycleMeasurement
import org.peekit.opentimelapse.core.engine.CycleRunner
import org.peekit.opentimelapse.core.engine.CycleStep
import org.peekit.opentimelapse.core.engine.EngineEvent
import org.peekit.opentimelapse.core.engine.EventSink
import org.peekit.opentimelapse.core.engine.FrameFileResult
import org.peekit.opentimelapse.core.engine.FrameStore
import org.peekit.opentimelapse.core.engine.StartRejection
import org.peekit.opentimelapse.core.engine.TimelapseEngine
import org.peekit.opentimelapse.core.model.CalibrationState
import org.peekit.opentimelapse.core.model.SessionManifest
import org.peekit.opentimelapse.core.model.TimelapseConfig
import org.peekit.opentimelapse.storage.FileFrameStore
import java.io.File

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

    @Volatile
    private var manifest: SessionManifest? = null

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
            ACTION_CALIBRATE -> launchCalibration()
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

        sessionJob = scope.launch {
            // Loaded before anything reads it, so the first cycle cannot run on defaults.
            liveConfig = app.configRepository.current()
            val watcher = launch {
                app.configRepository.config.collect { liveConfig = it }
            }

            try {
                // Resolved first so the session manifest records the real camera package.
                if (!ensureCameraResolved()) return@launch

                // A diagnostic cycle is not a session: recording one would leave a manifest
                // claiming zero frames next to a photo that was actually taken.
                val frameStore = if (singleCycle) NoOpFrameStore else openSession(liveConfig)
                val runner = CycleRunner(app.actuator, frameStore, app.clock, waiter, sink)
                val timelapseEngine = TimelapseEngine(runner, app.actuator, app.clock, waiter, sink)
                engine = timelapseEngine

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
                    val summary = timelapseEngine.runSession(manifest?.id ?: sessionId()) { liveConfig }
                    report("Finished (${summary.stopReason}): ${summary.framesCaptured} frames")
                }
            } finally {
                // NonCancellable because Stop cancels this job: a suspend call in a
                // cancelled coroutine throws at its first suspension point, which
                // silently lost the final manifest write and reported 0 frames.
                withContext(NonCancellable) { closeSession() }
                watcher.cancel()
                stopSelfSafely()
            }
        }
    }

    /**
     * Measures this device by shooting a few real frames and reading the timings off the
     * engine's own event stream.
     *
     * Not a search: only the settle delay between "the screen reports on" and "the screen
     * accepts touches" is invisible to every API, so only that one is probed - by raising
     * it and retrying when the unlock fails.
     */
    private fun launchCalibration() {
        if (sessionJob?.isActive == true) return

        framesCaptured = 0
        goForeground("Calibrating...", 0)
        wakeLock.acquire()
        app.log.clear()

        val waiter = AlarmWaiter(this, wakeLock)

        sessionJob = scope.launch {
            try {
                liveConfig = app.configRepository.current()
                if (!ensureCameraResolved()) return@launch

                val collector = CalibrationCollector(statusReportingSink())
                val runner = CycleRunner(app.actuator, NoOpFrameStore, app.clock, waiter, collector)

                var settle = liveConfig.delays.afterWakeMs
                var attempt = 0
                var captures = 0
                app.log.message("Calibrating on ${liveConfig.shutter.packageName} - this takes about a minute")

                // Counts successful captures, not attempts: a cold camera commonly loses the
                // first cycle, and calibrating off a single sample is worse than waiting.
                while (captures < CALIBRATION_CAPTURES && attempt < CALIBRATION_MAX_ATTEMPTS) {
                    val probe = liveConfig.copy(
                        delays = liveConfig.delays.copy(afterWakeMs = settle),
                        naming = liveConfig.naming.copy(enabled = false),
                    )
                    val outcome = runner.run(probe.normalized(), frameIndex = 1)
                    attempt++
                    collector.endCycle(app.clock.nowMs(), outcome.captured)
                    if (outcome.captured) captures++

                    if (!outcome.captured && outcome.failedStep == CycleStep.UNLOCK) {
                        // The one value that cannot be measured: raise it and try again.
                        val next = CalibrationCalculator.nextWakeSettleMs(settle)
                        if (next == null) {
                            report("Could not unlock this device - check the lock screen is set to Swipe")
                            return@launch
                        }
                        app.log.message("Unlock needed more settle time; trying ${next}ms")
                        settle = next
                        continue
                    }
                    if (outcome.fatal) {
                        report("Calibration stopped: ${outcome.detail}")
                        return@launch
                    }
                }

                applyCalibration(collector.measurements, settle)
            } finally {
                withContext(NonCancellable) { closeSession() }
                stopSelfSafely()
            }
        }
    }

    private suspend fun applyCalibration(runs: List<CycleMeasurement>, settleMs: Long) {
        val usable = runs.filter { it.captured }
        if (usable.isEmpty()) {
            report("Calibration could not capture a frame; leaving the defaults alone")
            return
        }

        val minInterval = CalibrationCalculator.minimumIntervalSeconds(usable)
        app.configRepository.update { stored ->
            CalibrationCalculator.deriveConfig(stored, usable).let { tuned ->
                tuned.copy(
                    delays = tuned.delays.copy(afterWakeMs = settleMs),
                    intervalSeconds = tuned.intervalSeconds.coerceAtLeast(minInterval),
                    calibration = CalibrationState(
                        completed = true,
                        atMs = System.currentTimeMillis(),
                        minIntervalSeconds = minInterval,
                        cameraPackage = stored.shutter.packageName,
                    ),
                )
            }
        }

        val tuned = app.configRepository.current()
        report(
            "Calibrated: shortest safe interval ${minInterval}s, " +
                "capture wait ${tuned.capture.captureTimeoutMs / 1000}s, " +
                "settle ${tuned.delays.afterWakeMs}ms"
        )
    }

    /**
     * Starts recording a session and returns the store that will file its frames.
     *
     * With naming off nothing is touched: the camera's own filenames and metadata stay
     * exactly as they are, and the manifest just records where the frames went.
     */
    private suspend fun openSession(config: TimelapseConfig): FrameStore {
        val name = app.sessionStore.newSessionName()
        val record = app.sessionStore.create(name, config, System.currentTimeMillis())
        manifest = record
        app.sessionStore.save(record)

        // The manifest must describe what actually happened, not what was configured:
        // claiming a numbered sequence that was never produced would emit an ffmpeg
        // command pointing at files that do not exist.
        if (!config.naming.enabled) {
            app.log.message("Session $name - keeping the camera's own filenames")
            return NoOpFrameStore
        }
        if (!app.storage.canRenameForeignFiles()) {
            app.log.message(
                "Naming is on but All-files access is not granted; frames keep their " +
                    "original names this session and will render from a file list."
            )
            manifest = record.copy(naming = record.naming.copy(enabled = false))
            app.sessionStore.save(manifest!!)
            return NoOpFrameStore
        }

        app.log.message("Session $name -> ${record.folderPath} as ${config.naming.prefix}...")
        return FileFrameStore(this, File(record.folderPath), config.naming, app.storage)
    }

    private suspend fun closeSession() {
        val record = manifest ?: return
        app.sessionStore.save(record.copy(endedAtMs = System.currentTimeMillis()))
        manifest = null
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

                manifest = manifest?.let { record ->
                    record.copy(
                        frameCount = framesCaptured,
                        lastIndex = event.index,
                        // Recorded whether or not frames were renamed - this list is what
                        // makes a session renderable without touching the camera roll.
                        framePaths = record.framePaths + event.paths,
                    )
                }
                // Flushed periodically so a flat battery still leaves a renderable session.
                if (framesCaptured % MANIFEST_FLUSH_EVERY == 0) {
                    manifest?.let { record -> scope.launch { app.sessionStore.save(record) } }
                }
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
            // Unused for path selection - CycleRunner skips the store when naming is off -
            // but kept correct: prefer the real path a renderer can open.
            FrameFileResult(ok = true, paths = media.map { it.path ?: it.uri })
    }

    companion object {
        const val ACTION_START = "org.peekit.opentimelapse.START"
        const val ACTION_STOP = "org.peekit.opentimelapse.STOP"
        const val ACTION_SINGLE_CYCLE = "org.peekit.opentimelapse.SINGLE_CYCLE"
        const val ACTION_CALIBRATE = "org.peekit.opentimelapse.CALIBRATE"

        /** Enough successful runs to see variance without making the user wait. */
        private const val CALIBRATION_CAPTURES = 3
        private const val CALIBRATION_MAX_ATTEMPTS = 6

        private const val MANIFEST_FLUSH_EVERY = 5

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
