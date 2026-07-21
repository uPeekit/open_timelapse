package org.peekit.opentimelapse.storage

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.peekit.opentimelapse.core.model.SessionManifest
import org.peekit.opentimelapse.core.render.FfmpegCommandBuilder
import org.peekit.opentimelapse.core.render.RenderSpec

data class SessionExport(
    val concatListPath: String,
    val command: String,
)

/**
 * Turns a recorded session into something renderable.
 *
 * Always available, whatever the storage permissions: it writes a concat list naming
 * exactly the session's frames, plus the matching ffmpeg command. That solves the problem
 * renaming was really there for - isolating one shoot from every other photo on the phone -
 * without needing to move or rename anything.
 */
class SessionExporter(private val context: Context) {

    suspend fun export(
        manifest: SessionManifest,
        spec: RenderSpec,
        outputPath: String = defaultOutput(manifest),
    ): SessionExport? = withContext(Dispatchers.IO) {
        if (manifest.framePaths.isEmpty()) return@withContext null

        val listFile = File(exportDir(), "${manifest.id}.txt")
        runCatching {
            listFile.writeText(FfmpegCommandBuilder.concatList(manifest.framePaths, spec.fps))
        }.onFailure { return@withContext null }

        val argv = if (manifest.isRenderable) {
            // A renamed sequence reads faster and is what a desktop user expects to see.
            FfmpegCommandBuilder.fromPattern(manifest, spec, outputPath)
        } else {
            FfmpegCommandBuilder.fromConcatList(listFile.absolutePath, spec, outputPath)
        }

        SessionExport(
            concatListPath = listFile.absolutePath,
            command = FfmpegCommandBuilder.asShellCommand(argv),
        )
    }

    private fun exportDir(): File =
        File(context.filesDir, "exports").apply { if (!exists()) mkdirs() }

    private fun defaultOutput(manifest: SessionManifest): String =
        "${manifest.folderPath}/${manifest.name}.mp4"
}
