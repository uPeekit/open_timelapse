package org.peekit.opentimelapse.core.model

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Turns "stop at 06:00" into an instant.
 *
 * A wall-clock stop time is almost always in the future in the user's head - someone
 * setting 06:00 at nine in the evening means tomorrow morning, not a time twelve hours
 * past. So a time that has already passed today rolls to tomorrow.
 */
object StopTime {

    fun nextOccurrence(
        hour: Int,
        minute: Int,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val wanted = LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))

        val today = now.toLocalDate().atTime(wanted).atZone(zone)
        val target = if (today.toInstant().toEpochMilli() > nowMs) {
            today
        } else {
            now.toLocalDate().plusDays(1).atTime(wanted).atZone(zone)
        }
        return target.toInstant().toEpochMilli()
    }

    /** Splits a stored instant back into hour and minute for display. */
    fun hourAndMinute(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<Int, Int> {
        val time = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime()
        return time.hour to time.minute
    }
}
