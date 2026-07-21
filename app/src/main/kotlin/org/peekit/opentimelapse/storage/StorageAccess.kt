package org.peekit.opentimelapse.storage

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * Whether we may rename files the camera app owns.
 *
 * On Android 11+ this needs All-files access. The scoped-storage alternative,
 * MediaStore.createWriteRequest, shows a system consent dialog *per file* - impossible in an
 * unattended loop that files a frame every few seconds. So the permission is requested only
 * when renaming is switched on, and the app works without it otherwise.
 */
class StorageAccess(private val context: Context) {

    fun canRenameForeignFiles(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /** Settings screen where the user grants it; there is no runtime-permission dialog. */
    fun requestIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }

    /** Sessions live under DCIM so the gallery and any cloud sync pick them up. */
    fun sessionsRoot(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "OpenTimelapse")
}
