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
import org.peekit.opentimelapse.Logcat

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

    /**
     * When frames are being renamed, the session takes the user's prefix - so the folder and
     * the rendered video are `oppo/`, `oppo.mp4`, not an opaque `TL_20260723_120908`. Without
     * naming there is no meaningful name to use, so it falls back to the timestamp.
     *
     * Made unique either way: two `oppo` sessions cannot share a folder, because their frames
     * both start at `oppo00000001.jpg` and would collide.
     */
    fun newSessionName(config: TimelapseConfig): String {
        val base = config.naming
            .takeIf { it.enabled && it.prefix.isNotBlank() }
            ?.prefix
            ?: ("TL_" + STAMP.format(Date()))
        return uniqueName(base)
    }

    private fun uniqueName(base: String): String {
        if (!isTaken(base)) return base
        var n = 2
        while (isTaken("${base}_$n")) n++
        return "${base}_$n"
    }

    private fun isTaken(name: String): Boolean =
        folderFor(name).exists() || File(privateDir, "$name.json").exists()

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
            .onFailure { Logcat.i("could not record session ${manifest.id}: ${it.message}") }

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

    /**
     * Deletes the session AND its photos - a deliberately separate, destructive action from
     * [delete], which only forgets the record. For a renamed session that is a self-contained
     * folder; for one that kept the camera's own names, these are the originals in the camera
     * roll, so the UI must confirm before calling this.
     *
     * Needs All-files access to remove files the camera app owns; a direct file delete is used
     * where the path is known, falling back to MediaStore for a bare content:// entry.
     */
    suspend fun deletePhotos(manifest: SessionManifest) = withContext(Dispatchers.IO) {
        manifest.framePaths.forEach { path ->
            runCatching {
                if (path.startsWith("content://")) {
                    context.contentResolver.delete(android.net.Uri.parse(path), null, null)
                } else {
                    File(path).delete()
                }
            }
        }
        runCatching { folderFor(manifest.id).deleteRecursively() }
        delete(manifest.id)
    }

    private companion object {
        const val MANIFEST_NAME = "manifest.json"
        val STAMP = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
}
