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

    private fun thresholds() = ChargingThresholds(config)

    @Test
    fun `asks for charging when it falls below the floor while unplugged`() {
        val sut = thresholds()
        assertNull(sut.onReading(BatteryReading(50, charging = false)))
        assertEquals(ChargingAction.START_CHARGING, sut.onReading(BatteryReading(39, charging = false)))
    }

    @Test
    fun `fires once on crossing, not on every reading below the line`() {
        // The failure this prevents: a battery sitting at 39% sending on every poll,
        // hammering the socket and burning through any rate limit.
        val sut = thresholds()
        assertEquals(ChargingAction.START_CHARGING, sut.onReading(BatteryReading(39, charging = false)))

        assertNull(sut.onReading(BatteryReading(38, charging = false)))
        assertNull(sut.onReading(BatteryReading(30, charging = false)))
        assertNull(sut.onReading(BatteryReading(20, charging = false)))
    }

    @Test
    fun `re-arms only after the opposite threshold`() {
        val sut = thresholds()
        sut.onReading(BatteryReading(39, charging = false))          // start charging
        assertEquals(ChargingAction.STOP_CHARGING, sut.onReading(BatteryReading(81, charging = true)))
        assertNull(sut.onReading(BatteryReading(85, charging = true)))

        // Falls again, and the low trigger is armed once more.
        assertEquals(ChargingAction.START_CHARGING, sut.onReading(BatteryReading(39, charging = false)))
    }

    @Test
    fun `charging state matters, not just the level`() {
        val sut = thresholds()

        // Already unplugged and high: nothing to stop.
        assertNull(sut.onReading(BatteryReading(90, charging = false)))
        // Already charging and low: nothing to start.
        assertNull(sut.onReading(BatteryReading(20, charging = true)))
    }

    @Test
    fun `a full battery on the charger is told to stop`() {
        assertEquals(
            ChargingAction.STOP_CHARGING,
            thresholds().onReading(BatteryReading(100, charging = true)),
        )
    }

    @Test
    fun `disabled does nothing at all`() {
        val off = ChargingThresholds(config.copy(enabled = false))
        assertNull(off.onReading(BatteryReading(5, charging = false)))
        assertNull(off.onReading(BatteryReading(100, charging = true)))
    }

    @Test
    fun `an unconfigured url yields no destination`() {
        val partial = ChargingThresholds(config.copy(stopChargingUrl = ""))
        assertEquals("https://ha/start", partial.callFor(ChargingAction.START_CHARGING)?.url)
        assertNull(partial.callFor(ChargingAction.STOP_CHARGING))
    }

    @Test
    fun `each direction carries its own method and body`() {
        val custom = ChargingThresholds(
            config.copy(
                startMethod = "GET", startBody = "",
                stopMethod = "POST", stopBody = """{"state":"off"}""",
            ),
        )
        val start = custom.callFor(ChargingAction.START_CHARGING)
        val stop = custom.callFor(ChargingAction.STOP_CHARGING)
        assertEquals("GET", start?.method)
        assertEquals("", start?.body)
        assertEquals("POST", stop?.method)
        assertEquals("""{"state":"off"}""", stop?.body)
    }

    @Test
    fun `thresholds are honoured exactly at the boundary`() {
        val sut = thresholds()
        assertEquals(ChargingAction.START_CHARGING, sut.onReading(BatteryReading(40, charging = false)))

        val other = thresholds()
        assertEquals(ChargingAction.STOP_CHARGING, other.onReading(BatteryReading(80, charging = true)))
    }
}
