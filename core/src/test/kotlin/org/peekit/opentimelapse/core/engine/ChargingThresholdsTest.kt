package org.peekit.opentimelapse.core.engine

import org.peekit.opentimelapse.core.model.ChargingConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChargingThresholdsTest {

    private val config = ChargingConfig(
        enabled = true,
        lowPercent = 40,
        highPercent = 80,
        startChargingUrl = "https://ha/start",
        stopChargingUrl = "https://ha/stop",
    )

    @Test
    fun `asks for charging when it falls below the floor while unplugged`() {
        val sut = ChargingThresholds()
        assertNull(sut.onReading(BatteryReading(50, charging = false), config))
        assertEquals(
            ChargingAction.START_CHARGING,
            sut.onReading(BatteryReading(39, charging = false), config),
        )
    }

    @Test
    fun `fires once on crossing, not on every reading below the line`() {
        // The failure this prevents: a battery sitting at 39% sending on every poll,
        // hammering the socket and burning through any rate limit.
        val sut = ChargingThresholds()
        assertEquals(
            ChargingAction.START_CHARGING,
            sut.onReading(BatteryReading(39, charging = false), config),
        )

        assertNull(sut.onReading(BatteryReading(38, charging = false), config))
        assertNull(sut.onReading(BatteryReading(30, charging = false), config))
        assertNull(sut.onReading(BatteryReading(20, charging = false), config))
    }

    @Test
    fun `re-arms only after the opposite threshold`() {
        val sut = ChargingThresholds()
        sut.onReading(BatteryReading(39, charging = false), config)          // start charging
        assertEquals(
            ChargingAction.STOP_CHARGING,
            sut.onReading(BatteryReading(81, charging = true), config),
        )
        assertNull(sut.onReading(BatteryReading(85, charging = true), config))

        // Falls again, and the low trigger is armed once more.
        assertEquals(
            ChargingAction.START_CHARGING,
            sut.onReading(BatteryReading(39, charging = false), config),
        )
    }

    @Test
    fun `charging state matters, not just the level`() {
        val sut = ChargingThresholds()

        // Already unplugged and high: nothing to stop.
        assertNull(sut.onReading(BatteryReading(90, charging = false), config))
        // Already charging and low: nothing to start.
        assertNull(sut.onReading(BatteryReading(20, charging = true), config))
    }

    @Test
    fun `a full battery on the charger is told to stop`() {
        assertEquals(
            ChargingAction.STOP_CHARGING,
            ChargingThresholds().onReading(BatteryReading(100, charging = true), config),
        )
    }

    @Test
    fun `disabled does nothing at all`() {
        val sut = ChargingThresholds()
        val off = config.copy(enabled = false)
        assertNull(sut.onReading(BatteryReading(5, charging = false), off))
        assertNull(sut.onReading(BatteryReading(100, charging = true), off))
    }

    @Test
    fun `config edits apply to the very next reading`() {
        // The engine re-reads config every cycle; the charging rules must not lag behind
        // on a snapshot taken at session start.
        val sut = ChargingThresholds()
        assertNull(sut.onReading(BatteryReading(45, charging = false), config))

        assertEquals(
            ChargingAction.START_CHARGING,
            sut.onReading(BatteryReading(45, charging = false), config.copy(lowPercent = 50)),
        )
    }

    @Test
    fun `an unconfigured url yields no destination`() {
        val partial = config.copy(stopChargingUrl = "")
        val sut = ChargingThresholds()
        assertEquals("https://ha/start", sut.callFor(ChargingAction.START_CHARGING, partial)?.url)
        assertNull(sut.callFor(ChargingAction.STOP_CHARGING, partial))
    }

    @Test
    fun `each direction carries its own method and body`() {
        val custom = config.copy(
            startMethod = "GET", startBody = "",
            stopMethod = "POST", stopBody = """{"state":"off"}""",
        )
        val sut = ChargingThresholds()
        val start = sut.callFor(ChargingAction.START_CHARGING, custom)
        val stop = sut.callFor(ChargingAction.STOP_CHARGING, custom)
        assertEquals("GET", start?.method)
        assertEquals("", start?.body)
        assertEquals("POST", stop?.method)
        assertEquals("""{"state":"off"}""", stop?.body)
    }

    @Test
    fun `thresholds are honoured exactly at the boundary`() {
        val sut = ChargingThresholds()
        assertEquals(
            ChargingAction.START_CHARGING,
            sut.onReading(BatteryReading(40, charging = false), config),
        )

        val other = ChargingThresholds()
        assertEquals(
            ChargingAction.STOP_CHARGING,
            other.onReading(BatteryReading(80, charging = true), config),
        )
    }
}
