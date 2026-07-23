package org.peekit.opentimelapse.core.engine

import org.peekit.opentimelapse.core.model.ChargingConfig

data class BatteryReading(
    val percent: Int,
    val charging: Boolean,
)

/** What the phone should be told to do about its charger. */
enum class ChargingAction { START_CHARGING, STOP_CHARGING }

/**
 * Decides when to ask for the charger to be switched.
 *
 * Edge-triggered, not level-triggered. `if (percent <= low) fire` would send on every
 * reading while the battery sits at 39%, hammering whatever is on the other end and
 * burning through any rate limit. A trigger fires once on crossing and re-arms only after
 * the opposite threshold is crossed.
 *
 * Charging state is part of the decision because level alone is ambiguous: a phone already
 * unplugged does not need telling to stop charging, and one already charging does not need
 * telling to start.
 */
class ChargingThresholds(private val config: ChargingConfig) {

    private var armedForLow = true
    private var armedForHigh = true

    fun onReading(reading: BatteryReading): ChargingAction? {
        if (!config.enabled) return null

        if (reading.percent >= config.highPercent && reading.charging) {
            if (!armedForHigh) return null
            armedForHigh = false
            armedForLow = true
            return ChargingAction.STOP_CHARGING
        }

        if (reading.percent <= config.lowPercent && !reading.charging) {
            if (!armedForLow) return null
            armedForLow = false
            armedForHigh = true
            return ChargingAction.START_CHARGING
        }

        return null
    }

    /** The URL for an action, or null when the user has not configured one. */
    fun urlFor(action: ChargingAction): String? = when (action) {
        ChargingAction.START_CHARGING -> config.startChargingUrl.takeIf { it.isNotBlank() }
        ChargingAction.STOP_CHARGING -> config.stopChargingUrl.takeIf { it.isNotBlank() }
    }
}
