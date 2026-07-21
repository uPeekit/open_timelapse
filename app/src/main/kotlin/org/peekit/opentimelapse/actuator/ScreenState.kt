package org.peekit.opentimelapse.actuator

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/**
 * Whether the screen is genuinely usable.
 *
 * Deliberately not `PowerManager.isInteractive`: on Samsung that returns true for
 * Always-On Display, which is a DOZE_SUSPEND state showing only a clock and a charging
 * indicator. A gesture dispatched at it goes nowhere, and the cycle believed it had a
 * working screen. Only [Display.STATE_ON] means the display will accept touches.
 */
class ScreenState(private val context: Context) {

    fun isOn(): Boolean = displayState() == Display.STATE_ON

    fun isDozing(): Boolean = displayState().let {
        it == Display.STATE_DOZE || it == Display.STATE_DOZE_SUSPEND
    }

    fun describe(): String = when (displayState()) {
        Display.STATE_ON -> "on"
        Display.STATE_OFF -> "off"
        Display.STATE_DOZE -> "doze (always-on display)"
        Display.STATE_DOZE_SUSPEND -> "doze-suspend (always-on display)"
        else -> "unknown"
    }

    private fun displayState(): Int =
        context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?.state
            ?: Display.STATE_UNKNOWN
}
