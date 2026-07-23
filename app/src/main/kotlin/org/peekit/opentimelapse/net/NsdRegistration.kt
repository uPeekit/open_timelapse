package org.peekit.opentimelapse.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import org.peekit.opentimelapse.data.LogRepository

/**
 * Advertises the control server over mDNS as `_opentimelapse._tcp`, so the phone is found by
 * name on the LAN rather than by hunting for its IP. Best-effort: if registration fails, the
 * server still works, the user just types the address.
 */
class NsdRegistration(
    context: Context,
    private val log: LogRepository,
) {

    private val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    fun register(port: Int) {
        unregister()

        val info = NsdServiceInfo().apply {
            serviceName = "OpenTimelapse"
            serviceType = "_opentimelapse._tcp"
            setPort(port)
        }

        val callback = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                log.message("Discoverable on the network as ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                log.message("mDNS registration failed ($errorCode); the address still works")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {}

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        listener = callback
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, callback) }
    }

    fun unregister() {
        listener?.let { runCatching { manager.unregisterService(it) } }
        listener = null
    }
}
