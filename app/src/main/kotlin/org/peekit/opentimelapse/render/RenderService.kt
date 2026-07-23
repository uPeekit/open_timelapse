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
        app.renderState.starting(sessionId)

        val spec = RenderSpec(
            fps = intent.getIntExtra(EXTRA_FPS, 30),
            longEdgePx = intent.getIntExtra(EXTRA_LONG_EDGE, 1920),
            // Always x264: the MediaCodec encoder cannot run from a standalone binary,
            // which has no JavaVM for ffmpeg's JNI bridge. See Encoder.HARDWARE.
            encoder = Encoder.X264,
            deflicker = intent.getBooleanExtra(EXTRA_DEFLICKER, false),
            customCommand = intent.getStringExtra(EXTRA_COMMAND).orEmpty(),
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
            app.renderState.progress(percent, progress.frame)
            notify(
                if (percent != null) "Rendering ${percent}% (frame ${progress.frame})"
                else "Rendering frame ${progress.frame}",
                percent,
            )
        }

        when (result) {
            is RenderResult.Success -> {
                val published = publish(output)
                app.renderState.success(published.uri, "Rendered ${manifest.name}")
                finish("Rendered to ${published.location} in ${result.elapsedMs / 1000}s")
            }

            is RenderResult.Failure -> {
                // The output is a truncated file that would only confuse the gallery.
                runCatching { if (output.exists()) output.delete() }
                result.log.takeLast(6).forEach { app.log.message("  ffmpeg: $it") }
                app.renderState.failed("Render failed: ${result.message}")
                finish("Render failed: ${result.message}")
            }

            RenderResult.Unavailable -> {
                app.renderState.failed("ffmpeg binary is missing from this build")
                finish("ffmpeg binary is missing from this build")
            }
        }
    }

    private suspend fun buildArgv(
        manifest: SessionManifest,
        spec: RenderSpec,
        output: File,
    ): List<String>? {
        if (spec.usesCustomCommand) {
            // Used verbatim apart from the destination, which the app substitutes so the
            // result lands somewhere it can then publish.
            val argv = FfmpegCommandBuilder.withOutput(
                FfmpegCommandBuilder.parseCommand(spec.customCommand),
                output.absolutePath,
            )
            app.log.message("Using your edited command")
            return argv.takeIf { it.size > 1 }
        }

        if (manifest.isRenderable) {
            return FfmpegCommandBuilder.fromPattern(manifest, spec, output.absolutePath)
        }
        // Frames kept the camera's own names, so they are fed through a list naming exactly
        // this session's files - nothing else sharing the folder gets pulled in.
        val export = app.sessionExporter.export(manifest, spec, output.absolutePath) ?: return null
        return FfmpegCommandBuilder.fromConcatList(export.concatListPath, spec, output.absolutePath)
    }

    /**
     * Where the finished video goes.
     *
     * Movies/OpenTimelapse when All-files access allows it, so the video lands somewhere
     * the gallery and a file manager can actually reach. App-specific external storage was
     * the previous choice and was effectively a black hole: Android 11+ hides
     * Android/data from file managers and MediaStore will not index it, so renders
     * reported success and then could not be found.
     */
    private fun outputFile(manifest: SessionManifest): File {
        if (app.storage.canRenameForeignFiles()) {
            val public = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "OpenTimelapse",
            )
            if (public.isDirectory || public.mkdirs()) return File(public, "${manifest.name}.mp4")
        }
        // Fallback: render privately, then copy into MediaStore so it is still reachable.
        val fallback = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        if (!fallback.exists()) fallback.mkdirs()
        return File(fallback, "${manifest.name}.mp4")
    }

    /** A published video: where it landed (for the log) and a URI to open it (for "open when rendered"). */
    private data class Published(val location: String, val uri: String?)

    /**
     * Makes the video visible.
     *
     * A file written directly to Movies/ only needs a scan. One left in app-specific
     * storage has to be copied into MediaStore, because nothing else can see it. Either way
     * the resulting MediaStore URI is captured, so the app can open the video afterwards.
     */
    private suspend fun publish(output: File): Published {
        val isPrivate = output.absolutePath.contains("/Android/data/")
        if (!isPrivate) {
            val uri = scanForUri(output.absolutePath)
            return Published(output.absolutePath, uri?.toString())
        }

        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, output.name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/OpenTimelapse",
                )
            }
        }
        val uri = runCatching {
            contentResolver.insert(
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values,
            )
        }.getOrNull() ?: return Published(output.absolutePath, null)

        return runCatching {
            contentResolver.openOutputStream(uri)?.use { sink ->
                output.inputStream().use { source -> source.copyTo(sink) }
            }
            output.delete()
            Published("Movies/OpenTimelapse/${output.name}", uri.toString())
        }.getOrDefault(Published(output.absolutePath, uri.toString()))
    }

    /** Scans a file into MediaStore and returns the URI its callback hands back. */
    private suspend fun scanForUri(path: String): android.net.Uri? =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            runCatching {
                android.media.MediaScannerConnection.scanFile(
                    this,
                    arrayOf(path),
                    arrayOf("video/mp4"),
                ) { _, uri -> if (cont.isActive) cont.resumeWith(Result.success(uri)) }
            }.onFailure { if (cont.isActive) cont.resumeWith(Result.success(null)) }
        }

    private fun isCharging(): Boolean =
        getSystemService(BatteryManager::class.java)?.isCharging == true

    private fun finish(message: String) {
        app.log.message(message)
        notify(message, null)
        stopSelfSafely()
    }

    private fun stopSelfSafely() {
        // REMOVE, not DETACH: the render notification is ongoing, so detaching left an
        // undismissable "Rendered to..." stuck in the shade forever. The result is now shown
        // in the app (progress, and opening the video), so the notification's job is done.
        stopForeground(STOP_FOREGROUND_REMOVE)
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
        const val EXTRA_COMMAND = "command"

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
                .putExtra(EXTRA_COMMAND, spec.customCommand)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
