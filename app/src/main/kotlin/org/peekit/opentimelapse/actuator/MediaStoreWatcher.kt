package org.peekit.opentimelapse.actuator

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.delay
import org.peekit.opentimelapse.core.engine.CapturedMedia

/**
 * Confirms a capture by watching for the file the camera app actually wrote.
 *
 * This is the only signal in the cycle that cannot lie: a gesture callback may report
 * success for a press that did nothing, or failure for one that worked, but a new row in
 * MediaStore means a photo exists.
 */
class MediaStoreWatcher(private val context: Context) {

    /**
     * Waits for media written after [sinceMs], then keeps collecting for [quietMs] after the
     * first arrival so a RAW sibling is grouped with its JPEG - one shutter press produces
     * two files in RAW mode and they must share a frame index.
     */
    suspend fun awaitNewMedia(sinceMs: Long, timeoutMs: Long, quietMs: Long): List<CapturedMedia> {
        val resolver = context.contentResolver
        val deadline = System.currentTimeMillis() + timeoutMs

        // Polled rather than observed: a ContentObserver still requires a query to learn
        // what changed, and the poll doubles as the "is it finished writing" check.
        var found = query(resolver, sinceMs)
        while (found.isEmpty() && System.currentTimeMillis() < deadline) {
            delay(POLL_MS)
            found = query(resolver, sinceMs)
        }
        if (found.isEmpty()) return emptyList()

        // Let a RAW sibling land, then re-read so both files share one frame index.
        delay(quietMs)
        return query(resolver, sinceMs).ifEmpty { found }
    }

    private fun query(resolver: ContentResolver, sinceMs: Long): List<CapturedMedia> {
        // DATE_ADDED is in seconds; floor the boundary so a file written in the same second
        // as the shutter press is not missed.
        val sinceSeconds = sinceMs / 1000

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            @Suppress("DEPRECATION") MediaStore.MediaColumns.DATA,
        )
        // Files, not Images: some OEMs register DNG under a different media type, and a
        // frame that landed as a "file" still counts as a captured frame.
        val selection = "${MediaStore.MediaColumns.DATE_ADDED} >= ? AND " +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? AND " +
            "${MediaStore.MediaColumns.IS_PENDING} = 0"
        val args = arrayOf(
            sinceSeconds.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
        )

        val out = mutableListOf<CapturedMedia>()
        runCatching {
            resolver.query(
                CONTENT_URI,
                projection,
                selection,
                args,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                @Suppress("DEPRECATION")
                val pathColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    // A zero-length row is a file still being written.
                    val size = cursor.getLong(sizeColumn)
                    if (size <= 0L) continue

                    out += CapturedMedia(
                        uri = Uri.withAppendedPath(CONTENT_URI, id.toString()).toString(),
                        path = if (pathColumn >= 0) cursor.getString(pathColumn) else null,
                        displayName = cursor.getString(nameColumn) ?: "unknown",
                        mimeType = cursor.getString(mimeColumn) ?: "application/octet-stream",
                        sizeBytes = size,
                        addedAtMs = cursor.getLong(addedColumn) * 1000,
                    )
                }
            }
        }
        return out
    }

    private companion object {
        val CONTENT_URI: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        const val POLL_MS = 150L
    }
}
