package org.peekit.opentimelapse

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.WindowManager

/**
 * Wakes the screen and dismisses itself. Shows nothing.
 *
 * A blocked background launch does not throw, so [ScreenWaker][org.peekit.opentimelapse.actuator.ScreenWaker]
 * never trusts this alone: it polls the display state afterwards, and the logcat line here
 * is the evidence trail for when the poll says the screen stayed off.
 */
class WakeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        Logcat.i("WakeActivity.onCreate ran")
        finish()
    }
}
