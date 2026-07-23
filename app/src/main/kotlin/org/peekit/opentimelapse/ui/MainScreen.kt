package org.peekit.opentimelapse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.peekit.opentimelapse.core.engine.ChargingAction
import org.peekit.opentimelapse.core.model.EndMode
import org.peekit.opentimelapse.core.model.StartTrigger
import org.peekit.opentimelapse.core.model.StopTime
import org.peekit.opentimelapse.core.render.FfmpegCommandBuilder
import org.peekit.opentimelapse.core.render.RenderSpec
import org.peekit.opentimelapse.core.model.CycleMode
import org.peekit.opentimelapse.core.model.TimelapseConfig
import org.peekit.opentimelapse.data.LogEntry

data class MainActions(
    val onStart: () -> Unit,
    val onStop: () -> Unit,
    val onSingleCycle: () -> Unit,
    val onFix: (SetupCheck) -> Unit,
    val onConfigChange: ((TimelapseConfig) -> TimelapseConfig) -> Unit,
    val sessionActions: SessionActions,
    val onOpenLicenses: () -> Unit,
    val onCalibrate: () -> Unit,
    val onDeclineCalibration: () -> Unit,
    val onTestWebhook: (ChargingAction) -> Unit,
)

@Composable
fun MainScreen(
    config: TimelapseConfig,
    checks: List<SetupCheck>,
    log: List<LogEntry>,
    sessions: List<org.peekit.opentimelapse.core.model.SessionManifest>,
    actions: MainActions,
    modifier: Modifier = Modifier,
) {
    val blocking = SetupChecks.blocking(checks)

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("OpenTimelapse", style = MaterialTheme.typography.headlineSmall)

        CalibrationSection(config, actions)
        SetupSection(checks, actions.onFix)
        ControlsSection(blocking.isEmpty(), actions)
        SettingsSection(config, actions.onConfigChange)
        PowerSection(config, actions.onConfigChange, actions.onTestWebhook)
        NetworkSection(config, actions.onConfigChange)
        SessionsSection(sessions, actions.sessionActions)
        RenderCommandSection(config, actions.onConfigChange)
        LogSection(log)

        androidx.compose.material3.TextButton(onClick = actions.onOpenLicenses) {
            Text("Open source licences")
        }
    }
}

/**
 * Offered before the first run rather than done silently: it shoots a few real frames,
 * which takes about a minute and is a surprise if the user is trying to catch something
 * happening right now.
 */
@Composable
private fun CalibrationSection(config: TimelapseConfig, actions: MainActions) {
    val state = config.calibration
    val stale = state.completed && state.cameraPackage != config.shutter.packageName

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Calibration", style = MaterialTheme.typography.titleMedium)

            when {
                stale -> {
                    Text(
                        "Calibrated for a different camera app. Timings do not carry over - " +
                            "run it again for ${config.shutter.packageName}.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = actions.onCalibrate) { Text("Calibrate again") }
                }

                state.completed -> Text(
                    "Done. Shortest safe interval on this phone: ${state.minIntervalSeconds}s.",
                    style = MaterialTheme.typography.bodySmall,
                )

                else -> {
                    Text(
                        "Every phone is different - how long the screen takes to accept a tap, " +
                            "how long the camera takes to save a photo. Calibration measures " +
                            "yours by shooting a few frames. It takes about a minute and the " +
                            "photos are left in your camera roll.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (state.declined) {
                        Text(
                            "Skipped - the defaults may drop frames on this device.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = actions.onCalibrate) { Text("Calibrate now") }
                        if (!state.declined) {
                            OutlinedButton(onClick = actions.onDeclineCalibration) { Text("Later") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupSection(checks: List<SetupCheck>, onFix: (SetupCheck) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Setup", style = MaterialTheme.typography.titleMedium)

            checks.forEach { check ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = (if (check.satisfied) "OK  " else if (check.required) "!!  " else "--  ") + check.title,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(check.detail, style = MaterialTheme.typography.bodySmall)
                    }
                    if (!check.satisfied && check.fix != null) {
                        OutlinedButton(onClick = { onFix(check) }) { Text("Fix") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlsSection(canStart: Boolean, actions: MainActions) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Controls", style = MaterialTheme.typography.titleMedium)

            if (!canStart) {
                Text(
                    "Finish the required setup items above before starting.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "Start opens your camera and waits, so you can pick the mode - Pro, RAW, " +
                    "Night, whatever. The app never changes it; it just presses the shutter.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.onStart, enabled = canStart) { Text("Start") }
                OutlinedButton(onClick = actions.onStop) { Text("Stop") }
                OutlinedButton(onClick = actions.onSingleCycle, enabled = canStart) {
                    Text("One frame")
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
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

        EndMode.AT_TIME -> {
            val stored = config.session.endAtEpochMs
            val (hour, minute) = if (stored > 0) {
                StopTime.hourAndMinute(stored)
            } else {
                6 to 0
            }
            BoundTextField(
                value = "%02d:%02d".format(hour, minute),
                onValueChange = { typed ->
                    val parts = typed.split(":")
                    val h = parts.getOrNull(0)?.trim()?.toIntOrNull()
                    val m = parts.getOrNull(1)?.trim()?.toIntOrNull()
                    if (h != null && m != null) {
                        // Stored as an instant: a time already past today means tomorrow.
                        val at = StopTime.nextOccurrence(h, m, System.currentTimeMillis())
                        onChange { it.copy(session = it.session.copy(endAtEpochMs = at)) }
                    }
                },
                label = "Stop at (HH:MM)",
                modifier = Modifier.fillMaxWidth(),
            )
        }

        EndMode.MANUAL -> Unit
    }
}

/**
 * A text field that owns what it shows.
 *
 * Binding `value` straight to the config loses keystrokes: each one is written to DataStore
 * and read back through a flow, so the next keystroke is typed against the previous value
 * and overwrites it. Measured on a real device at four characters a second -
 * "http://127.0.0.1:8099/on" arrived as ".nt:2.00:801o".
 *
 * So the field keeps its own text and only follows the config until the user first types.
 * After that it is the source of truth, which is what the user already believes.
 */
@Composable
private fun BoundTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
) {
    var text by remember { mutableStateOf(value) }
    var edited by remember { mutableStateOf(false) }

    // Config loads asynchronously, so an untouched field must still pick up the stored value.
    if (!edited && value != text) text = value

    OutlinedTextField(
        value = text,
        onValueChange = {
            edited = true
            text = it
            onValueChange(it)
        },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = supportingText?.let { { Text(it) } },
        isError = isError,
        singleLine = true,
        modifier = modifier,
    )
}

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
private fun PowerSection(
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

                BoundTextField(
                    value = charging.startChargingUrl,
                    onValueChange = { typed ->
                        onChange { it.copy(charging = it.charging.copy(startChargingUrl = typed.trim())) }
                    },
                    label = "URL to switch the plug on",
                    placeholder = "http://192.168.1.50/relay/0?turn=on",
                    modifier = Modifier.fillMaxWidth(),
                )

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
                        value = charging.method,
                        onValueChange = { typed ->
                            onChange { it.copy(charging = it.charging.copy(method = typed.uppercase().trim())) }
                        },
                        label = "Method",
                        modifier = Modifier.weight(1f),
                    )
                    BoundTextField(
                        value = charging.body,
                        onValueChange = { typed ->
                            onChange { it.copy(charging = it.charging.copy(body = typed)) }
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

/**
 * Local network control: a switch, a generated token, and the QR to pair a laptop.
 *
 * Off by default, and the switch is the only thing visible until it is turned on. Enabling it
 * mints a token; the QR encodes the pairing URL with the token in it, so a laptop scans once.
 * The server itself only runs while a session is running - this screen just configures it.
 */
@Composable
private fun NetworkSection(
    config: TimelapseConfig,
    onChange: ((TimelapseConfig) -> TimelapseConfig) -> Unit,
) {
    val net = config.network

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Control over wi-fi", style = MaterialTheme.typography.titleMedium)

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Check and stop from a laptop")
                    Text(
                        "Serves a small page on your wi-fi while a session runs - status, a " +
                            "live preview, and a Stop button. Protected by a token; off unless " +
                            "you turn it on.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = net.enabled,
                    onCheckedChange = { on ->
                        onChange {
                            // Turning on mints a token if there is not one already; turning off
                            // keeps it so re-enabling does not invalidate a paired laptop.
                            val token = if (on && it.network.token.isBlank()) newToken() else it.network.token
                            it.copy(network = it.network.copy(enabled = on, token = token))
                        }
                    },
                )
            }

            if (net.enabled && net.token.isNotBlank()) {
                val ip = remember { org.peekit.opentimelapse.net.LocalNetwork.ipv4Address() }
                val url = ip?.let {
                    org.peekit.opentimelapse.net.LocalNetwork.pairingUrl(it, net.port, net.token)
                }

                if (url != null) {
                    val qr = remember(url) { org.peekit.opentimelapse.net.QrBitmap.render(url) }
                    qr?.let {
                        androidx.compose.foundation.Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "Pairing QR code",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp),
                        )
                    }
                    Text("Scan this, or open:", style = MaterialTheme.typography.bodySmall)
                    Text(
                        url,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                } else {
                    Text(
                        "Join a wi-fi network to get a pairing address.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Text(
                    "The token is your key - anyone with it on your network can watch the " +
                        "preview and stop the shoot.",
                    style = MaterialTheme.typography.bodySmall,
                )

                // For when a token leaks, or a QR was photographed: a fresh token unpairs
                // every laptop at once. Only offered when there is a token to replace.
                OutlinedButton(
                    onClick = { onChange { it.copy(network = it.network.copy(token = newToken())) } },
                ) {
                    Text("Regenerate token")
                }
            }
        }
    }
}

/** A URL-safe random token, generated on the device with a cryptographic source. */
private fun newToken(): String {
    val bytes = ByteArray(16)
    java.security.SecureRandom().nextBytes(bytes)
    return android.util.Base64.encodeToString(
        bytes,
        android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
    )
}

/**
 * The ffmpeg command, shown and editable.
 *
 * Bundling ffmpeg rather than MediaCodec was a bet on flexibility, and until now none of it
 * was reachable - no deflicker, no different CRF, no crop. The generated command is the
 * baseline and stays in charge until someone deliberately edits it.
 */
@Composable
private fun RenderCommandSection(
    config: TimelapseConfig,
    onChange: ((TimelapseConfig) -> TimelapseConfig) -> Unit,
) {
    val generated = remember {
        FfmpegCommandBuilder.asShellCommand(
            FfmpegCommandBuilder.fromConcatList("<frames>", RenderSpec(), "<output>")
        )
    }
    val custom = config.customRenderCommand
    val editing = custom.isNotBlank()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Render command", style = MaterialTheme.typography.titleMedium)
            Text(
                if (editing) {
                    "Using your command. The output path is still filled in by the app."
                } else {
                    "Generated from the settings above. Edit it to take control - deflicker, " +
                        "a different quality, a crop."
                },
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedTextField(
                value = if (editing) custom else generated,
                onValueChange = { typed ->
                    onChange { it.copy(customRenderCommand = typed) }
                },
                label = { Text(if (editing) "Your command" else "Generated (edit to override)") },
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                ),
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            if (editing) {
                OutlinedButton(onClick = { onChange { it.copy(customRenderCommand = "") } }) {
                    Text("Reset to generated")
                }
            }
        }
    }
}

@Composable
private fun LogSection(log: List<LogEntry>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Log", style = MaterialTheme.typography.titleMedium)

            if (log.isEmpty()) {
                Text("Nothing yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(log.asReversed()) { entry ->
                        Text(
                            text = entry.format(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = when (entry.ok) {
                                false -> Color(0xFFB3261E)
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
    }
}
