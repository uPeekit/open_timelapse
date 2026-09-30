package org.peekit.opentimelapse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.peekit.opentimelapse.core.engine.ChargingAction
import org.peekit.opentimelapse.core.model.SessionManifest
import org.peekit.opentimelapse.core.model.TimelapseConfig
import org.peekit.opentimelapse.data.LogEntry
import org.peekit.opentimelapse.data.RenderState
import org.peekit.opentimelapse.data.RunState
import org.peekit.opentimelapse.pro.ProState

data class MainActions(
    val onStart: () -> Unit,
    val onStop: () -> Unit,
    val onSingleCycle: () -> Unit,
    val onFix: (SetupCheck) -> Unit,
    val onConfigChange: ((TimelapseConfig) -> TimelapseConfig) -> Unit,
    val sessionActions: SessionActions,
    val onOpenLicenses: () -> Unit,
    val onOpenUrl: (String) -> Unit,
    val onCalibrate: () -> Unit,
    val onDeclineCalibration: () -> Unit,
    val onTestWebhook: (ChargingAction) -> Unit,
    val onShareLog: () -> Unit,
    val onUnlockPro: () -> Unit,
)

/**
 * Ordered by how often each part is touched: setup (until green) and the Shoot card on
 * top, results next, and the set-once extras folded away below.
 */
@Composable
fun MainScreen(
    config: TimelapseConfig,
    checks: List<SetupCheck>,
    log: List<LogEntry>,
    sessions: List<SessionManifest>,
    render: RenderState,
    runState: RunState,
    pro: ProState,
    actions: MainActions,
    version: String,
    modifier: Modifier = Modifier,
) {
    val blocking = SetupChecks.blocking(checks)
    val proGate = ProGate(pro, actions.onUnlockPro)

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "OpenTimelapse",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )

        SetupSection(checks, config, actions)
        ShootSection(config, runState, blocking.isEmpty(), actions, proGate)

        SessionsSection(
            sessions = sessions,
            render = render,
            openAfterRender = config.openVideoAfterRender,
            onOpenAfterRenderChange = { on ->
                actions.onConfigChange { it.copy(openVideoAfterRender = on) }
            },
            actions = actions.sessionActions,
        )

        PowerSection(config, actions.onConfigChange, actions.onTestWebhook, proGate)
        NetworkSection(config, actions.onConfigChange, proGate)
        RenderCommandSection(config, actions.onConfigChange, proGate)
        LogSection(log, actions.onShareLog)
        AboutSection(version, actions.onOpenUrl, actions.onOpenLicenses)
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
    // Asked every time rather than remembered: consent that outlives the service being
    // switched off again would send the user to Settings without the explanation.
    var disclosing by remember { mutableStateOf<SetupCheck?>(null) }

    disclosing?.let { check ->
        AlertDialog(
            onDismissRequest = { disclosing = null },
            title = { Text(check.title) },
            text = {
                Text(
                    check.disclosure.orEmpty(),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        disclosing = null
                        actions.onFix(check)
                    },
                ) { Text("Agree and continue") }
            },
            dismissButton = {
                TextButton(onClick = { disclosing = null }) { Text("No thanks") }
            },
        )
    }

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
                    color = if (allOk) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                )
                Text(if (expanded) "▲" else "▼", style = MaterialTheme.typography.titleMedium)
            }

            if (expanded) {
                checks.forEach { check ->
                    SetupItem(
                        mark = if (check.satisfied) "OK  " else if (check.required) "!!  " else "--  ",
                        markColor = markColor(satisfied = check.satisfied, required = check.required),
                        title = check.title,
                        detail = check.detail,
                        action = if (!check.satisfied && check.fix != null) {
                            {
                                OutlinedButton(
                                    onClick = {
                                        if (check.disclosure != null) disclosing = check else actions.onFix(check)
                                    },
                                ) { Text("Fix") }
                            }
                        } else {
                            null
                        },
                    )
                }

                SetupItem(
                    mark = if (calibrated) "OK  " else if (cal.declined) "--  " else "!!  ",
                    markColor = markColor(satisfied = calibrated, required = !cal.declined),
                    title = "Calibration",
                    detail = when {
                        stale -> "Calibrated for another camera - recalibrate for ${config.shutter.packageName}."
                        calibrated -> "Done. Shortest safe interval on this phone: ${cal.minIntervalSeconds}s."
                        cal.declined -> "Skipped - the defaults may drop frames on this device."
                        else -> "Measures how long this phone's screen and camera take, by shooting a " +
                            "few frames (about a minute; the photos stay in your camera roll)."
                    },
                    // Offered even once it is done: timings drift with a firmware update, and
                    // a calibration that has gone wrong was otherwise impossible to redo -
                    // the only way back was to switch camera app and make it go stale.
                    action = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = actions.onCalibrate) {
                                Text(if (calibrated) "Redo" else "Calibrate")
                            }
                            if (!cal.completed && !cal.declined) {
                                OutlinedButton(onClick = actions.onDeclineCalibration) { Text("Later") }
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun markColor(satisfied: Boolean, required: Boolean): Color = when {
    satisfied -> MaterialTheme.colorScheme.secondary
    required -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun SetupItem(
    mark: String,
    markColor: Color,
    title: String,
    detail: String,
    action: (@Composable () -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row {
                Text(mark, style = MaterialTheme.typography.bodyLarge, color = markColor)
                Text(title, style = MaterialTheme.typography.bodyLarge)
            }
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        action?.invoke()
    }
}
