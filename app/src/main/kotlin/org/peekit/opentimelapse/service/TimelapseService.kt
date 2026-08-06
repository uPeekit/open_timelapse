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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.peekit.opentimelapse.MainActivity
import org.peekit.opentimelapse.TimelapseApp
import org.peekit.opentimelapse.accessibility.AccessibilityBridge
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
import org.peekit.opentimelapse.core.model.CycleMode
import org.peekit.opentimelapse.core.model.SessionManifest
import org.peekit.opentimelapse.core.model.StartTrigger
import org.peekit.opentimelapse.core.model.TimelapseConfig
import org.peekit.opentimelapse.core.net.ServerState
import org.peekit.opentimelapse.net.AppControlBackend
import org.peekit.opentimelapse.net.ControlServer
import org.peekit.opentimelapse.net.LocalNetwork
import org.peekit.opentimelapse.net.NsdRegistration
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

    /** Mutated by the engine's event sink, read by notification code on other threads. */
    @Volatile
    private var framesCaptured = 0

    private val charging get() = app.charging
    private var controlServer: ControlServer? = null
    private var nsd: NsdRegistration? = null

    /** Kept so the mandatory startForeground() at the top of onStartCommand says something true. */
    @Volatile
    private var notificationStatus = "Idle"

    /** The last thing [report] said, re-posted as the result notification when the run ends. */
    @Volatile
    private var lastReport: String? = null

    /** Calibration reuses the session's event sink, but must not describe itself as one. */
    @Volatile
    private var calibrating = false

    /**
     * True between a cycle's first step and the frame it produces.
     *
     * Stop reads it to decide whether there is anything worth waiting for: the engine spends
     * most of a session idle between frames, where stopping is instant, and only a cycle
     * already under way is worth letting finish.
     */
    @Volatile
    private var cycleInFlight = false

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
        // Unconditionally, and before anything else. startForegroundService() requires
        // startForeground() within a few seconds or the system kills the process - which it
        // did on every Stop, because that path went straight to stopSelf().
        //
        // The status is decided from the action first: posting the previous one showed
        // "Starting..." when the user pressed Stop, and "Idle" when they pressed it again.
        notificationStatus = when (intent?.action) {
            ACTION_STOP -> "Stopping..."
            ACTION_CALIBRATE -> "Calibrating..."
            ACTION_SINGLE_CYCLE -> "Single cycle"
            else -> if (sessionJob?.isActive == true) notificationStatus else "Starting..."
        }
        goForeground(notificationStatus, framesCaptured)

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
        notificationStatus = if (singleCycle) "Single cycle" else "Starting..."
        goForeground(notificationStatus, 0)
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
            // Session-scoped on purpose: a battery watcher that outlived the shoot would
            // switch the user's socket while they were just using the phone. The config is
            // read per battery event, so enabling or editing webhooks mid-session applies.
            if (!singleCycle) {
                charging.start { liveConfig.charging }
                startControlServer(liveConfig)
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
                    val offset = awaitStart(frameStore, waiter, sink) ?: return@launch
                    val summary = timelapseEngine.runSession(manifest?.id ?: sessionId()) {
                        // The manual frame already used the first index, so the schedule
                        // continues from the next one.
                        liveConfig.copy(
                            naming = liveConfig.naming.copy(
                                startIndex = liveConfig.naming.startIndex + offset,
                            ),
                        )
                    }
                    report("Finished (${summary.stopReason}): ${summary.framesCaptured} frames")
                }
            } finally {
                // NonCancellable because Stop cancels this job: a suspend call in a
                // cancelled coroutine throws at its first suspension point, which
                // silently lost the final manifest write and reported 0 frames.
                val stoppedByUser = !isActive
                charging.stop()
                stopControlServer()
                withContext(NonCancellable) {
                    closeSession()
                    // Only the test shot returns to the app; a full session is unattended by
                    // definition and announces itself by notification alone.
                    if (singleCycle && !stoppedByUser) returnToApp()
                }
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
        calibrating = true
        notificationStatus = "Calibrating..."
        goForeground(notificationStatus, 0)
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
                // Read before entering NonCancellable, which would report active again: a
                // user who pressed Stop chose to leave, and must not be pulled back in.
                val stoppedByUser = !isActive
                withContext(NonCancellable) {
                    closeSession()
                    if (!stoppedByUser) returnToApp()
                }
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
            // Every run, not only the ones that landed: a cycle that timed out is the single
            // most useful measurement there is for a ceiling, and filtering it out here was
            // what kept the ratchet alive even after the calculator learned to count it.
            CalibrationCalculator.deriveConfig(stored, runs).let { tuned ->
                tuned.copy(
                    delays = tuned.delays.copy(afterWakeMs = settleMs),
                    intervalSeconds = tuned.intervalSeconds.coerceAtLeast(minInterval),
                    // Folded in rather than replaced: cycle time swings with whether the
                    // camera cold-starts, and one lucky run must not erase how slow this
                    // phone is known to get. See CalibrationState.advanced.
                    calibration = stored.calibration.advanced(
                        minIntervalSeconds = minInterval,
                        camera = stored.shutter.packageName,
                        atMs = System.currentTimeMillis(),
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
     * Holds the session until the user is actually ready, and returns how many frames were
     * already taken during that wait.
     *
     * The camera is raised but never reconfigured - the app resumes whatever mode it was
     * left in, which is the whole reason it drives the manufacturer's app rather than
     * opening its own camera.
     */
    private suspend fun awaitStart(
        frameStore: FrameStore,
        waiter: AlarmWaiter,
        sink: EventSink,
    ): Int? {
        app.actuator.launchCamera(liveConfig.shutter.packageName)

        if (liveConfig.session.startTrigger == StartTrigger.TIMER) {
            val grace = liveConfig.session.startDelaySeconds
            if (grace > 0) {
                report("Camera open - set the mode you want. First frame in ${grace}s.")
                waiter.sleep(grace * 1000L)
            }
            return 0
        }

        val timeoutMs = liveConfig.session.manualShotTimeoutMinutes.coerceAtLeast(1) * 60_000L
        report("Set up your camera, then take one photo - that starts the timelapse.")

        val since = app.clock.nowMs()
        val media = app.actuator.awaitNewMedia(
            sinceMs = since,
            timeoutMs = timeoutMs,
            quietMs = liveConfig.capture.siblingQuietMs,
            expectedOwner = liveConfig.shutter.packageName,
        )
        if (media.isEmpty()) {
            report("No photo taken within ${liveConfig.session.manualShotTimeoutMinutes} minutes - stopping.")
            return null
        }

        // That photo is frame 1: filed, counted and named like any other.
        val index = liveConfig.naming.startIndex
        val filed = frameStore.fileFrame(media, index)
        val paths = if (filed.ok) filed.paths else media.map { it.path ?: it.uri }
        sink.emit(EngineEvent.FrameCaptured(app.clock.nowMs(), index, paths))

        report("Started from your photo. Next frame in ${liveConfig.intervalSeconds}s.")
        // In lock-cycle mode, lock the moment the manual shot is taken: the screen going dark
        // is the clearest possible signal that the session has taken over. The engine's own
        // cycle wakes and unlocks again for the next frame.
        if (liveConfig.mode == CycleMode.LOCK_CYCLE) app.actuator.lockScreen()
        // Wait out one interval so the engine's first slot lands on schedule rather than
        // firing immediately and double-shooting.
        waiter.sleep(liveConfig.intervalMs)
        return 1
    }

    /**
     * Starts recording a session and returns the store that will file its frames.
     *
     * With naming off nothing is touched: the camera's own filenames and metadata stay
     * exactly as they are, and the manifest just records where the frames went.
     */
    private suspend fun openSession(config: TimelapseConfig): FrameStore {
        val name = app.sessionStore.newSessionName(config)
        val record = app.sessionStore.create(name, config, System.currentTimeMillis())
        manifest = record
        app.sessionStore.save(record)
        app.runState.update { it.copy(sessionName = name) }

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
     * Brings up the LAN control server for the session, but only when it is switched on and
     * holds a token. Off is the default, and a tokenless port is never opened - [NetworkConfig.runnable]
     * enforces both.
     */
    private fun startControlServer(config: TimelapseConfig) {
        if (!config.network.runnable) return

        app.runState.update {
            it.copy(
                serverState = ServerState.RUNNING,
                intervalSeconds = config.intervalSeconds,
                startedAtMs = System.currentTimeMillis(),
            )
        }

        val backend = AppControlBackend(this, app.actuator, app.runState)
        val server = ControlServer(backend, app.log)
        server.start(config.network.token, config.network.port)
        controlServer = server

        val port = server.boundPort
        if (port <= 0) {
            controlServer = null
            app.runState.reset()
            return
        }

        val registration = NsdRegistration(this, app.log)
        registration.register(port)
        nsd = registration

        val ip = LocalNetwork.ipv4Address()
        if (ip != null) {
            app.log.message("Control server live at http://$ip:$port - scan the QR in the app to pair")
        } else {
            app.log.message("Control server live on port $port")
        }
    }

    private fun stopControlServer() {
        nsd?.unregister()
        nsd = null
        controlServer?.stop()
        controlServer = null
        app.runState.reset()
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

    /**
     * Stops the session, letting a frame already in flight finish.
     *
     * Cancelling outright threw away whatever the cycle was doing - a shutter already pressed
     * and a file already written were simply lost. So the engine is asked to stop and given a
     * bounded window to reach the end of its cycle, which is also what produces the real
     * summary. The window matters: the engine spends most of its life inside the wait between
     * frames, where a graceful stop cannot land, so a hard cancel is still the fallback.
     */
    private fun stop() {
        val running = sessionJob?.takeIf { it.isActive }
        if (running == null) {
            stopSelfSafely()
            return
        }

        engine?.requestStop()

        // Nothing in flight - the session is idle between frames, which is where Stop is
        // pressed most of the time. Waiting there would only make Stop feel broken.
        if (!cycleInFlight) {
            running.cancel()
            report("Stopped after $framesCaptured frame${if (framesCaptured == 1) "" else "s"}")
            stopSelfSafely()
            return
        }

        notificationStatus = "Finishing the current frame..."
        notification.update(notificationStatus, framesCaptured)

        scope.launch {
            val endedOnItsOwn = withTimeoutOrNull(GRACEFUL_STOP_MS) { running.join() } != null
            if (!endedOnItsOwn) {
                running.cancel()
                // The engine never reached its summary, so say something true instead of
                // repeating whatever was last reported mid-run.
                lastReport =
                    "Stopped after $framesCaptured frame${if (framesCaptured == 1) "" else "s"}"
                stopSelfSafely()
            }
            // Otherwise the session's own finally has already reported and stopped the service.
        }
    }

    private fun report(text: String) {
        lastReport = text
        app.log.message(text)
        notification.update(text, framesCaptured)
    }

    /**
     * Brings the app back to the front after a run the user is watching.
     *
     * Calibration and a test shot both end with the camera in front and, in lock-cycle mode,
     * a locked screen - so the result was only visible to someone who thought to navigate
     * back, which is not obvious when the phone has been shooting by itself for a minute.
     * A real session is deliberately excluded: it can end at four in the morning, and waking
     * the phone then would be wrong.
     */
    private suspend fun returnToApp() {
        // The lock that ends the final cycle is asynchronous, exactly as the wake is. Asking
        // the display straight afterwards can still answer "on", which skipped the wake
        // entirely and left the phone showing a lock screen that arrived a moment later.
        awaitLockSettled()

        if (app.actuator.isScreenOn()) {
            app.log.message("Returning to the app: screen already on")
        } else {
            val woke = app.actuator.wakeScreen()
            // The same settle the cycle pays: a screen that reports itself on does not yet
            // accept touches, and the swipe below would be swallowed.
            delay(liveConfig.delays.afterWakeMs)
            app.log.message("Returning to the app: wake ${woke.describe()}")
        }

        unlockForReturn()

        // Launched from the accessibility service where possible: it is system-bound, which
        // is what exempts it from the background-activity-launch restrictions.
        val launcher: Context = AccessibilityBridge.service ?: this
        val intent = Intent(this, MainActivity::class.java)
            // SINGLE_TOP so an app already in the back stack is handed the extra through
            // onNewIntent, rather than being reordered forward without ever reading it.
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            .putExtra(MainActivity.EXTRA_OVER_KEYGUARD, true)
        runCatching { launcher.startActivity(intent) }
            .onFailure { app.log.message("Could not bring the app forward: ${it.message}") }
    }

    /** Waits for the screen to go dark after the closing lock, so the wake below is not skipped. */
    private suspend fun awaitLockSettled() {
        if (liveConfig.mode != CycleMode.LOCK_CYCLE) return
        val deadline = app.clock.nowMs() + LOCK_SETTLE_TIMEOUT_MS
        while (app.clock.nowMs() < deadline) {
            if (!app.actuator.isScreenOn()) return
            delay(LOCK_SETTLE_POLL_MS)
        }
    }

    /**
     * Dismisses the keyguard, retried and verified against device state.
     *
     * The dispatch result is not evidence - One UI swallows a swipe sent too soon after a
     * wake, reproducibly, which is why the cycle itself retries. A single unverified attempt
     * here is what left the phone sitting on the lock screen with the result unread.
     */
    private suspend fun unlockForReturn() {
        if (!app.actuator.isKeyguardShowing()) return

        // A swipe cannot pass a PIN, pattern or password; the notification does the telling.
        if (app.actuator.isDeviceSecure()) {
            app.log.message("Lock screen is secured - unlock to see the result")
            return
        }

        for (attempt in 1..liveConfig.unlock.attempts.coerceAtLeast(1)) {
            app.actuator.swipeUnlock(liveConfig.unlock)
            delay(liveConfig.delays.afterUnlockMs)
            if (!app.actuator.isKeyguardShowing()) {
                app.log.message("Returning to the app: unlocked on attempt $attempt")
                return
            }
        }
        app.log.message("Returning to the app: the lock screen would not dismiss")
    }

    private fun stopSelfSafely() {
        wakeLock.release()
        // REMOVE, not DETACH: detaching left the ongoing notification on screen with whatever
        // text it last had - "Stopping..." after a Stop - and nothing ever updated it again,
        // so it went stale.
        stopForeground(STOP_FOREGROUND_REMOVE)
        // The outcome then goes up as its own dismissible notification. Removing the ongoing
        // one alone was silent: a calibration that finished while the phone sat face-down
        // announced itself nowhere at all.
        lastReport?.let { notification.result(it) }
        lastReport = null
        calibrating = false
        stopSelf()
    }

    /** Mirrors engine events into the log and keeps the notification current. */
    private fun statusReportingSink() = EventSink { event ->
        app.log.emit(event)
        when (event) {
            is EngineEvent.FrameCaptured -> {
                cycleInFlight = false
                framesCaptured++
                // Every calibration cycle shoots frame 1, so "frame ${index} captured" read
                // as if nothing were progressing. Count the frames it still needs instead.
                notification.update(
                    if (calibrating) {
                        "Calibrating - $framesCaptured of $CALIBRATION_CAPTURES frames"
                    } else {
                        "Running - frame ${event.index} captured"
                    },
                    framesCaptured,
                )

                // Published for the control server: frame count, the preview source, and an
                // estimate of the next slot (the engine schedules absolutely, but last+interval
                // is close enough for a status readout).
                app.runState.update {
                    it.copy(
                        framesCaptured = framesCaptured,
                        lastFramePath = event.paths.lastOrNull() ?: it.lastFramePath,
                        nextFrameAtMs = app.clock.nowMs() + liveConfig.intervalMs,
                    )
                }

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
                // The flush carries endedAtMs = now (the last frame's time): if the phone dies
                // outright, closeSession never runs to write it, and without this the session
                // that ran until the battery gave out would show no duration at all.
                if (framesCaptured % MANIFEST_FLUSH_EVERY == 0) {
                    manifest?.let { record ->
                        val flushed = record.copy(endedAtMs = app.clock.nowMs())
                        scope.launch { app.sessionStore.save(flushed) }
                    }
                }
            }

            is EngineEvent.StepStarted -> {
                cycleInFlight = true
                notification.update("${event.step}...", framesCaptured)
            }

            is EngineEvent.FrameFailed -> cycleInFlight = false

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
        charging.stop()
        stopControlServer()
        wakeLock.release()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Store for sessions that keep the camera's own filenames: files nothing, moves nothing. */
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

        /** The closing lock is asynchronous; this is how long its effect is waited for. */
        private const val LOCK_SETTLE_TIMEOUT_MS = 2_000L
        private const val LOCK_SETTLE_POLL_MS = 100L

        /**
         * How long Stop lets an in-flight frame finish before cancelling. Long enough for a
         * slow capture to land, short enough that Stop still feels like stopping.
         */
        private const val GRACEFUL_STOP_MS = 20_000L

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
