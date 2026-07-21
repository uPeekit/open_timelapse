package org.peekit.opentimelapse.storage

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.peekit.opentimelapse.core.engine.CapturedMedia
import org.peekit.opentimelapse.core.engine.FrameFileResult
import org.peekit.opentimelapse.core.engine.FrameNamer
import org.peekit.opentimelapse.core.engine.FrameStore
import org.peekit.opentimelapse.core.model.NamingConfig

/**
 * Moves each captured frame into the session folder under a sequential name.
 *
 * A move on the same volume is a rename syscall, so this costs nothing even for a 100MB RAW
 * pair - the file is never copied. All files from one shutter press share an index and keep
 * their own extensions, so a JPEG and its DNG stay together.
 */
class FileFrameStore(
    private val context: Context,
    private val sessionFolder: File,
    naming: NamingConfig,
    private val storage: StorageAccess = StorageAccess(context),
) : FrameStore {

    private val namer = FrameNamer(naming)

    override suspend fun fileFrame(media: List<CapturedMedia>, index: Int): FrameFileResult =
        withContext(Dispatchers.IO) {
            if (media.isEmpty()) {
                return@withContext FrameFileResult(false, detail = "nothing was captured to file")
            }
            if (!storage.canRenameForeignFiles()) {
                return@withContext FrameFileResult(
                    false,
                    detail = "All-files access is needed to rename the camera's photos",
                )
            }
            if (!sessionFolder.exists() && !sessionFolder.mkdirs()) {
                return@withContext FrameFileResult(false, detail = "could not create $sessionFolder")
            }

            val moves = mutableListOf<Move>()
            for (item in media) {
                val source = resolvePath(item.uri)
                    ?: return@withContext rollback(moves, "could not resolve a path for ${item.displayName}")

                val target = File(sessionFolder, namer.fileName(index, item.displayName))
                if (target.exists()) {
                    return@withContext rollback(moves, "${target.name} already exists")
                }
                if (!source.renameTo(target)) {
                    return@withContext rollback(moves, "could not move ${source.name}")
                }
                moves += Move(source, target, item.uri)
            }

            // Scan both ends: the old path so its stale row is dropped, the new one so the
            // gallery finds the frame where it now lives.
            //
            // Deliberately NOT contentResolver.delete() on the old row. That row still
            // references the same inode after a rename, so deleting it deletes the frame
            // we just moved - observed destroying a captured frame that the log had
            // already reported as filed successfully.
            scan(moves.flatMap { listOf(it.source, it.target) })

            FrameFileResult(ok = true, paths = moves.map { it.target.absolutePath })
        }

    /**
     * Undoes a partly-filed frame.
     *
     * Necessary, not defensive: the engine does not consume the index on failure, so a
     * stranded file would collide with the retry and fail that frame forever.
     */
    private fun rollback(moves: List<Move>, reason: String): FrameFileResult {
        moves.forEach { runCatching { it.target.renameTo(it.source) } }
        return FrameFileResult(false, detail = reason)
    }

    private fun resolvePath(uri: String): File? {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return null
        @Suppress("DEPRECATION")
        val projection = arrayOf(MediaStore.MediaColumns.DATA)
        return runCatching {
            context.contentResolver.query(parsed, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                cursor.getString(0)?.let(::File)?.takeIf { it.exists() }
            }
        }.getOrNull()
    }

    private fun scan(files: List<File>) {
        runCatching {
            MediaScannerConnection.scanFile(
                context,
                files.map { it.absolutePath }.toTypedArray(),
                null,
                null,
            )
        }
    }

    private data class Move(val source: File, val target: File, val sourceUri: String)
}
