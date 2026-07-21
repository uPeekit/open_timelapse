package org.peekit.opentimelapse.core.engine

/** Injected so tests run on virtual time instead of waiting out real intervals. */
fun interface Clock {
    fun nowMs(): Long
}

/**
 * Injected for the same reason, and because the real implementation is not a `delay()`:
 * with the screen off the CPU sleeps, so long waits go through AlarmManager.
 */
interface Waiter {
    suspend fun sleep(durationMs: Long)

    suspend fun awaitUntil(epochMs: Long)
}
