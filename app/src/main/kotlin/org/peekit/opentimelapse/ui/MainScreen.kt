package org.peekit.opentimelapse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.peekit.opentimelapse.core.engine.ChargingAction
import org.peekit.opentimelapse.core.model.SessionManifest
import org.peekit.opentimelapse.core.model.TimelapseConfig
import org.peekit.opentimelapse.data.LogEntry
import org.peekit.opentimelapse.data.RenderState

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
    val onShareLog: () -> Unit,
)

@Composable
fun MainScreen(
    config: TimelapseConfig,
    checks: List<SetupCheck>,
    log: List<LogEntry>,
    sessions: List<SessionManifest>,
    render: RenderState,
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
        SessionsSection(
            sessions = sessions,
            render = render,
            openAfterRender = config.openVideoAfterRender,
            onOpenAfterRenderChange = { on ->
                actions.onConfigChange { it.copy(openVideoAfterRender = on) }
            },
            actions = actions.sessionActions,
        )
        RenderCommandSection(config, actions.onConfigChange)
        LogSection(log, actions.onShareLog)

        TextButton(onClick = actions.onOpenLicenses) {
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
