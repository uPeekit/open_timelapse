package org.peekit.opentimelapse.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import org.peekit.opentimelapse.BuildConfig
import org.peekit.opentimelapse.TimelapseApp
import org.peekit.opentimelapse.accessibility.AccessibilityBridge
import org.peekit.opentimelapse.accessibility.TimelapseAccessibilityService
import org.peekit.opentimelapse.core.model.CycleMode
import org.peekit.opentimelapse.core.model.TimelapseConfig

data class SetupCheck(
    val title: String,
    val satisfied: Boolean,
    val detail: String,
    /** Where the user goes to fix it; null when there is nothing to open. */
    val fix: Intent? = null,
    /** A missing optional item warns but does not block Start. */
    val required: Boolean = true,
    /** Shown in full and agreed to before [fix] is opened; null when there is nothing to disclose. */
    val disclosure: String? = null,
)

/**
 * Reads live system state every time.
 *
 * Nothing is cached: ColorOS was observed silently restoring the enabled-accessibility-
 * services setting after it had been cleared, so a checklist that trusted what it last
 * wrote would show green while the service was gone.
 */
object SetupChecks {

    fun evaluate(context: Context, config: TimelapseConfig): List<SetupCheck> {
        val app = context.applicationContext as TimelapseApp
        val checks = mutableListOf<SetupCheck>()

        checks += SetupCheck(
            title = "Accessibility service",
            satisfied = isAccessibilityEnabled(context),
            detail = if (AccessibilityBridge.isConnected) {
                "Connected."
            } else {
                "Lets the app tap the shutter and unlock the screen. Nothing works without it. " +
                    "Android switches it off after an app update or a crash, so check here first."
            },
            fix = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            disclosure = ACCESSIBILITY_DISCLOSURE,
        )

        checks += SetupCheck(
            title = "Camera app",
            satisfied = config.shutter.packageName.isNotBlank(),
            detail = config.shutter.packageName.ifBlank {
                "Detected automatically on the first run - nothing to type."
            },
            required = false,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checks += SetupCheck(
                title = "Notifications",
                satisfied = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED,
                detail = "Shows the running session and its Stop button.",
                fix = appSettings(context),
            )
        }

        checks += SetupCheck(
            title = "Read photos",
            satisfied = hasMediaRead(context),
            detail = "Used to confirm each frame actually landed, rather than trusting the tap.",
            fix = appSettings(context),
        )

        checks += SetupCheck(
            title = "Ignore battery optimisation",
            satisfied = context.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName) == true,
            detail = "Without it, long sessions can be frozen between frames. On Samsung " +
                "this may also need Battery > Unrestricted in the app's own settings.",
            // The targeted dialog, not the global list: Samsung's list does not obviously
            // lead to the per-app switch that actually matters.
            fix = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context)),
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarms = context.getSystemService(android.app.AlarmManager::class.java)
            checks += SetupCheck(
                title = "Exact alarms",
                satisfied = alarms?.canScheduleExactAlarms() == true,
                detail = "Keeps long intervals on time while the phone sleeps.",
                fix = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri(context)),
            )
        }

        // The play flavor does not declare the permission, so there is nothing to grant.
        if (config.naming.enabled && BuildConfig.ALL_FILES_ACCESS) {
            checks += SetupCheck(
                title = "All-files access",
                satisfied = app.storage.canRenameForeignFiles(),
                detail = "Needed only to rename frames into a session folder. " +
                    "Without it they keep the camera's own names.",
                fix = app.storage.requestIntent(),
                required = false,
            )
        }

        if (config.mode == CycleMode.LOCK_CYCLE) {
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            checks += SetupCheck(
                title = "Lock screen is Swipe",
                satisfied = keyguard?.isDeviceSecure != true,
                detail = "A swipe cannot pass a PIN, pattern or password. " +
                    "Set the lock to Swipe, or switch to Awake mode.",
                fix = Intent(Settings.ACTION_SECURITY_SETTINGS),
            )
        }

        return checks
    }

    fun blocking(checks: List<SetupCheck>): List<SetupCheck> =
        checks.filter { it.required && !it.satisfied }

    /**
     * Whether the service is genuinely running.
     *
     * The bridge is the authority: the service registers itself there when the system binds
     * it. Reading the settings string alone was wrong and actively harmful - a device with
     * our service listed but the master accessibility toggle off reported green while every
     * frame failed with "accessibility service is not connected".
     */
    private fun isAccessibilityEnabled(context: Context): Boolean {
        if (AccessibilityBridge.isConnected) return true

        val listed = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
            .split(':')
            .any { it.equals("${context.packageName}/${TimelapseAccessibilityService::class.java.name}", true) }
        val masterSwitchOn = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0,
        ) == 1

        return listed && masterSwitchOn
    }

    private fun hasMediaRead(context: Context): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun appSettings(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))

    private fun packageUri(context: Context): Uri = Uri.parse("package:${context.packageName}")

    /**
     * What the user agrees to before being sent to the accessibility settings.
     *
     * Google Play requires this of any app that uses the Accessibility API without being an
     * assistive tool: in the app, ahead of the request, naming what is accessed and what is
     * done with it. Keep it true - if the service ever reads or keeps more, say so here.
     */
    private val ACCESSIBILITY_DISCLOSURE = """
        OpenTimelapse uses Android's Accessibility API to press your camera app's shutter for you. It is not an assistive tool.

        What it does
        • Presses the shutter button in your camera app at the interval you set.
        • In lock-cycle mode, swipes the lock screen away before a frame and locks the screen again after it.
        It acts only during a session, test shot or calibration that you started.

        What it can see
        To find the shutter and to check that the camera is in front, it reads which app is on screen and the layout of that app's controls: their position, type and labels.

        What happens to that
        It is used on this phone, at that moment. OpenTimelapse keeps only which camera app and which button to press, and notes them in its own log. Nothing is collected, sent off the device or shared with anyone, unless you share that log yourself.
    """.trimIndent()
}
