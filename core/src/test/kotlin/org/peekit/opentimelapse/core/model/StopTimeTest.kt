package org.peekit.opentimelapse.core.model

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StopTimeTest {

    private val zone = ZoneId.of("Europe/London")

    private fun at(text: String): Long = ZonedDateTime.parse(text).toInstant().toEpochMilli()

    @Test
    fun `a full date and time can be days away`() {
        val stop = StopTime.atDateTime(year = 2026, month = 7, day = 30, hour = 6, minute = 0, zone = zone)
        assertEquals(at("2026-07-30T06:00:00+01:00[Europe/London]"), stop)
    }

    @Test
    fun `a time later today stays today`() {
        val now = at("2026-07-22T09:00:00+01:00[Europe/London]")

        val stop = StopTime.nextOccurrence(hour = 18, minute = 30, nowMs = now, zone = zone)

        assertEquals(at("2026-07-22T18:30:00+01:00[Europe/London]"), stop)
    }

    @Test
    fun `a time already past rolls to tomorrow`() {
        // Setting 06:00 at nine in the evening means tomorrow morning, not this morning.
        val now = at("2026-07-22T21:00:00+01:00[Europe/London]")

        val stop = StopTime.nextOccurrence(hour = 6, minute = 0, nowMs = now, zone = zone)

        assertEquals(at("2026-07-23T06:00:00+01:00[Europe/London]"), stop)
        assertTrue(stop > now)
    }

    @Test
    fun `the current minute counts as passed rather than stopping instantly`() {
        val now = at("2026-07-22T12:00:00+01:00[Europe/London]")

        val stop = StopTime.nextOccurrence(hour = 12, minute = 0, nowMs = now, zone = zone)

        assertEquals(at("2026-07-23T12:00:00+01:00[Europe/London]"), stop)
    }

    @Test
    fun `survives a daylight saving change`() {
        // Clocks go back on 25 October 2026; the night is 25 hours long.
        val now = at("2026-10-24T23:00:00+01:00[Europe/London]")

        val stop = StopTime.nextOccurrence(hour = 6, minute = 0, nowMs = now, zone = zone)

        assertTrue(stop > now, "must still be in the future across a DST boundary")
        val (hour, minute) = StopTime.hourAndMinute(stop, zone)
        assertEquals(6, hour)
        assertEquals(0, minute)
    }

    @Test
    fun `round trips back to the hour and minute shown to the user`() {
        val now = at("2026-07-22T09:00:00+01:00[Europe/London]")
        val stop = StopTime.nextOccurrence(hour = 5, minute = 45, nowMs = now, zone = zone)

        assertEquals(5 to 45, StopTime.hourAndMinute(stop, zone))
    }

    @Test
    fun `nonsense input is clamped rather than throwing`() {
        val now = at("2026-07-22T09:00:00+01:00[Europe/London]")

        val (hour, minute) = StopTime.hourAndMinute(
            StopTime.nextOccurrence(hour = 99, minute = 99, nowMs = now, zone = zone),
            zone,
        )
        assertEquals(23, hour)
        assertEquals(59, minute)
    }
}
