package org.peekit.opentimelapse.render

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.peekit.opentimelapse.TimelapseApp
import org.peekit.opentimelapse.core.model.SessionManifest
import org.peekit.opentimelapse.core.render.Encoder
import org.peekit.opentimelapse.core.render.FfmpegCommandBuilder
import org.peekit.opentimelapse.core.render.RenderSpec

/**
 * Renders a recorded session into an mp4 using the bundled ffmpeg.
 *
 * A foreground service because a render outlives the screen: an hour of frames takes
 * minutes to encode, and Android would otherwise freeze the process the moment the user
 * switches away.
 */
class RenderService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var app: TimelapseApp
    private var job: Job? = null

    override fun onCreate() {
        super.onCreate()
        app = application as TimelapseApp
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                job?.cancel()
                app.log.message("Render cancelled")
                stopSelfSafely()
            }

            else -> {
                val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
                if (sessionId == null) stopSelfSafely() else startRender(sessionId, intent)
            }
        }
        return START_NOT_STICKY
    }

    private fun startRender(sessionId: String, intent: Intent) {
        if (job?.isActive == true) {
            app.log.message("A render is already running")
            return
        }
        goForeground("Preparing...", null)

        val spec = RenderSpec(
            fps = intent.getIntExtra(EXTRA_FPS, 30),
            longEdgePx = intent.getIntExtra(EXTRA_LONG_EDGE, 1920),
            // Always x264: the MediaCodec encoder cannot run from a standalone binary,
            // which has no JavaVM for ffmpeg's JNI bridge. See Encoder.HARDWARE.
            encoder = Encoder.X264,
            deflicker = intent.getBooleanExtra(EXTRA_DEFLICKER, false),
        )

        job = scope.launch {
            val manifest = app.sessionStore.loadAll().firstOrNull { it.id == sessionId }
            if (manifest == null) {
                finish("Session $sessionId is gone")
                return@launch
            }
            if (!app.ffmpeg.isAvailable) {
                finish("This build has no ffmpeg binary; use the exported command on a computer instead")
                return@launch
            }
            if (!isCharging()) {
                app.log.message("Encoding is software-only and slow - plug the phone in for a long session")
            }

            render(manifest, spec)
        }
    }

    private suspend fun render(manifest: SessionManifest, spec: RenderSpec) {
        val output = outputFile(manifest)
        val argv = buildArgv(manifest, spec, output) ?: run {
            finish("${manifest.name} has no frames to render")
            return
        }

        app.log.message("Rendering ${manifest.frameCount} frames -> ${output.name}")
        val total = manifest.frameCount

        val result = app.ffmpeg.render(argv) { progress ->
            val percent = progress.percentOf(total)
            notify(
                if (percent != null) "Rendering ${percent}% (frame ${progress.frame})"
                else "Rendering frame ${progress.frame}",
                percent,
            )
        }

        when (result) {
            is RenderResult.Success -> {
                publish(output)
                finish("Rendered ${output.name} in ${result.elapsedMs / 1000}s")
            }

            is RenderResult.Failure -> {
                // The output is a truncated file that would only confuse the gallery.
                runCatching { if (output.exists()) output.delete() }
                result.log.takeLast(6).forEach { app.log.message("  ffmpeg: $it") }
                finish("Render failed: ${result.message}")
            }

            RenderResult.Unavailable -> finish("ffmpeg binary is missing from this build")
        }
    }

    private suspend fun buildArgv(
        manifest: SessionManifest,
        spec: RenderSpec,
        output: File,
    ): List<String>? {
        if (manifest.isRenderable) {
            return FfmpegCommandBuilder.fromPattern(manifest, spec, output.absolutePath)
        }
        // Frames kept the camera's own names, so they are fed through a list naming exactly
        // this session's files - nothing else sharing the folder gets pulled in.
        val export = app.sessionExporter.export(manifest, spec, output.absolutePath) ?: return null
        return FfmpegCommandBuilder.fromConcatList(export.concatListPath, spec, output.absolutePath)
    }

    /**
     * Written to app-specific external storage, which never needs a permission, then
     * published to MediaStore so it appears in the gallery like any other video.
     */
    private fun outputFile(manifest: SessionManifest): File {
        val folder = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        if (!folder.exists()) folder.mkdirs()
        return File(folder, "${manifest.name}.mp4")
    }

    private fun publish(output: File) {
        runCatching {
            android.media.MediaScannerConnection.scanFile(
                this,
                arrayOf(output.absolutePath),
                arrayOf("video/mp4"),
                null,
            )
        }
    }

    private fun isCharging(): Boolean =
        getSystemService(BatteryManager::class.java)?.isCharging == true

    private fun finish(message: String) {
        app.log.message(message)
        notify(message, null)
        stopSelfSafely()
    }

    private fun stopSelfSafely() {
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Rendering", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun build(status: String, percent: Int?): Notification {
        val cancel = android.app.PendingIntent.getService(
            this,
            0,
            Intent(this, RenderService::class.java).setAction(ACTION_CANCEL),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenTimelapse")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply { if (percent != null) setProgress(100, percent, false) }
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
            .build()
    }

    private fun notify(status: String, percent: Int?) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, build(status, percent))
    }

    private fun goForeground(status: String, percent: Int?) {
        val notification = build(status, percent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_CANCEL = "org.peekit.opentimelapse.RENDER_CANCEL"
        const val EXTRA_SESSION_ID = "session"
        const val EXTRA_FPS = "fps"
        const val EXTRA_LONG_EDGE = "longEdge"
        const val EXTRA_ARCHIVAL = "archival"
        const val EXTRA_DEFLICKER = "deflicker"

        private const val CHANNEL_ID = "render"
        private const val NOTIFICATION_ID = 2

        fun render(
            context: Context,
            sessionId: String,
            spec: RenderSpec,
        ) {
            val intent = Intent(context, RenderService::class.java)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_FPS, spec.fps)
                .putExtra(EXTRA_LONG_EDGE, spec.longEdgePx)
                .putExtra(EXTRA_ARCHIVAL, spec.encoder == Encoder.X264)
                .putExtra(EXTRA_DEFLICKER, spec.deflicker)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
