package org.peekit.opentimelapse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.peekit.opentimelapse.data.LogEntry

@Composable
internal fun LogSection(log: List<LogEntry>, onShareLog: () -> Unit) {
    CollapsibleCard(title = "Log") {
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
                            false -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }

        // Shares the durable file, which spans past sessions and any crash - not just
        // what happens to be on screen.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onShareLog) { Text("Share") }
        }
    }
}
