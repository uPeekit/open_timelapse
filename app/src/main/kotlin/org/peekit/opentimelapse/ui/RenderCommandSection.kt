package org.peekit.opentimelapse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.peekit.opentimelapse.core.model.TimelapseConfig
import org.peekit.opentimelapse.core.render.FfmpegCommandBuilder
import org.peekit.opentimelapse.core.render.RenderSpec

/**
 * The ffmpeg command, shown and editable.
 *
 * Bundling ffmpeg rather than MediaCodec was a bet on flexibility, and until now none of it
 * was reachable - no deflicker, no different CRF, no crop. The generated command is the
 * baseline and stays in charge until someone deliberately edits it.
 */
@Composable
internal fun RenderCommandSection(
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
                textStyle = TextStyle(
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
