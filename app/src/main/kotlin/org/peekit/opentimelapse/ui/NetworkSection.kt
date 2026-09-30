package org.peekit.opentimelapse.ui

import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.security.SecureRandom
import org.peekit.opentimelapse.core.model.TimelapseConfig
import org.peekit.opentimelapse.net.LocalNetwork
import org.peekit.opentimelapse.net.QrBitmap

/**
 * Local network control: a switch, a generated token, and the QR to pair a laptop.
 *
 * Off by default, and the switch is the only thing visible until it is turned on. Enabling it
 * mints a token; the QR encodes the pairing URL with the token in it, so a laptop scans once.
 * The server itself only runs while a session is running - this screen just configures it.
 */
@Composable
internal fun NetworkSection(
    config: TimelapseConfig,
    onChange: ((TimelapseConfig) -> TimelapseConfig) -> Unit,
) {
    val net = config.network

    CollapsibleCard(title = "Control over wi-fi") {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Check and stop from a laptop")
                Text(
                    "Serves a small page on your wi-fi while a session runs - status, a " +
                        "live preview, and a Stop button. Protected by a token; off unless " +
                        "you turn it on.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            LimeSwitch(
                checked = net.enabled,
                onCheckedChange = { on ->
                    onChange {
                        // Turning on mints a token if there is not one already; turning off
                        // keeps it so re-enabling does not invalidate a paired laptop.
                        val token = if (on && it.network.token.isBlank()) newToken() else it.network.token
                        it.copy(network = it.network.copy(enabled = on, token = token))
                    }
                },
            )
        }

        if (net.enabled && net.token.isNotBlank()) {
            val ip = remember { LocalNetwork.ipv4Address() }
            val url = ip?.let { LocalNetwork.pairingUrl(it, net.port, net.token) }

            if (url != null) {
                val qr = remember(url) { QrBitmap.render(url) }
                qr?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Pairing QR code",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp),
                    )
                }
                Text("Scan this, or open:", style = MaterialTheme.typography.bodySmall)
                Text(
                    url,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            } else {
                Text(
                    "Join a wi-fi network to get a pairing address.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Text(
                "The token is your key - anyone with it on your network can watch the " +
                    "preview and stop the shoot. It travels as plain http on your wi-fi, " +
                    "so treat the network itself as trusted.",
                style = MaterialTheme.typography.bodySmall,
            )

            // For when a token leaks, or a QR was photographed: a fresh token unpairs
            // every laptop at once. Only offered when there is a token to replace.
            OutlinedButton(
                onClick = { onChange { it.copy(network = it.network.copy(token = newToken())) } },
            ) {
                Text("Regenerate token")
            }
        }
    }
}

/** A URL-safe random token, generated on the device with a cryptographic source. */
private fun newToken(): String {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)
    return Base64.encodeToString(
        bytes,
        Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
    )
}
