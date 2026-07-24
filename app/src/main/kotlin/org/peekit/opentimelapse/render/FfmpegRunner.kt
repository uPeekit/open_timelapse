package org.peekit.opentimelapse.render

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.peekit.opentimelapse.core.render.FfmpegProgressParser
import org.peekit.opentimelapse.core.render.RenderProgress
import kotlin.coroutines.coroutineContext

sealed interface RenderResult {
    data class Success(val outputPath: String, val elapsedMs: Long) : RenderResult
    data class Failure(val message: String, val log: List<String>) : RenderResult
    data object Unavailable : RenderResult
}

/**
 * Runs the bundled ffmpeg executable.
 *
 * The binary ships as `jniLibs/<abi>/libffmpeg.so` because since Android 10 an app may only
 * exec files from its native library directory - which is read-only and populated from the
 * APK at install time. Reading it from nativeLibraryDir also means the installer has already
 * picked the right ABI for us.
 */
class FfmpegRunner(private val context: Context) {

    fun binary(): File? =
        File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
            .takeIf { it.exists() && it.canExecute() }

    val isAvailable: Boolean get() = binary() != null

    /**
     * Renders and reports progress. Cancelling the calling coroutine kills the process -
     * a half-written mp4 is left behind for the caller to delete.
     */
    suspend fun render(
        argv: List<String>,
        onProgress: (RenderProgress) -> Unit,
    ): RenderResult = withContext(Dispatchers.IO) {
        val binary = binary() ?: return@withContext RenderResult.Unavailable

        val command = buildList {
            add(binary.absolutePath)
            add("-hide_banner")
            // Never wait on a terminal that does not exist.
            add("-nostdin")
            // Machine-readable progress on stdout; the human status line stays on stderr.
            add("-progress"); add("pipe:1")
            addAll(argv)
        }

        val startedAt = System.currentTimeMillis()
        val process = ProcessBuilder(command).start()
        val log = ArrayDeque<String>()

        try {
            val stderrReader = CoroutineScope(coroutineContext).launch {
                runCatching {
                    process.errorStream.bufferedReader().forEachLine { line ->
                        synchronized(log) {
                            log.addLast(line)
                            // Only the tail matters: ffmpeg's failure reason is always last.
                            while (log.size > MAX_LOG_LINES) log.removeFirst()
                        }
                    }
                }
            }

            val parser = FfmpegProgressParser()
            process.inputStream.bufferedReader().forEachLine { line ->
                // Cancellation cannot interrupt a blocking read, so it is checked here and
                // the process is destroyed in the finally below.
                coroutineContext.ensureActive()
                parser.onLine(line)?.let(onProgress)
            }

            val exit = process.waitFor()
            stderrReader.cancel()
            val tail = synchronized(log) { log.toList() }

            if (exit == 0) {
                RenderResult.Success(argv.last(), System.currentTimeMillis() - startedAt)
            } else {
                RenderResult.Failure(
                    message = tail.lastOrNull { it.isNotBlank() } ?: "ffmpeg exited with $exit",
                    log = tail,
                )
            }
        } finally {
            if (process.isAlive) process.destroy()
        }
    }

    private companion object {
        const val BINARY_NAME = "libffmpeg.so"
        const val MAX_LOG_LINES = 40
    }
}
