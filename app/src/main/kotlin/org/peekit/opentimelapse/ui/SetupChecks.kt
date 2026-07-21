package org.peekit.opentimelapse.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import org.peekit.opentimelapse.TimelapseApp
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
            detail = "Lets the app tap the shutter and unlock the screen. Nothing works without it.",
            fix = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
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
            detail = "Without it, long sessions can be frozen between frames.",
            fix = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
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

        if (config.naming.enabled) {
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

    private fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = "${context.packageName}/${TimelapseAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
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
}
