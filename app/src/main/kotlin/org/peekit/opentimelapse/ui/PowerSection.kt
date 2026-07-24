package org.peekit.opentimelapse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.peekit.opentimelapse.core.engine.ChargingAction
import org.peekit.opentimelapse.core.model.TimelapseConfig

/**
 * Battery floor and charging webhooks.
 *
 * A phone that shoots until it dies loses the session and the last frames with it, and a
 * phone left on the charger for a week-long shoot swells its battery. Both are solved by
 * the same thing: knowing the charge level and being able to act on it.
 *
 * Off by default. It sends a request to a URL the user typed, and nothing else.
 */
@Composable
internal fun PowerSection(
    config: TimelapseConfig,
    onChange: ((TimelapseConfig) -> TimelapseConfig) -> Unit,
    onTest: (ChargingAction) -> Unit,
) {
    val charging = config.charging

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Power", style = MaterialTheme.typography.titleMedium)

            BoundTextField(
                value = config.session.stopBelowBatteryPercent.toString(),
                onValueChange = { typed ->
                    typed.toIntOrNull()?.let { percent ->
                        onChange {
                            it.copy(session = it.session.copy(stopBelowBatteryPercent = percent.coerceIn(0, 95)))
                        }
                    }
                },
                label = "Stop below battery %",
                supportingText = "0 to keep shooting until the phone dies. Ignored while charging.",
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider()

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Control a smart plug")
                    Text(
                        "Calls a URL when the battery gets low or full, so a long shoot can " +
                            "charge itself without sitting at 100% for days. Only while a " +
                            "session is running.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = charging.enabled,
                    onCheckedChange = { on ->
                        onChange { it.copy(charging = it.charging.copy(enabled = on)) }
                    },
                )
            }

            if (charging.enabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BoundTextField(
                        value = charging.lowPercent.toString(),
                        onValueChange = { typed ->
                            typed.toIntOrNull()?.let { percent ->
                                onChange { it.copy(charging = it.charging.copy(lowPercent = percent.coerceIn(1, 99))) }
                            }
                        },
                        label = "Charge below %",
                        modifier = Modifier.weight(1f),
                    )
                    BoundTextField(
                        value = charging.highPercent.toString(),
                        onValueChange = { typed ->
                            typed.toIntOrNull()?.let { percent ->
                                onChange { it.copy(charging = it.charging.copy(highPercent = percent.coerceIn(2, 100))) }
                            }
                        },
                        label = "Stop above %",
                        modifier = Modifier.weight(1f),
                    )
                }

                // Each direction gets its own URL, method and body: the on and off endpoints
                // are often shaped differently (a Shelly toggles with two GETs, Home Assistant
                // wants a POST with a payload that differs per direction).
                Text("When charge is needed", style = MaterialTheme.typography.titleSmall)
                BoundTextField(
                    value = charging.startChargingUrl,
                    onValueChange = { typed ->
                        onChange { it.copy(charging = it.charging.copy(startChargingUrl = typed.trim())) }
                    },
                    label = "URL to switch the plug on",
                    placeholder = "http://192.168.1.50/relay/0?turn=on",
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BoundTextField(
                        value = charging.startMethod,
                        onValueChange = { typed ->
                            onChange { it.copy(charging = it.charging.copy(startMethod = typed.uppercase().trim())) }
                        },
                        label = "Method",
                        modifier = Modifier.weight(1f),
                    )
                    BoundTextField(
                        value = charging.startBody,
                        onValueChange = { typed ->
                            onChange { it.copy(charging = it.charging.copy(startBody = typed)) }
                        },
                        label = "Body (optional)",
                        modifier = Modifier.weight(2f),
                    )
                }

                Text("When charge is full", style = MaterialTheme.typography.titleSmall)
                BoundTextField(
                    value = charging.stopChargingUrl,
                    onValueChange = { typed ->
                        onChange { it.copy(charging = it.charging.copy(stopChargingUrl = typed.trim())) }
                    },
                    label = "URL to switch the plug off",
                    placeholder = "http://192.168.1.50/relay/0?turn=off",
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BoundTextField(
                        value = charging.stopMethod,
                        onValueChange = { typed ->
                            onChange { it.copy(charging = it.charging.copy(stopMethod = typed.uppercase().trim())) }
                        },
                        label = "Method",
                        modifier = Modifier.weight(1f),
                    )
                    BoundTextField(
                        value = charging.stopBody,
                        onValueChange = { typed ->
                            onChange { it.copy(charging = it.charging.copy(stopBody = typed)) }
                        },
                        label = "Body (optional)",
                        modifier = Modifier.weight(2f),
                    )
                }

                // Testing this against a real battery would mean waiting for it to drain to
                // 40%, so the buttons fire the request now and report the result in the log.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onTest(ChargingAction.START_CHARGING) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Test on") }
                    OutlinedButton(
                        onClick = { onTest(ChargingAction.STOP_CHARGING) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Test off") }
                }
                Text(
                    "Results appear in the log below.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
