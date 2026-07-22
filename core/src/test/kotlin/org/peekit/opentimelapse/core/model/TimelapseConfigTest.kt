package org.peekit.opentimelapse.core.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimelapseConfigTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `enabling naming forces capture verification on`() {
        // Renaming needs to know which files a press produced; only MediaStore observation knows.
        val config = TimelapseConfig(
            naming = NamingConfig(enabled = true),
            capture = CaptureConfig(verifyViaMediaStore = false),
        )

        assertTrue(config.normalized().capture.verifyViaMediaStore)
    }

    @Test
    fun `verification stays off when naming is off`() {
        val config = TimelapseConfig(
            naming = NamingConfig(enabled = false),
            capture = CaptureConfig(verifyViaMediaStore = false),
        )

        assertFalse(config.normalized().capture.verifyViaMediaStore)
    }

    @Test
    fun `a zero interval cannot stall the engine`() {
        assertEquals(1_000L, TimelapseConfig(intervalSeconds = 0).intervalMs)
        assertEquals(30_000L, TimelapseConfig(intervalSeconds = 30).intervalMs)
    }

    @Test
    fun `round trips through json`() {
        val original = TimelapseConfig(
            mode = CycleMode.AWAKE,
            intervalSeconds = 45,
            naming = NamingConfig(enabled = true, prefix = "sunset", padWidth = 6, startIndex = 10),
            session = SessionConfig(endMode = EndMode.AT_TIME, endAtEpochMs = 1_800_000_000_000L),
        )

        val decoded = json.decodeFromString<TimelapseConfig>(json.encodeToString(original))

        assertEquals(original, decoded)
    }

    @Test
    fun `an older stored config decodes with defaults for fields added later`() {
        // Exactly what DataStore holds after an app update introduces new settings.
        val stored = """{"intervalSeconds":15,"shutter":{"packageName":"com.example.cam"}}"""

        val decoded = json.decodeFromString<TimelapseConfig>(stored)

        assertEquals(15, decoded.intervalSeconds)
        assertEquals("com.example.cam", decoded.shutter.packageName)
        assertEquals(CycleMode.LOCK_CYCLE, decoded.mode)
        assertEquals(DelayConfig().afterCameraReadyMs, decoded.delays.afterCameraReadyMs)
        assertFalse(decoded.naming.enabled)
    }

    @Test
    fun `the default config names no manufacturer`() {
        // A hardcoded package would ship every non-Samsung user a broken config.
        val shutter = TimelapseConfig().shutter
        assertEquals(ShutterMode.AUTO, shutter.mode)
        assertEquals("", shutter.packageName)
        assertEquals("", shutter.viewId)
    }

    @Test
    fun `presets stay in auto mode so a stale view id still falls back`() {
        val withId = CameraPreset.ALL.first { it.shutterViewId != null }.toShutterConfig()
        assertEquals(ShutterMode.AUTO, withId.mode)
        assertTrue(withId.viewId.isNotEmpty())

        val withoutId = CameraPreset.ALL.first { it.shutterViewId == null }.toShutterConfig()
        assertEquals(ShutterMode.AUTO, withoutId.mode)
        assertTrue(withoutId.viewId.isEmpty())
    }

    @Test
    fun `preset packages are unique`() {
        val packages = CameraPreset.ALL.map { it.packageName }
        assertEquals(packages.size, packages.distinct().size)
    }

    @Test
    fun `manifest exposes an ffmpeg pattern only when frames were renamed`() {
        val named = SessionManifest(
            id = "1", name = "sunset", folderPath = "/DCIM/OpenTimelapse/sunset",
            cameraPackage = "com.sec.android.app.camera", intervalSeconds = 30,
            startedAtMs = 0L, naming = NamingConfig(enabled = true, prefix = "sunset", padWidth = 8),
            frameCount = 120,
        )
        assertEquals("sunset%08d.jpg", named.inputPattern())
        assertEquals("sunset%08d.dng", named.inputPattern("dng"))
        assertTrue(named.isRenderable)

        val untouched = named.copy(naming = NamingConfig(enabled = false))
        assertEquals(null, untouched.inputPattern())
        assertFalse(untouched.isRenderable)
    }
}
