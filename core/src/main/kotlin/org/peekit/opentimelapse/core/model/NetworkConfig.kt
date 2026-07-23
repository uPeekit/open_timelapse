package org.peekit.opentimelapse.core.model

import kotlinx.serialization.Serializable

/**
 * The local control server: a small HTTP server in the foreground service so a phone left
 * shooting on a windowsill can be checked and stopped from a laptop on the same wifi.
 *
 * Off by default, and deliberately so. The app otherwise holds no network permission at all,
 * which is a real property worth keeping for something that drives your camera and reads your
 * photo library. Turning this on spends that, so it is never implicit.
 *
 * The [token] gates every endpoint - including the preview, which is camera output. It is
 * generated when the server is enabled and shown in-app as a QR code, so pairing a laptop is
 * a scan rather than a typed secret. An empty token means the server must not start: a port
 * with no token on shared wifi would let anyone stop a shoot or watch previews.
 */
@Serializable
data class NetworkConfig(
    val enabled: Boolean = false,
    /** URL-safe secret, filled when the server is enabled. Blank keeps the server off. */
    val token: String = "",
    val port: Int = 8787,
) {
    /** The server may run only when switched on *and* actually holding a secret to check. */
    val runnable: Boolean get() = enabled && token.isNotBlank()
}
