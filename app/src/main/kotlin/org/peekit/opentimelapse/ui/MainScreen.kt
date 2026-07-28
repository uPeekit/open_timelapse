package org.peekit.opentimelapse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

        SetupSection(checks, config, actions)
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
 * Setup and calibration in one collapsible card. It takes a lot of vertical space and is only
 * interesting until everything is green, so once it is all OK it folds to a single "Setup - OK"
 * line; anything still needing attention keeps it open. Calibration lives here as one more item
 * rather than its own card, since it is just another thing to get right before the first shoot.
 */
@Composable
private fun SetupSection(checks: List<SetupCheck>, config: TimelapseConfig, actions: MainActions) {
    val cal = config.calibration
    val stale = cal.completed && cal.cameraPackage != config.shutter.packageName
    val calibrated = cal.completed && !stale
    // "settled" = done, or deliberately skipped: either way it should not keep the card open.
    val calSettled = calibrated || cal.declined

    val allOk = checks.all { it.satisfied } && calSettled
    // Follows the state: collapses when everything is green, re-opens if something regresses -
    // while still letting the user tap to peek either way.
    var expanded by remember(allOk) { mutableStateOf(!allOk) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (allOk) "Setup - OK" else "Setup",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(if (expanded) "▲" else "▼", style = MaterialTheme.typography.titleMedium)
            }

            if (expanded) {
                checks.forEach { check ->
                    SetupItem(
                        mark = if (check.satisfied) "OK  " else if (check.required) "!!  " else "--  ",
                        title = check.title,
                        detail = check.detail,
                        action = if (!check.satisfied && check.fix != null) {
                            { OutlinedButton(onClick = { actions.onFix(check) }) { Text("Fix") } }
                        } else {
                            null
                        },
                    )
                }

                SetupItem(
                    mark = if (calibrated) "OK  " else if (cal.declined) "--  " else "!!  ",
                    title = "Calibration",
                    detail = when {
                        stale -> "Calibrated for another camera - recalibrate for ${config.shutter.packageName}."
                        calibrated -> "Done. Shortest safe interval on this phone: ${cal.minIntervalSeconds}s."
                        cal.declined -> "Skipped - the defaults may drop frames on this device."
                        else -> "Measures how long this phone's screen and camera take, by shooting a " +
                            "few frames (about a minute; the photos stay in your camera roll)."
                    },
                    action = if (!calibrated) {
                        {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = actions.onCalibrate) { Text("Calibrate") }
                                if (!cal.completed && !cal.declined) {
                                    OutlinedButton(onClick = actions.onDeclineCalibration) { Text("Later") }
                                }
                            }
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun SetupItem(mark: String, title: String, detail: String, action: (@Composable () -> Unit)?) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(mark + title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        action?.invoke()
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
                    Text("Test shot")
                }
            }
        }
    }
}
