package org.peekit.opentimelapse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.peekit.opentimelapse.core.model.SessionManifest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class SessionActions(
    val onRenderFast: (SessionManifest) -> Unit,
    val onRenderArchival: (SessionManifest) -> Unit,
    val onCopyCommand: (SessionManifest) -> Unit,
    val onDelete: (SessionManifest) -> Unit,
)

@Composable
fun SessionsSection(sessions: List<SessionManifest>, actions: SessionActions) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Sessions", style = MaterialTheme.typography.titleMedium)

            if (sessions.isEmpty()) {
                Text("No sessions yet. Shoot one and it appears here.", style = MaterialTheme.typography.bodySmall)
                return@Column
            }

            sessions.forEach { session ->
                SessionRow(session, actions)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SessionRow(session: SessionManifest, actions: SessionActions) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(session.name, style = MaterialTheme.typography.bodyLarge)
        Text(summarise(session), style = MaterialTheme.typography.bodySmall)

        val renderable = session.frameCount > 0
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { actions.onRenderFast(session) }, enabled = renderable) {
                Text("Render")
            }
            OutlinedButton(onClick = { actions.onRenderArchival(session) }, enabled = renderable) {
                Text("Archival")
            }
            OutlinedButton(onClick = { actions.onCopyCommand(session) }, enabled = renderable) {
                Text("Copy ffmpeg")
            }
            OutlinedButton(onClick = { actions.onDelete(session) }) {
                Text("Delete")
            }
        }
    }
}

private fun summarise(session: SessionManifest): String {
    val frames = "${session.frameCount} frame${if (session.frameCount == 1) "" else "s"}"
    val naming = if (session.naming.enabled) "named ${session.naming.prefix}" else "original names"
    val started = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(session.startedAtMs))
    val length = session.endedAtMs?.let { end ->
        val seconds = TimeUnit.MILLISECONDS.toSeconds(end - session.startedAtMs)
        " over ${seconds / 60}m ${seconds % 60}s"
    } ?: ""
    return "$frames, every ${session.intervalSeconds}s, $naming\n$started$length"
}
