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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

        SetupSection(checks, actions.onFix)
        ControlsSection(blocking.isEmpty(), actions)
        SettingsSection(config, actions.onConfigChange)
        SessionsSection(sessions, actions.sessionActions)
        LogSection(log)

        androidx.compose.material3.TextButton(onClick = actions.onOpenLicenses) {
            Text("Open source licences")
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

            OutlinedTextField(
                value = config.intervalSeconds.toString(),
                onValueChange = { typed ->
                    typed.toIntOrNull()?.let { seconds ->
                        onChange { it.copy(intervalSeconds = seconds.coerceAtLeast(1)) }
                    }
                },
                label = { Text("Seconds between frames") },
                singleLine = true,
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
                OutlinedTextField(
                    value = config.naming.prefix,
                    onValueChange = { typed ->
                        onChange { it.copy(naming = it.naming.copy(prefix = typed.filter { c -> c.isLetterOrDigit() || c == '_' })) }
                    },
                    label = { Text("Name prefix") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
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
