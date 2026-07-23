package org.peekit.opentimelapse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
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
import org.peekit.opentimelapse.core.model.SessionManifest
import org.peekit.opentimelapse.data.RenderPhase
import org.peekit.opentimelapse.data.RenderState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class SessionActions(
    val onRender: (SessionManifest) -> Unit,
    val onCopyCommand: (SessionManifest) -> Unit,
    val onDelete: (SessionManifest) -> Unit,
    val onDeletePhotos: (SessionManifest) -> Unit,
)

@Composable
fun SessionsSection(
    sessions: List<SessionManifest>,
    render: RenderState,
    openAfterRender: Boolean,
    onOpenAfterRenderChange: (Boolean) -> Unit,
    actions: SessionActions,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Sessions", style = MaterialTheme.typography.titleMedium)

            if (sessions.isEmpty()) {
                Text("No sessions yet. Shoot one and it appears here.", style = MaterialTheme.typography.bodySmall)
                return@Column
            }

            // One switch for all renders: check the result at a glance without hunting for
            // the file.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = openAfterRender, onCheckedChange = onOpenAfterRenderChange)
                Text("Open the video when a render finishes", style = MaterialTheme.typography.bodyMedium)
            }

            sessions.forEach { session ->
                SessionRow(session, render, actions)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SessionRow(session: SessionManifest, render: RenderState, actions: SessionActions) {
    var confirmDeletePhotos by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(session.name, style = MaterialTheme.typography.bodyLarge)
        Text(summarise(session), style = MaterialTheme.typography.bodySmall)

        // Progress for the render of *this* session, so feedback sits under its own row.
        if (render.sessionId == session.id && render.phase == RenderPhase.RUNNING) {
            val percent = render.percent
            if (percent != null) {
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Rendering $percent%", style = MaterialTheme.typography.bodySmall)
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Rendering frame ${render.frame}", style = MaterialTheme.typography.bodySmall)
            }
        }

        val renderable = session.frameCount > 0
        val rendering = render.sessionId == session.id && render.phase == RenderPhase.RUNNING
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { actions.onRender(session) }, enabled = renderable && !rendering) {
                Text("Render")
            }
            OutlinedButton(onClick = { actions.onCopyCommand(session) }, enabled = renderable) {
                Text("Copy ffmpeg")
            }
            OutlinedButton(onClick = { actions.onDelete(session) }) {
                Text("Delete")
            }
            OutlinedButton(onClick = { confirmDeletePhotos = true }) {
                Text("Delete photos")
            }
        }
    }

    if (confirmDeletePhotos) {
        AlertDialog(
            onDismissRequest = { confirmDeletePhotos = false },
            title = { Text("Delete photos?") },
            text = {
                Text(
                    if (session.naming.enabled) {
                        "Permanently deletes the ${session.frameCount} photos in this session's " +
                            "folder, and the session itself. This cannot be undone."
                    } else {
                        "These frames are the originals in your camera roll. Deleting removes " +
                            "the ${session.frameCount} photos permanently, and the session. This " +
                            "cannot be undone."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeletePhotos = false
                    actions.onDeletePhotos(session)
                }) { Text("Delete photos") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeletePhotos = false }) { Text("Cancel") }
            },
        )
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
