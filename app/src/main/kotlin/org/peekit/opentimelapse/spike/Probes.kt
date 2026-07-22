package org.peekit.opentimelapse.spike

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import org.peekit.opentimelapse.WakeActivity
import org.peekit.opentimelapse.accessibility.AccessibilityBridge
import org.peekit.opentimelapse.TimelapseApp
import org.peekit.opentimelapse.core.render.Encoder
import org.peekit.opentimelapse.core.render.RenderSpec
import org.peekit.opentimelapse.render.RenderService
import org.peekit.opentimelapse.core.ui.ShutterFinder
import org.peekit.opentimelapse.service.TimelapseService
import java.io.File
import kotlinx.coroutines.delay

/**
 * Phase 0 risk spike.
 *
 * Each probe answers one question that the rest of the design assumes. They report
 * PASS/FAIL to logcat rather than throwing, because the interesting failures here are
 * silent - a blocked background activity launch does not raise anything.
 */
object Probes {

    suspend fun run(context: Context, which: String, params: Bundle? = null) {
        SpikeLog.log("--- probe '$which' starting ---")
        when (which) {
            "sysinfo" -> sysinfo(context)
            // Drives the real engine over adb; the service itself stays unexported.
            "start" -> TimelapseService.send(context, TimelapseService.ACTION_START)
            "stop" -> TimelapseService.send(context, TimelapseService.ACTION_STOP)
            "cycle" -> TimelapseService.send(context, TimelapseService.ACTION_SINGLE_CYCLE)
            "calibrate" -> TimelapseService.send(context, TimelapseService.ACTION_CALIBRATE)
            "config" -> configure(context, params)
            "sessions" -> listSessions(context)
            "delete-latest" -> deleteLatest(context)
            "export" -> exportLatest(context)
            "ffmpeg" -> ffmpegInfo(context)
            "render" -> renderLatest(context, params)
            "set-command" -> setRenderCommand(context, params)
            "swipe" -> swipeSweep(context, params)
            "bal-service" -> backgroundLaunch(context, fromAccessibility = false)
            "bal-accessibility" -> backgroundLaunch(context, fromAccessibility = true)
            "wake-only" -> wakeOnly(context)
            "keyguard" -> keyguardGesture(context)
            "lock" -> lockOverCamera(context)
            "exec" -> execNativeBinary(context)
            "shutter" -> shutterFinder(context, click = false)
            "shutter-click" -> shutterFinder(context, click = true)
            else -> SpikeLog.log("unknown probe '$which'")
        }
        SpikeLog.log("--- probe '$which' done ---")
    }

    fun sysinfo(context: Context) {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        SpikeLog.log("device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
        SpikeLog.log("isDeviceSecure=${keyguard.isDeviceSecure} isKeyguardLocked=${keyguard.isKeyguardLocked}")
        SpikeLog.log("isInteractive=${power.isInteractive} ignoringBatteryOptimisations=${power.isIgnoringBatteryOptimizations(context.packageName)}")
        SpikeLog.log("canDrawOverlays=${Settings.canDrawOverlays(context)}")
        SpikeLog.log("accessibilityConnected=${AccessibilityBridge.service != null}")
        SpikeLog.log("cameraPackage(IMAGE_CAPTURE)=${resolveCameraPackage(context)}")
        AccessibilityBridge.service?.let { SpikeLog.log("screenBounds=${it.screenBounds()}") }
    }

    /**
     * Probe 1. Android 10+ blocks activity launches from the background, and Android 15
     * tightened it further. A blocked launch is silent, so success is measured by whether
     * [WakeActivity] actually ran - not by the absence of an exception.
     */
    private suspend fun backgroundLaunch(context: Context, fromAccessibility: Boolean) {
        val label = if (fromAccessibility) "bal-accessibility" else "bal-service"
        val power = context.getSystemService(PowerManager::class.java)
        val overlayGranted = Settings.canDrawOverlays(context)

        val launcher: Context? =
            if (fromAccessibility) AccessibilityBridge.service else context
        if (launcher == null) {
            SpikeLog.result(label, false, "accessibility service is not connected")
            return
        }

        val screenBefore = power.isInteractive
        val startedAt = System.currentTimeMillis()
        WakeActivity.lastStartedAtMs.set(0L)
        SpikeLog.log("$label: screenOn=$screenBefore overlayGranted=$overlayGranted")

        val intent = Intent(context, WakeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            launcher.startActivity(intent)
            SpikeLog.log("$label: startActivity() returned without throwing")
        } catch (t: Throwable) {
            SpikeLog.log("$label: startActivity() threw ${t.javaClass.simpleName}: ${t.message}")
        }

        delay(2_500)
        val ran = WakeActivity.startedSince(startedAt)
        val screenAfter = power.isInteractive
        SpikeLog.result(
            label,
            ran,
            "activityRan=$ran screen=$screenBefore->$screenAfter overlayGranted=$overlayGranted",
        )
    }

    /**
     * Probe 2a. Waking alone, with no swipe at all.
     *
     * If an insecure keyguard dismisses itself when WakeActivity shows over it, the whole
     * unlock-swipe step is a fallback rather than a requirement - so it has to be measured
     * separately from the gesture.
     */
    private suspend fun wakeOnly(context: Context) {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        SpikeLog.log("wake-only: before screenOn=${power.isInteractive} locked=${keyguard.isKeyguardLocked}")

        wakeScreen(context)

        repeat(6) { step ->
            delay(500)
            SpikeLog.log("wake-only: +${(step + 1) * 500}ms screenOn=${power.isInteractive} locked=${keyguard.isKeyguardLocked}")
        }
        SpikeLog.result(
            "wake-only",
            power.isInteractive && !keyguard.isKeyguardLocked,
            "screenOn=${power.isInteractive} lockedAfter=${keyguard.isKeyguardLocked} (no gesture dispatched)",
        )
    }

    /**
     * Sets config over adb until the Settings screen exists:
     *
     *   --ez naming true --es prefix sunset --ei interval 15
     */
    private suspend fun configure(context: Context, params: Bundle?) {
        val app = context.applicationContext as TimelapseApp
        app.configRepository.update { config ->
            config.copy(
                intervalSeconds = params?.getInt("interval", config.intervalSeconds)
                    ?: config.intervalSeconds,
                naming = config.naming.copy(
                    enabled = params?.getBoolean("naming", config.naming.enabled)
                        ?: config.naming.enabled,
                    prefix = params?.getString("prefix") ?: config.naming.prefix,
                ),
            )
        }
        val updated = app.configRepository.current()
        SpikeLog.log(
            "config: interval=${updated.intervalSeconds}s naming=${updated.naming.enabled} " +
                "prefix=${updated.naming.prefix} camera=${updated.shutter.packageName}"
        )
        SpikeLog.log("config: allFilesAccess=${app.storage.canRenameForeignFiles()}")
    }

    /** Writes the concat list and prints the ffmpeg command for the newest session. */
    private suspend fun exportLatest(context: Context) {
        val app = context.applicationContext as TimelapseApp
        val latest = app.sessionStore.loadAll().firstOrNull()
        if (latest == null) {
            SpikeLog.log("export: no sessions recorded yet")
            return
        }
        val export = app.sessionExporter.export(latest, RenderSpec())
        if (export == null) {
            SpikeLog.log("export: ${latest.name} has no recorded frames")
            return
        }
        SpikeLog.log("export: list=${export.concatListPath}")
        SpikeLog.log("export: ${export.command}")
    }

    /** Confirms the bundled binary runs at all, and reports what it can encode. */
    private suspend fun ffmpegInfo(context: Context) {
        val app = context.applicationContext as TimelapseApp
        val binary = app.ffmpeg.binary()
        SpikeLog.log("ffmpeg: path=${binary?.absolutePath ?: "MISSING"} executable=${binary?.canExecute()}")
        SpikeLog.log("ffmpeg: ${app.ffmpeg.version() ?: "did not run"}")
    }

    /** Renders the newest session so the whole pipeline can be exercised over adb. */
    private suspend fun renderLatest(context: Context, params: Bundle?) {
        val app = context.applicationContext as TimelapseApp
        val latest = app.sessionStore.loadAll().firstOrNull { it.frameCount > 0 }
        if (latest == null) {
            SpikeLog.log("render: no session with frames")
            return
        }
        val spec = RenderSpec(
            fps = params?.getInt("fps", 10) ?: 10,
            longEdgePx = params?.getInt("edge", 1280) ?: 1280,
            encoder = if (params?.getBoolean("x264", true) != false) Encoder.X264 else Encoder.HARDWARE,
            customCommand = app.configRepository.current().customRenderCommand,
        )
        SpikeLog.log("render: ${latest.name} (${latest.frameCount} frames) with ${spec.encoder}")
        RenderService.render(context, latest.id, spec)
    }

    /** Exercises exactly what the Sessions screen's Delete button calls. */
    private suspend fun deleteLatest(context: Context) {
        val app = context.applicationContext as TimelapseApp
        val latest = app.sessionStore.loadAll().firstOrNull()
        if (latest == null) {
            SpikeLog.log("delete: nothing to delete")
            return
        }
        SpikeLog.log("delete: removing ${latest.id}")
        app.sessionStore.delete(latest.id)
        val remaining = app.sessionStore.loadAll()
        SpikeLog.log(
            "delete: ${remaining.size} left; still present=${remaining.any { it.id == latest.id }}"
        )
    }

    /** Sets or clears the custom render command over adb. */
    private suspend fun setRenderCommand(context: Context, params: Bundle?) {
        val app = context.applicationContext as TimelapseApp
        val command = params?.getString("cmd").orEmpty()
        app.configRepository.update { it.copy(customRenderCommand = command) }
        SpikeLog.log("custom command = '${app.configRepository.current().customRenderCommand}'")
    }

    private suspend fun listSessions(context: Context) {
        val app = context.applicationContext as TimelapseApp
        val sessions = app.sessionStore.loadAll()
        SpikeLog.log("sessions: ${sessions.size} found under ${app.storage.sessionsRoot()}")
        sessions.forEach {
            SpikeLog.log(
                "  ${it.name}: ${it.frameCount} frames, pattern=${it.inputPattern() ?: "original names"}, " +
                    "renderable=${it.isRenderable}"
            )
        }
    }

    /**
     * Sweepable unlock swipe. One UI did not respond to the default path, and the swipe
     * geometry is user-configurable anyway, so this exists to find defaults empirically:
     *
     *   --ef sx 0.5 --ef sy 0.95 --ef ex 0.5 --ef ey 0.25 --el dur 400
     */
    private suspend fun swipeSweep(context: Context, params: Bundle?) {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val service = AccessibilityBridge.service
        if (service == null) {
            SpikeLog.result("swipe", false, "accessibility service is not connected")
            return
        }

        val sx = params?.getFloat("sx", 0.5f) ?: 0.5f
        val sy = params?.getFloat("sy", 0.8f) ?: 0.8f
        val ex = params?.getFloat("ex", 0.5f) ?: 0.5f
        val ey = params?.getFloat("ey", 0.3f) ?: 0.3f
        val duration = params?.getLong("dur", 300L) ?: 300L

        if (!keyguard.isKeyguardLocked) {
            SpikeLog.log("swipe: keyguard is not showing - nothing to dismiss, results are meaningless")
        }
        SpikeLog.log("swipe: ($sx,$sy)->($ex,$ey) over ${duration}ms, lockedBefore=${keyguard.isKeyguardLocked}")

        val dispatched = service.swipe(sx, sy, ex, ey, duration)
        delay(1_500)

        SpikeLog.result(
            "swipe",
            !keyguard.isKeyguardLocked,
            "path=($sx,$sy)->($ex,$ey) dur=$duration dispatched=$dispatched lockedAfter=${keyguard.isKeyguardLocked}",
        )
    }

    /** Probe 2b. Can a dispatched gesture dismiss the keyguard on a freshly woken screen? */
    private suspend fun keyguardGesture(context: Context) {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val service = AccessibilityBridge.service
        if (service == null) {
            SpikeLog.result("keyguard", false, "accessibility service is not connected")
            return
        }

        SpikeLog.log(
            "keyguard: secure=${keyguard.isDeviceSecure} lockedBefore=${keyguard.isKeyguardLocked} " +
                "screenOn=${power.isInteractive}"
        )
        if (keyguard.isDeviceSecure) {
            SpikeLog.log("keyguard: device has a credential lock - a swipe cannot pass it, as designed")
        }

        wakeScreen(context)
        delay(800)
        SpikeLog.log("keyguard: after wake screenOn=${power.isInteractive} locked=${keyguard.isKeyguardLocked}")

        // The first dispatch is often cancelled by the window transition that the wake
        // itself causes, so measure whether a retry gets through.
        var dispatched = false
        var attempts = 0
        repeat(3) {
            if (!dispatched) {
                attempts++
                dispatched = service.swipe(0.5f, 0.8f, 0.5f, 0.3f, 300L)
                if (!dispatched) delay(600)
            }
        }
        delay(1_200)

        val lockedAfter = keyguard.isKeyguardLocked
        SpikeLog.result(
            "keyguard",
            dispatched && !lockedAfter,
            "gestureDispatched=$dispatched attempts=$attempts lockedAfter=$lockedAfter screenOn=${power.isInteractive}",
        )
    }

    /** Probe 3. Does GLOBAL_ACTION_LOCK_SCREEN work while the camera owns the screen? */
    private suspend fun lockOverCamera(context: Context) {
        val power = context.getSystemService(PowerManager::class.java)
        val service = AccessibilityBridge.service
        if (service == null) {
            SpikeLog.result("lock", false, "accessibility service is not connected")
            return
        }

        val camera = resolveCameraPackage(context)
        if (camera == null) {
            SpikeLog.result("lock", false, "no IMAGE_CAPTURE handler found")
            return
        }
        launchCamera(context, camera)
        delay(3_000)
        SpikeLog.log("lock: foreground=${AccessibilityBridge.service?.foregroundPackage()} (wanted $camera)")

        val accepted = service.lockScreen()
        delay(2_500)
        val interactive = power.isInteractive
        SpikeLog.result(
            "lock",
            accepted && !interactive,
            "actionAccepted=$accepted screenOnAfter=$interactive",
        )
    }

    /**
     * Probe 4. Since Android 10 an app may only exec from its native library directory.
     * The binary here is this device's own /system/bin/toybox, renamed - a real ARM64 ELF,
     * which makes this a genuine test of the path ffmpeg will take in Phase 7.
     */
    private fun execNativeBinary(context: Context) {
        val dir = context.applicationInfo.nativeLibraryDir
        // Now that the app ships a real ffmpeg, that binary IS the exec test - a better
        // one than the pulled-shell hack the spike originally used.
        val binary = File(dir, "libffmpeg.so")
        SpikeLog.log("exec: dir=$dir exists=${binary.exists()} canExecute=${binary.canExecute()} size=${binary.length()}")

        if (!binary.exists()) {
            SpikeLog.result("exec", false, "ffmpeg binary was not extracted - check useLegacyPackaging")
            return
        }

        try {
            val process = ProcessBuilder(binary.absolutePath, "-version")
                .redirectErrorStream(true)
                .start()
            val firstLine = process.inputStream.bufferedReader().readLine().orEmpty()
            val exit = process.waitFor()
            SpikeLog.result(
                "exec",
                exit == 0 && firstLine.contains("ffmpeg version"),
                "exit=$exit output='$firstLine'",
            )
        } catch (t: Throwable) {
            SpikeLog.result("exec", false, "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Probe 5. The real test of device-agnostic detection: score this phone's own camera
     * UI and see whether the shutter comes out on top with no manufacturer knowledge.
     */
    private suspend fun shutterFinder(context: Context, click: Boolean) {
        val service = AccessibilityBridge.service
        if (service == null) {
            SpikeLog.result("shutter", false, "accessibility service is not connected")
            return
        }
        val camera = resolveCameraPackage(context)
        if (camera == null) {
            SpikeLog.result("shutter", false, "no IMAGE_CAPTURE handler found")
            return
        }

        launchCamera(context, camera)
        delay(4_000)

        val nodes = service.activeWindowNodes()
        val clickable = nodes.count { it.clickable }
        SpikeLog.log("shutter: foreground=${AccessibilityBridge.service?.foregroundPackage()} nodes=${nodes.size} clickable=$clickable")

        val screen = service.screenBounds()
        val finder = ShutterFinder(screen)
        val ranked = finder.rank(nodes)

        ranked.take(6).forEachIndexed { position, scored ->
            SpikeLog.log(
                "  #$position score=%.3f id=%s desc=%s bounds=%s %s".format(
                    scored.score,
                    scored.node.viewId ?: "-",
                    scored.node.contentDescription ?: "-",
                    scored.node.bounds,
                    scored.reasons.joinToString("; "),
                )
            )
        }

        val best = finder.best(nodes)
        SpikeLog.result(
            "shutter",
            best != null,
            if (best == null) {
                "nothing scored above ${ShutterFinder.MIN_SCORE}; ${ranked.size} candidates"
            } else {
                "best=%s score=%.3f".format(best.node.viewId ?: best.node.contentDescription ?: "unlabelled", best.score)
            },
        )

        if (click && best != null) {
            val how = service.clickAt(best.node.bounds)
            SpikeLog.log("shutter: clicked via $how - check the gallery for a new photo")
        }
    }

    fun resolveCameraPackage(context: Context): String? {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        val resolved = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName
    }

    private fun launchCamera(context: Context, packageName: String) {
        // No CLEAR_TASK: resuming the existing task is what preserves Pro mode settings.
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            SpikeLog.log("no launch intent for $packageName")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launcher: Context = AccessibilityBridge.service ?: context
        try {
            launcher.startActivity(intent)
            SpikeLog.log("launched $packageName")
        } catch (t: Throwable) {
            SpikeLog.log("launching $packageName threw ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    @Suppress("DEPRECATION")
    fun wakeScreen(context: Context) {
        val power = context.getSystemService(PowerManager::class.java)
        val launcher: Context = AccessibilityBridge.service ?: context
        try {
            launcher.startActivity(
                Intent(context, WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (t: Throwable) {
            SpikeLog.log("wake via activity threw ${t.javaClass.simpleName}")
        }
        // Legacy fallback, kept only to record whether it still does anything on modern Android.
        val lock = power.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "opentimelapse:spike-wake",
        )
        lock.acquire(3_000L)
        SpikeLog.log("wake: legacy FULL_WAKE_LOCK acquired, interactive=${power.isInteractive}")
    }

}
