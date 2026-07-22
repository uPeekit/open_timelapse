package org.peekit.opentimelapse.storage

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.peekit.opentimelapse.core.model.SessionManifest
import org.peekit.opentimelapse.core.model.TimelapseConfig
import org.peekit.opentimelapse.spike.SpikeLog

/**
 * Records what was shot.
 *
 * The authoritative copy lives in app-private storage, because scoped storage refuses
 * non-media files in DCIM: writing manifest.json there fails silently without All-files
 * access, which was observed producing sessions that looked recorded but were not.
 *
 * A copy is also placed beside the frames when permissions allow, so that copying the
 * folder to a PC carries everything needed to render it. That copy is a convenience -
 * losing it never loses the session.
 */
class SessionStore(
    private val context: Context,
    private val storage: StorageAccess,
) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val privateDir: File
        get() = File(context.filesDir, "sessions").apply { if (!exists()) mkdirs() }

    fun folderFor(name: String): File = File(storage.sessionsRoot(), name)

    fun newSessionName(): String = "TL_" + STAMP.format(Date())

    fun create(name: String, config: TimelapseConfig, startedAtMs: Long): SessionManifest =
        SessionManifest(
            id = name,
            name = name,
            folderPath = folderFor(name).absolutePath,
            cameraPackage = config.shutter.packageName,
            intervalSeconds = config.intervalSeconds,
            startedAtMs = startedAtMs,
            naming = config.naming,
            firstIndex = config.naming.startIndex,
        )

    /**
     * Written every few frames as well as at the end, so a flat battery or a crash still
     * leaves a renderable session rather than an orphaned pile of images.
     */
    suspend fun save(manifest: SessionManifest) = withContext(Dispatchers.IO) {
        val document = json.encodeToString(manifest)

        runCatching { File(privateDir, "${manifest.id}.json").writeText(document) }
            .onFailure { SpikeLog.log("could not record session ${manifest.id}: ${it.message}") }

        // Best effort: needs All-files access, and its absence must not fail the session.
        runCatching {
            val folder = File(manifest.folderPath)
            if (folder.isDirectory || folder.mkdirs()) {
                File(folder, MANIFEST_NAME).writeText(document)
            }
        }
        Unit
    }

    /**
     * Sessions from app storage, plus any found beside their frames.
     *
     * The second source matters because uninstalling wipes app-private storage: without it
     * a reinstall - switching from a GitHub build to an F-Droid one, say - would leave the
     * photos on disk but orphaned, with nothing able to recognise them as sessions again.
     * The copy written next to the frames is what makes a reinstall lossless.
     */
    suspend fun loadAll(): List<SessionManifest> = withContext(Dispatchers.IO) {
        val private = privateDir.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "json" }
            .mapNotNull { decode(it) }

        val beside = storage.sessionsRoot().listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { folder -> decode(File(folder, MANIFEST_NAME)) }

        // App storage wins on conflict - it is flushed during a session, so it is the
        // fresher copy. associateBy keeps the last entry, hence private comes second.
        (beside + private)
            .associateBy { it.id }
            .values
            .sortedByDescending { it.startedAtMs }
    }

    private fun decode(file: File): SessionManifest? =
        if (!file.isFile) null
        else runCatching { json.decodeFromString<SessionManifest>(file.readText()) }.getOrNull()

    /**
     * Forgets a session.
     *
     * Both manifests must go: deleting only the private copy left the one beside the frames,
     * which recovery then restored on the next refresh - the row simply reappeared.
     *
     * The frames themselves are never touched. They are the user's photos, and an app that
     * quietly deletes a folder of images because a list row was dismissed would be wrong.
     */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        runCatching { File(privateDir, "$id.json").delete() }

        val folder = folderFor(id)
        runCatching { File(folder, MANIFEST_NAME).delete() }
        // Remove the folder only when nothing of the user's is left in it.
        runCatching { if (folder.isDirectory && folder.list().isNullOrEmpty()) folder.delete() }
        Unit
    }

    private companion object {
        const val MANIFEST_NAME = "manifest.json"
        val STAMP = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
}
