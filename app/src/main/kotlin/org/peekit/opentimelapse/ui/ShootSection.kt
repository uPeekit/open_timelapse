package org.peekit.opentimelapse.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.delay
import org.peekit.opentimelapse.core.model.CycleMode
import org.peekit.opentimelapse.core.model.EndMode
import org.peekit.opentimelapse.core.model.StartTrigger
import org.peekit.opentimelapse.core.model.StopTime
import org.peekit.opentimelapse.core.model.TimelapseConfig
import org.peekit.opentimelapse.core.net.ServerState
import org.peekit.opentimelapse.data.RunState

/**
 * Everything a shoot needs, in one bright card: the Start/Stop toggle, the interval, and
 * when to stop. Options that are set once and rarely revisited fold away under
 * "More options" so they stop pushing the essentials off screen.
 */
@Composable
internal fun ShootSection(
    config: TimelapseConfig,
    runState: RunState,
    canStart: Boolean,
    actions: MainActions,
) {
    val running = runState.serverState == ServerState.RUNNING

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Shoot",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            if (!canStart) {
                Text(
                    "Finish the required setup items above before starting.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // One toggle, driven by the service's published state: the pair of blind
                // Start/Stop buttons predates the UI being able to see whether it runs.
                if (running) {
                    Button(onClick = actions.onStop, modifier = Modifier.weight(2f)) {
                        Text("■  Stop")
                    }
                } else {
                    Button(
                        onClick = actions.onStart,
                        enabled = canStart,
                        modifier = Modifier.weight(2f),
                        colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = OnLime),
                    ) { Text("▶  Start") }
                }
                OutlinedButton(
                    onClick = actions.onSingleCycle,
                    enabled = canStart && !running,
                    modifier = Modifier.weight(1f),
                ) { Text("Test") }
            }

            if (running) {
                RunStatusLine(runState)
            } else {
                Text(
                    "Start opens your camera and waits, so you can pick the mode - Pro, RAW, " +
                        "Night, whatever. The app never changes it; it just presses the shutter.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            IntervalField(config, actions.onConfigChange)
            StopConditionSettings(config, actions.onConfigChange)

            MoreOptions(config, actions.onConfigChange)
        }
    }
}

/** Frames so far and a live countdown to the next one - the reassurance a running shoot lacked. */
@Composable
private fun RunStatusLine(runState: RunState) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    val next = runState.nextFrameAtMs
    val countdown = if (next > now) " · next in ${(next - now) / 1000}s" else ""
    Text(
        "Shooting · ${runState.framesCaptured} frames$countdown",
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun IntervalField(
    config: TimelapseConfig,
    onChange: ((TimelapseConfig) -> TimelapseConfig) -> Unit,
) {
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
}

/** Set-once options, folded so the card stays the size of what a shoot actually needs. */
@Composable
private fun MoreOptions(
    config: TimelapseConfig,
    onChange: ((TimelapseConfig) -> TimelapseConfig) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Row(
        // Min 48dp: a text-height strip is too fiddly a touch target to hit reliably.
        Modifier.fillMaxWidth().clickable { expanded = !expanded }.heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "More options",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        Text(if (expanded) "▲" else "▼", style = MaterialTheme.typography.titleSmall)
    }

    if (!expanded) return

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
        LimeSwitch(
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
        LimeSwitch(
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
        LimeSwitch(
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
    Text(
        "Stop",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.secondary,
    )

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
