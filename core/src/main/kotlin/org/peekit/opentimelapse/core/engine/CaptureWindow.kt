package org.peekit.opentimelapse.core.engine

/**
 * How long to wait for the camera to write a frame.
 *
 * The wait itself is event-driven - it ends the moment the file appears - so this is a
 * ceiling, not a cost. Its only job is to be larger than the slowest capture the camera
 * will ever produce.
 *
 * It widens as the session goes because exposure time is not a property of the device: the
 * same phone that writes a frame in one second at noon takes thirty on a night sky, and a
 * sunset shot in auto mode moves between those two while nobody is watching. A ceiling
 * fitted once, at the start, is fitted to the wrong scene.
 */
object CaptureWindow {

    /** Headroom over the slowest capture seen, matching the factor calibration uses. */
    private const val HEADROOM = 3.0

    /**
     * The widest window this will infer on its own. A capture that never lands would
     * otherwise stall the session for as long as it liked; the user's own configured value
     * is still honoured above this.
     */
    const val CEILING_MS = 120_000L

    fun timeoutFor(configuredMs: Long, longestObservedMs: Long): Long {
        val inferred = (longestObservedMs * HEADROOM).toLong().coerceAtMost(CEILING_MS)
        return maxOf(configuredMs, inferred)
    }
}
