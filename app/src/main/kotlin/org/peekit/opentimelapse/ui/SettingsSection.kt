package org.peekit.opentimelapse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale
import org.peekit.opentimelapse.core.model.CycleMode
import org.peekit.opentimelapse.core.model.EndMode
import org.peekit.opentimelapse.core.model.StartTrigger
import org.peekit.opentimelapse.core.model.StopTime
import org.peekit.opentimelapse.core.model.TimelapseConfig

@Composable
internal fun SettingsSection(
    config: TimelapseConfig,
    onChange: ((TimelapseConfig) -> TimelapseConfig) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Settings", style = MaterialTheme.typography.titleMedium)

            Text("Starting", style = MaterialTheme.typography.titleSmall)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Begin on my first photo")
                    Text(
                        if (config.session.startTrigger == StartTrigger.FIRST_MANUAL_SHOT) {
                            "Set the camera up, take one shot, and that becomes frame 1."
                        } else {
                            "Waits a fixed time after Start instead."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = config.session.startTrigger == StartTrigger.FIRST_MANUAL_SHOT,
                    onCheckedChange = { manual ->
                        onChange {
                            it.copy(
                                session = it.session.copy(
                                    startTrigger = if (manual) StartTrigger.FIRST_MANUAL_SHOT else StartTrigger.TIMER,
                                ),
                            )
                        }
                    },
                )
            }

            if (config.session.startTrigger == StartTrigger.TIMER) {
                BoundTextField(
                    value = config.session.startDelaySeconds.toString(),
                    onValueChange = { typed ->
                        typed.toIntOrNull()?.let { seconds ->
                            onChange { it.copy(session = it.session.copy(startDelaySeconds = seconds.coerceIn(0, 600))) }
                        }
                    },
                    label = "Seconds to set up the camera",
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            HorizontalDivider()
            StopConditionSettings(config, onChange)
            HorizontalDivider()

            val minimum = config.calibration.minIntervalSeconds
            val tooShort = minimum > 0 && config.intervalSeconds < minimum

            BoundTextField(
                value = config.intervalSeconds.toString(),
                onValueChange = { typed ->
                    typed.toIntOrNull()?.let { seconds ->
                        onChange { it.copy(intervalSeconds = seconds.coerceAtLeast(1)) }
                    }
                },
                label = "Seconds between frames",
                isError = tooShort,
                // A cycle that overruns its slot drops the frame outright, so this is a
                // guarantee of loss rather than a matter of taste.
                supportingText = if (tooShort) {
                    "A cycle takes about ${minimum}s on this phone. Below that, frames will be dropped."
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Lock between frames")
                    Text(
                        if (config.mode == CycleMode.LOCK_CYCLE) {
                            "Saves battery. Needs a Swipe lock screen."
                        } else {
                            "Awake mode: camera stays up. Uses more battery, fewer moving parts."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = config.mode == CycleMode.LOCK_CYCLE,
                    onCheckedChange = { locked ->
                        onChange {
                            it.copy(mode = if (locked) CycleMode.LOCK_CYCLE else CycleMode.AWAKE)
                        }
                    },
                )
            }

            HorizontalDivider()

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Rename frames")
                    Text(
                        "Moves each frame into a session folder as " +
                            "${config.naming.prefix}${"0".repeat(config.naming.padWidth - 1)}1.jpg, " +
                            "ready for ffmpeg.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = config.naming.enabled,
                    onCheckedChange = { enabled ->
                        onChange { it.copy(naming = it.naming.copy(enabled = enabled)) }
                    },
                )
            }

            if (config.naming.enabled) {
                BoundTextField(
                    value = config.naming.prefix,
                    onValueChange = { typed ->
                        onChange { it.copy(naming = it.naming.copy(prefix = typed.filter { c -> c.isLetterOrDigit() || c == '_' })) }
                    },
                    label = "Name prefix",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * When the session ends on its own.
 *
 * Worth having rather than relying on Stop: an overnight shoot should not depend on
 * someone being awake to end it, and a phone left running past sunrise wastes battery and
 * storage on frames nobody wants.
 */
@Composable
private fun StopConditionSettings(
    config: TimelapseConfig,
    onChange: ((TimelapseConfig) -> TimelapseConfig) -> Unit,
) {
    Text("Stopping", style = MaterialTheme.typography.titleSmall)

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EndMode.entries.forEach { mode ->
            val selected = config.session.endMode == mode
            val label = when (mode) {
                EndMode.MANUAL -> "When I stop"
                EndMode.AFTER_DURATION -> "After a while"
                EndMode.AT_TIME -> "At a time"
            }
            if (selected) {
                Button(onClick = {}, modifier = Modifier.weight(1f)) { Text(label, maxLines = 2) }
            } else {
                OutlinedButton(
                    onClick = { onChange { it.copy(session = it.session.copy(endMode = mode)) } },
                    modifier = Modifier.weight(1f),
                ) { Text(label, maxLines = 2) }
            }
        }
    }

    when (config.session.endMode) {
        EndMode.AFTER_DURATION -> BoundTextField(
            value = config.session.durationMinutes.toString(),
            onValueChange = { typed ->
                typed.toIntOrNull()?.let { minutes ->
                    onChange { it.copy(session = it.session.copy(durationMinutes = minutes.coerceIn(1, 10_000))) }
                }
            },
            label = "Minutes",
            modifier = Modifier.fillMaxWidth(),
        )

        EndMode.AT_TIME -> StopAtDateTime(config.session.endAtEpochMs) { at ->
            onChange { it.copy(session = it.session.copy(endAtEpochMs = at)) }
        }

        EndMode.MANUAL -> Unit
    }
}

/**
 * A date *and* time to stop, not just a time of day: a time alone caps the shoot at the next
 * 24 hours, but a windowsill timelapse can run for days. Native pickers - date, then time -
 * because a keyboard date is fiddly and easy to enter wrong.
 */
@Composable
private fun StopAtDateTime(storedMs: Long, onPicked: (Long) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // Seed the pickers from the stored instant, or from tomorrow morning on a blank field.
    val seed = java.util.Calendar.getInstance().apply {
        if (storedMs > 0) timeInMillis = storedMs else { add(java.util.Calendar.DAY_OF_YEAR, 1); set(java.util.Calendar.HOUR_OF_DAY, 6); set(java.util.Calendar.MINUTE, 0) }
    }

    val label = remember(storedMs) {
        if (storedMs > 0) {
            java.text.SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(java.util.Date(storedMs))
        } else {
            "Pick a date and time"
        }
    }

    OutlinedButton(
        onClick = {
            android.app.DatePickerDialog(
                context,
                { _, year, month0, day ->
                    android.app.TimePickerDialog(
                        context,
                        { _, hour, minute ->
                            onPicked(StopTime.atDateTime(year, month0 + 1, day, hour, minute))
                        },
                        seed.get(java.util.Calendar.HOUR_OF_DAY),
                        seed.get(java.util.Calendar.MINUTE),
                        true,
                    ).show()
                },
                seed.get(java.util.Calendar.YEAR),
                seed.get(java.util.Calendar.MONTH),
                seed.get(java.util.Calendar.DAY_OF_MONTH),
            ).apply { datePicker.minDate = System.currentTimeMillis() }.show()
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Stop at: $label") }
}
