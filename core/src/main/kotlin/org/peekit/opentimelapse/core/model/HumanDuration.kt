package org.peekit.opentimelapse.core.model

/**
 * A span of time as a human reads it: "2h 19m 32s", "5m 20s", "45s".
 *
 * Seconds alone stop being legible past a minute or two - a render log saying "8372s" is a
 * number nobody parses at a glance. Hours are shown only once there are hours, minutes once
 * there are minutes; seconds are always shown so a short span never renders empty.
 */
fun humanDuration(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val hours = s / 3600
    val minutes = (s % 3600) / 60
    val seconds = s % 60
    return buildString {
        if (hours > 0) append("${hours}h ")
        if (hours > 0 || minutes > 0) append("${minutes}m ")
        append("${seconds}s")
    }
}
