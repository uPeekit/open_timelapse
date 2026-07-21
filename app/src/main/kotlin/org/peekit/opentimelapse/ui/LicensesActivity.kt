package org.peekit.opentimelapse.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * In-app open-source notice. Required, not optional: release builds bundle a GPL ffmpeg
 * binary, and the GPL obliges the distributor to make the licence and the offer of source
 * visible to the user.
 */
class LicensesActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Scaffold { padding ->
                    Column(
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(padding)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Open source licences", style = MaterialTheme.typography.headlineSmall)
                        Text(NOTICE, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    private companion object {
        val NOTICE = """
            OpenTimelapse is licensed under Apache-2.0.

            This build bundles an ffmpeg executable to render timelapses on the device.
            It is built with libx264, which makes that binary GPL-2.0-or-later.

            ffmpeg - https://ffmpeg.org - GPL-2.0-or-later (as built)
            x264   - https://www.videolan.org/developers/x264.html - GPL-2.0-or-later

            The exact upstream versions and the script that reproduces the binary are in the
            project's tools/build-ffmpeg.sh, and the full licence texts are in its LICENSES
            directory. Contact the distributor of this build for the corresponding source.
        """.trimIndent()
    }
}
