package org.peekit.opentimelapse.spike

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 0 diagnostics. Everything goes to logcat under one tag so results can be
 * collected over adb without touching the phone.
 */
object SpikeLog {

    const val TAG = "OTLSPIKE"

    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val entries = ArrayDeque<String>()
    private val listeners = mutableListOf<(String) -> Unit>()

    @Synchronized
    fun log(message: String) {
        val line = "[${stamp.format(Date())}] $message"
        entries.addLast(line)
        while (entries.size > 300) entries.removeFirst()
        Log.i(TAG, message)
        listeners.toList().forEach { it(line) }
    }

    fun result(probe: String, ok: Boolean, detail: String) {
        log("RESULT $probe = ${if (ok) "PASS" else "FAIL"} :: $detail")
    }

    @Synchronized
    fun snapshot(): String = entries.joinToString("\n")

    @Synchronized
    fun addListener(listener: (String) -> Unit) {
        listeners += listener
    }

    @Synchronized
    fun removeListener(listener: (String) -> Unit) {
        listeners -= listener
    }
}
