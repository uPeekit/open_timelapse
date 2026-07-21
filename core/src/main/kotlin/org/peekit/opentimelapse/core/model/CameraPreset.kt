package org.peekit.opentimelapse.core.model

/**
 * Optional shortcuts, not the mechanism.
 *
 * The app resolves the device's own camera app by asking the package manager who handles
 * IMAGE_CAPTURE, and finds the shutter geometrically - so an unlisted phone works with no
 * preset at all. These entries only pre-fill a known view id to skip a detection step, and
 * they go stale as firmware changes, which is why the resulting config stays in AUTO mode
 * and falls back on its own.
 */
data class CameraPreset(
    val label: String,
    val packageName: String,
    val shutterViewId: String?,
    val contentDescription: String = "Shutter",
) {
    fun toShutterConfig(): ShutterConfig = ShutterConfig(
        mode = ShutterMode.AUTO,
        packageName = packageName,
        viewId = shutterViewId.orEmpty(),
        contentDescription = contentDescription,
    )

    companion object {
        /**
         * A view id is listed only where it is actually known. Everything else is left
         * null on purpose - inventing resource names would be worse than detecting the
         * button, which works regardless.
         */
        val ALL: List<CameraPreset> = listOf(
            CameraPreset("Generic AOSP", "com.android.camera2", "com.android.camera2:id/shutter_button"),
            CameraPreset("Samsung One UI", "com.sec.android.app.camera", "com.sec.android.app.camera:id/shutter_button"),
            CameraPreset("Google Pixel", "com.google.android.GoogleCamera", "com.google.android.GoogleCamera:id/shutter_button"),
            CameraPreset("Xiaomi / MIUI", "com.android.camera", "com.android.camera:id/shutter_button"),
            CameraPreset("OnePlus / Oppo / Realme", "com.oplus.camera", null),
            CameraPreset("OnePlus (legacy)", "com.oneplus.camera", null),
            CameraPreset("Huawei / Honor", "com.huawei.camera", null),
            CameraPreset("Vivo", "com.vivo.camera", null),
            CameraPreset("Motorola", "com.motorola.camera3", null),
            CameraPreset("Sony", "com.sonyericsson.android.camera", null),
            CameraPreset("Asus", "com.asus.camera", null),
            CameraPreset("LG", "com.lge.camera", null),
        )
    }
}
