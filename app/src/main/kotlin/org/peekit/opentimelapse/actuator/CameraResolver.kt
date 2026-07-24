package org.peekit.opentimelapse.actuator

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore

/**
 * Finds this device's camera app by asking who handles IMAGE_CAPTURE.
 *
 * This is what keeps the app device-agnostic: nothing is hardcoded, so a phone nobody has
 * ever tested works out of the box. Presets exist only to skip a calibration step.
 */
class CameraResolver(private val context: Context) {

    fun resolveDefault(): String? {
        val manager = context.packageManager
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)

        val preferred = manager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.packageName

        // "android" means the system returned the chooser because several apps qualify and
        // none is the default; fall back to enumerating them.
        if (preferred != null && preferred != CHOOSER) return preferred

        return manager.queryIntentActivities(intent, 0)
            .asSequence()
            .map { it.activityInfo.packageName }
            .firstOrNull { it != CHOOSER && it != context.packageName }
    }

    private companion object {
        const val CHOOSER = "android"
    }
}
