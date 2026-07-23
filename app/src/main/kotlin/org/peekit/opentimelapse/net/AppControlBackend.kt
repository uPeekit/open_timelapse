package org.peekit.opentimelapse.net

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.StatFs
import java.io.ByteArrayOutputStream
import java.io.File
import org.peekit.opentimelapse.actuator.AndroidDeviceActuator
import org.peekit.opentimelapse.core.net.ServerState
import org.peekit.opentimelapse.core.net.StatusSnapshot
import org.peekit.opentimelapse.data.RunStateRepository
import org.peekit.opentimelapse.service.TimelapseService

/**
 * Turns the app's live state into what the control server serves, and its control requests
 * into service intents. The server itself knows none of this - it moves bytes and checks
 * tokens; everything session-shaped lives here.
 */
class AppControlBackend(
    private val context: Context,
    private val actuator: AndroidDeviceActuator,
    private val runState: RunStateRepository,
) : ControlBackend {

    override fun status(): StatusSnapshot {
        val run = runState.state.value
        val battery = actuator.batteryNow()

        val nextInSeconds = run.nextFrameAtMs
            .takeIf { it > 0 }
            ?.let { ((it - System.currentTimeMillis()) / 1000).coerceAtLeast(0).toInt() }

        return StatusSnapshot(
            state = run.serverState,
            sessionName = run.sessionName,
            framesCaptured = run.framesCaptured,
            nextFrameInSeconds = nextInSeconds,
            intervalSeconds = run.intervalSeconds,
            batteryPercent = battery.percent,
            charging = battery.charging,
            freeStorageMb = freeStorageMb(),
            runningForMs = if (run.startedAtMs > 0) System.currentTimeMillis() - run.startedAtMs else 0,
        )
    }

    override fun previewJpeg(): ByteArray? {
        val path = runState.state.value.lastFramePath ?: return null
        return runCatching { downscaleToJpeg(path) }.getOrNull()
    }

    override fun requestStart() {
        TimelapseService.send(context, TimelapseService.ACTION_START)
    }

    override fun requestStop() {
        TimelapseService.send(context, TimelapseService.ACTION_STOP)
    }

    private fun freeStorageMb(): Long =
        runCatching {
            val stat = StatFs(context.getExternalFilesDir(null)?.path ?: context.filesDir.path)
            stat.availableBytes / (1024 * 1024)
        }.getOrDefault(0)

    /**
     * Reads the last frame at reduced resolution and re-encodes a small JPEG. Frames can be
     * 4000-px camera output; sending them whole over the LAN on every 2s poll would be wasteful
     * and slow, and a preview only needs to be legible.
     */
    private fun downscaleToJpeg(path: String): ByteArray? {
        val bytes: ByteArray? = when {
            path.startsWith("content://") ->
                context.contentResolver.openInputStream(android.net.Uri.parse(path))?.use { it.readBytes() }
            else -> File(path).takeIf { it.exists() }?.readBytes()
        }
        if (bytes == null) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, PREVIEW_MAX_PX)
        }
        val bitmap: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null

        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, PREVIEW_QUALITY, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    private fun sampleSizeFor(width: Int, target: Int): Int {
        var sample = 1
        var w = width
        while (w / 2 >= target) {
            w /= 2
            sample *= 2
        }
        return sample
    }

    private companion object {
        const val PREVIEW_MAX_PX = 640
        const val PREVIEW_QUALITY = 70
    }
}
