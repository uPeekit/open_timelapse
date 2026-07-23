package org.peekit.opentimelapse.net

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The phone's address on the LAN, for building the pairing URL.
 *
 * Picks the first non-loopback IPv4 address on an up interface. On a phone shooting on a
 * windowsill that is the wifi address, which is what a laptop on the same network needs.
 */
object LocalNetwork {

    fun ipv4Address(): String? =
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrNull()

    /** The URL a laptop opens, token embedded so the QR is a scan-and-go. */
    fun pairingUrl(ip: String, port: Int, token: String): String =
        "http://$ip:$port/?token=$token"
}
