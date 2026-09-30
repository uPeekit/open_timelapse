package org.peekit.opentimelapse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.peekit.opentimelapse.BuildConfig

/**
 * Where the app points outside itself. The values are set per store flavor in
 * app/build.gradle.kts, so a changed donation page or a moved repository is a one-line edit
 * there, not a hunt through the UI. A blank one hides its row.
 */
object AboutLinks {
    /** Blank in the play flavor, which is closed-source. */
    const val SOURCE = BuildConfig.SOURCE_URL
    const val ISSUES = BuildConfig.ISSUES_URL

    /**
     * Donation page. GitHub Sponsors for now because the project already lives there; a
     * Ko-fi or Liberapay page works just as well. Blank in the play flavor: Google Play does
     * not allow a donation link that goes around its own billing.
     */
    const val DONATE = BuildConfig.DONATE_URL
}

/**
 * Version, licence, links and the coffee button, folded away at the very bottom: none of it
 * is needed on a shoot, but all of it should be findable without leaving the app.
 */
@Composable
internal fun AboutSection(
    version: String,
    onOpenUrl: (String) -> Unit,
    onOpenLicenses: () -> Unit,
) {
    CollapsibleCard(title = "About") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("OpenTimelapse $version", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Shoots timelapses by driving your phone's own camera app. " +
                    (if (AboutLinks.SOURCE.isNotBlank()) "Free, open source (Apache-2.0), no" else "No") +
                    " ads, no tracking. The bundled ffmpeg is GPL.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Column {
            if (AboutLinks.SOURCE.isNotBlank()) {
                LinkRow("Source code on GitHub") { onOpenUrl(AboutLinks.SOURCE) }
            }
            if (AboutLinks.ISSUES.isNotBlank()) {
                LinkRow("Report a problem") { onOpenUrl(AboutLinks.ISSUES) }
            }
            LinkRow("Open source licences", onOpenLicenses)
        }

        if (AboutLinks.DONATE.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The app is free and stays that way. If it earned its keep on a shoot, " +
                        "a coffee helps keep it going.",
                    style = MaterialTheme.typography.bodySmall,
                )
                FilledTonalButton(
                    onClick = { onOpenUrl(AboutLinks.DONATE) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("☕  Buy me a coffee")
                }
            }
        }
    }
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        TextButton(onClick = onClick) { Text(label) }
    }
}
