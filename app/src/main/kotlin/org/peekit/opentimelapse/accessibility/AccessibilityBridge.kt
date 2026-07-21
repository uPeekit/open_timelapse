package org.peekit.opentimelapse.accessibility

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one place that knows whether the accessibility service is alive.
 *
 * The service is owned by the system, not by us: it can be bound and torn down at any time,
 * and ColorOS was observed silently restoring the enabled-services setting after it had been
 * cleared. So nothing caches a reference - callers ask here every time, and the Setup screen
 * reads live state rather than what it last wrote.
 */
object AccessibilityBridge {

    private val current = MutableStateFlow<TimelapseAccessibilityService?>(null)

    /** The live service, or null when the system has not bound it. Never cache the result. */
    val service: TimelapseAccessibilityService? get() = current.value

    val serviceFlow: StateFlow<TimelapseAccessibilityService?> = current.asStateFlow()

    val isConnected: Boolean get() = current.value != null

    /** For the Setup screen, which must reflect live state rather than what it last wrote. */
    val connected: Flow<Boolean> = current.map { it != null }

    internal fun register(service: TimelapseAccessibilityService) {
        current.value = service
    }

    internal fun unregister() {
        current.value = null
    }

    /**
     * Waits for the service instead of failing outright: after a reboot or an app update the
     * engine can start moments before the system has bound it.
     */
    suspend fun awaitService(timeoutMs: Long = 5_000): TimelapseAccessibilityService? =
        current.value ?: withTimeoutOrNull(timeoutMs) { current.first { it != null } }
}
