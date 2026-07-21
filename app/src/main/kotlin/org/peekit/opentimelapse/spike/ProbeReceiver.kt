package org.peekit.opentimelapse.spike

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Lets probes be triggered over adb, so a run is reproducible and needs nobody
 * tapping the screen at the right moment:
 *
 *   adb shell am broadcast -a org.peekit.opentimelapse.PROBE -p org.peekit.opentimelapse \
 *       --es which bal-service --el delay 20000
 */
class ProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val which = intent.getStringExtra(SpikeService.EXTRA_WHICH)
        if (which == null) {
            SpikeLog.log("PROBE broadcast with no 'which' extra")
            return
        }
        val delayMs = intent.getLongExtra(SpikeService.EXTRA_DELAY, 0L)

        val service = SpikeService.instance
        if (service != null) {
            service.runProbe(which, delayMs, intent.extras)
        } else {
            // Starting it from here would give the probe a freshly-started service's
            // privileges, which is not the state we want to measure.
            SpikeLog.log("SpikeService is not running - open the app once first")
        }
    }
}
