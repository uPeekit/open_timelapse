package org.peekit.opentimelapse

import android.util.Log

/**
 * Mirrors the app's log to logcat under one tag, so a running session can be watched over adb
 * without the UI. The in-app log (with its own ring buffer) lives in
 * [org.peekit.opentimelapse.data.LogRepository]; this is only the logcat side of it.
 */
internal object Logcat {
    const val TAG = "OpenTimelapse"

    fun i(message: String) = Log.i(TAG, message)
}
