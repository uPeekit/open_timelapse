package org.peekit.opentimelapse

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.peekit.opentimelapse.actuator.AndroidDeviceActuator
import org.peekit.opentimelapse.actuator.CameraResolver
import org.peekit.opentimelapse.core.engine.Clock
import android.util.Log
import org.peekit.opentimelapse.data.ConfigRepository
import org.peekit.opentimelapse.data.LogFile
import org.peekit.opentimelapse.data.LogRepository
import org.peekit.opentimelapse.data.RunStateRepository
import org.peekit.opentimelapse.render.FfmpegRunner
import org.peekit.opentimelapse.service.ChargingWebhooks
import org.peekit.opentimelapse.storage.SessionExporter
import org.peekit.opentimelapse.storage.SessionStore
import org.peekit.opentimelapse.storage.StorageAccess

/**
 * The dependency graph, wired by hand.
 *
 * It is about a dozen objects with no cycles and no scoping beyond "one per process", so a
 * DI framework would add a build step and indirection without removing any decisions.
 */
class TimelapseApp : Application() {

    val clock: Clock = Clock { System.currentTimeMillis() }

    val logFile by lazy { LogFile(this) }

    val log by lazy { LogRepository(logFile) }

    override fun onCreate() {
        super.onCreate()
        // Capture the "why" of an unattended crash: write the stack trace to the durable log
        // before the process dies, then hand off to the platform's handler so the crash still
        // surfaces normally.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                logFile.append("FATAL on ${thread.name}: ${Log.getStackTraceString(throwable)}")
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    val configRepository by lazy { ConfigRepository(this) }

    /** The live session, published for the control server (and later the UI). */
    val runState by lazy { RunStateRepository() }

    val actuator by lazy { AndroidDeviceActuator(this) }

    val cameraResolver by lazy { CameraResolver(this) }

    val storage by lazy { StorageAccess(this) }

    val sessionStore by lazy { SessionStore(this, storage) }

    val sessionExporter by lazy { SessionExporter(this) }

    val ffmpeg by lazy { FfmpegRunner(this) }

    /**
     * Application-scoped so the Settings "Test" button can fire one without a session, and
     * so an in-flight request is not cancelled by the service shutting down. It only
     * *watches* the battery between [ChargingWebhooks.start] and `stop`.
     */
    val charging by lazy {
        ChargingWebhooks(this, log, CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }
}
