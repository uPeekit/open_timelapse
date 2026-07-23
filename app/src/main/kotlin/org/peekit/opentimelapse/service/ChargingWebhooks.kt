package org.peekit.opentimelapse.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.peekit.opentimelapse.core.engine.BatteryReading
import org.peekit.opentimelapse.core.engine.ChargingAction
import org.peekit.opentimelapse.core.engine.ChargingThresholds
import org.peekit.opentimelapse.core.model.ChargingConfig
import org.peekit.opentimelapse.data.LogRepository

/**
 * Switches a smart socket by calling a URL when the battery crosses a threshold.
 *
 * Lives only as long as a session: registered when one starts, unregistered when it ends.
 * That keeps a battery watcher from running permanently and costs nothing between shoots.
 *
 * Every failure is logged and swallowed. A socket that did not switch is a nuisance; a
 * session that stopped because of it would be a lost timelapse.
 */
class ChargingWebhooks(
    private val context: Context,
    private val log: LogRepository,
    private val scope: CoroutineScope,
) {

    private var receiver: BroadcastReceiver? = null
    private var thresholds: ChargingThresholds? = null

    fun start(config: ChargingConfig) {
        if (!config.enabled) return
        stop()

        val rules = ChargingThresholds(config)
        thresholds = rules

        val listener = object : BroadcastReceiver() {
            override fun onReceive(receivedContext: Context?, intent: Intent?) {
                val reading = intent?.toReading() ?: return
                rules.onReading(reading)?.let { action -> fire(action, rules, config, reading) }
            }
        }
        receiver = listener

        // Sticky broadcast rather than polling: the system already publishes every change.
        runCatching {
            context.registerReceiver(listener, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
        log.message("Charging control on: below ${config.lowPercent}% charge, above ${config.highPercent}% stop")
    }

    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        thresholds = null
    }

    /** Sends one webhook now, so a setup can be checked without draining a real battery. */
    fun test(config: ChargingConfig, action: ChargingAction) {
        val rules = ChargingThresholds(config)
        val url = rules.urlFor(action)
        if (url == null) {
            log.message("No URL configured for $action")
            return
        }
        scope.launch { send(url, config, action) }
    }

    private fun fire(
        action: ChargingAction,
        rules: ChargingThresholds,
        config: ChargingConfig,
        reading: BatteryReading,
    ) {
        val url = rules.urlFor(action) ?: run {
            log.message("$action at ${reading.percent}% but no URL is configured")
            return
        }
        log.message("Battery ${reading.percent}% -> $action")
        scope.launch { send(url, config, action) }
    }

    private suspend fun send(url: String, config: ChargingConfig, action: ChargingAction) {
        withContext(Dispatchers.IO) {
            val result = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                runCatching {
                    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = config.method.uppercase().takeIf { it in METHODS } ?: "POST"
                        connectTimeout = REQUEST_TIMEOUT_MS.toInt()
                        readTimeout = REQUEST_TIMEOUT_MS.toInt()
                        if (requestMethod != "GET" && config.body.isNotBlank()) {
                            doOutput = true
                            setRequestProperty("Content-Type", "application/json")
                            outputStream.use { it.write(config.body.toByteArray()) }
                        }
                    }
                    val code = connection.responseCode
                    connection.disconnect()
                    code
                }
            }

            when {
                result == null -> log.message("$action webhook timed out")
                result.isFailure -> log.message("$action webhook failed: ${result.exceptionOrNull()?.message}")
                else -> log.message("$action webhook returned ${result.getOrNull()}")
            }
        }
    }

    private fun Intent.toReading(): BatteryReading? {
        val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null

        val status = getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        return BatteryReading(percent = level * 100 / scale, charging = charging)
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 10_000L
        val METHODS = setOf("GET", "POST", "PUT")
    }
}
