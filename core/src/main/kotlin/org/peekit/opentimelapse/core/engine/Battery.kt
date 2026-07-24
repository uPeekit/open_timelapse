package org.peekit.opentimelapse.core.engine

import org.peekit.opentimelapse.core.model.ChargingConfig

data class BatteryReading(
    val percent: Int,
    val charging: Boolean,
)

/** What the phone should be told to do about its charger. */
enum class ChargingAction { START_CHARGING, STOP_CHARGING }

/** One resolved webhook: where to call, how, and with what. The Android side just sends it. */
data class WebhookCall(
    val url: String,
    val method: String,
    val body: String,
)

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
 *
 * The config is passed per reading, not held: the engine re-reads config every cycle, and
 * the charging rules must not lag behind on a snapshot taken at session start. Only the
 * hysteresis state lives here.
 */
class ChargingThresholds {

    private var armedForLow = true
    private var armedForHigh = true

    fun onReading(reading: BatteryReading, config: ChargingConfig): ChargingAction? {
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

    /** The call for an action, or null when the user has not configured a URL for it. */
    fun callFor(action: ChargingAction, config: ChargingConfig): WebhookCall? = when (action) {
        ChargingAction.START_CHARGING ->
            config.startChargingUrl.takeIf { it.isNotBlank() }
                ?.let { WebhookCall(it, config.startMethod, config.startBody) }

        ChargingAction.STOP_CHARGING ->
            config.stopChargingUrl.takeIf { it.isNotBlank() }
                ?.let { WebhookCall(it, config.stopMethod, config.stopBody) }
    }
}
