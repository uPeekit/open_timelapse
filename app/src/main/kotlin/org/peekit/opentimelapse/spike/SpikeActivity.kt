package org.peekit.opentimelapse.spike

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.peekit.opentimelapse.service.TimelapseService

/**
 * Phase 0 console. Deliberately plain views - the spike must not depend on the UI stack
 * we have not built yet, and it gets replaced by the real Compose UI in Phase 5.
 */
class SpikeActivity : Activity() {

    private lateinit var output: TextView

    private val listener: (String) -> Unit = { line ->
        runOnUiThread {
            output.append("\n$line")
            (output.parent as? ScrollView)?.post { (output.parent as ScrollView).fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Started from the foreground so the service is already established by the time
        // the background-launch probe runs.
        SpikeService.start(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        root.addView(button("START timelapse") {
            TimelapseService.send(this, TimelapseService.ACTION_START)
        })
        root.addView(button("STOP timelapse") {
            TimelapseService.send(this, TimelapseService.ACTION_STOP)
        })
        root.addView(button("Run one cycle (real engine)") {
            TimelapseService.send(this, TimelapseService.ACTION_SINGLE_CYCLE)
        })

        root.addView(button("System info") { trigger("sysinfo") })
        root.addView(button("Probe 1a - background launch from service (20s)") { trigger("bal-service", 20_000) })
        root.addView(button("Probe 1b - background launch from accessibility (20s)") { trigger("bal-accessibility", 20_000) })
        root.addView(button("Probe 2 - swipe unlock on keyguard (20s)") { trigger("keyguard", 20_000) })
        root.addView(button("Probe 3 - lock screen over camera") { trigger("lock") })
        root.addView(button("Probe 4 - exec native binary") { trigger("exec") })
        root.addView(button("Probe 5 - find shutter in camera UI") { trigger("shutter") })
        root.addView(button("Probe 5b - find shutter AND click it") { trigger("shutter-click") })
        root.addView(button("Open accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button("Open 'display over other apps'") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        })

        output = TextView(this).apply {
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
            setTextColor(Color.DKGRAY)
            textSize = 11f
            text = SpikeLog.snapshot()
        }
        val scroll = ScrollView(this).apply {
            addView(output)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
            ).apply { weight = 1f }
        }
        root.addView(scroll)

        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        SpikeLog.addListener(listener)
    }

    override fun onStop() {
        SpikeLog.removeListener(listener)
        super.onStop()
    }

    private fun trigger(which: String, delayMs: Long = 0L) {
        val service = SpikeService.instance
        if (service == null) {
            SpikeLog.log("service not running yet - try again in a moment")
            SpikeService.start(this)
            return
        }
        service.runProbe(which, delayMs)
        if (delayMs > 0) moveTaskToBack(true)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        isAllCaps = false
        setOnClickListener { onClick() }
    }
}
