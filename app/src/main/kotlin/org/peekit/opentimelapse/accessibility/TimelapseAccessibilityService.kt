package org.peekit.opentimelapse.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.peekit.opentimelapse.core.ui.NodeBounds
import org.peekit.opentimelapse.core.ui.UiNode
import org.peekit.opentimelapse.Logcat

/**
 * The only component that can touch the screen on our behalf.
 *
 * It exposes primitives - gesture, node inspection, global actions - and holds no policy:
 * what to press and when lives in :core. Callers reach it through [AccessibilityBridge]
 * rather than a cached reference, because the system owns its lifecycle.
 */
class TimelapseAccessibilityService : AccessibilityService() {

    @Volatile
    private var lastWindowPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityBridge.register(this)
        Logcat.i("AccessibilityService connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AccessibilityBridge.unregister()
        Logcat.i("AccessibilityService unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AccessibilityBridge.unregister()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastWindowPackage = event.packageName?.toString()
        }
    }

    override fun onInterrupt() = Unit

    /**
     * The app that currently owns the screen.
     *
     * Read from the active window, *not* from window-state events: those reported
     * `com.android.systemui` on all three test devices while the camera genuinely owned the
     * screen, because status bar and shade windows raise events of their own. The event
     * stream is kept only as a fallback for when there is no active window.
     */
    fun foregroundPackage(): String? =
        rootInActiveWindow?.packageName?.toString() ?: lastWindowPackage

    /** Real display size in pixels; gestures are dispatched in these coordinates. */
    fun screenBounds(): NodeBounds {
        val manager = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = manager.currentWindowMetrics.bounds
            NodeBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
        } else {
            @Suppress("DEPRECATION")
            val metrics = resources.displayMetrics
            NodeBounds(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
    }

    fun activeWindowNodes(): List<UiNode> = NodeFinder.flatten(rootInActiveWindow)

    suspend fun swipe(
        startXPercent: Float,
        startYPercent: Float,
        endXPercent: Float,
        endYPercent: Float,
        durationMs: Long,
    ): Boolean {
        val screen = screenBounds()
        val path = Path().apply {
            moveTo(screen.width * startXPercent, screen.height * startYPercent)
            lineTo(screen.width * endXPercent, screen.height * endYPercent)
        }
        return dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
                .build(),
            "swipe",
        )
    }

    suspend fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y + 1f)
        }
        return dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS))
                .build(),
            "tap",
        )
    }

    /**
     * Clicks the node occupying [bounds], preferring ACTION_CLICK and falling back to a tap.
     *
     * The returned string describes how it was done, for the log. Note that the result is
     * *not* proof of anything: callers verify against device state.
     */
    suspend fun clickAt(bounds: NodeBounds): String {
        val match = rootInActiveWindow?.let { findByBounds(it, bounds, 0) }
        if (match != null && match.isClickable) {
            if (match.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "ACTION_CLICK"
        }
        val tapped = tap(bounds.centerX.toFloat(), bounds.centerY.toFloat())
        return if (tapped) "coordinate tap" else "click not dispatched"
    }

    fun lockScreen(): Boolean = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

    private suspend fun dispatch(gesture: GestureDescription, label: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            val callback = object : GestureResultCallback() {
                override fun onCompleted(description: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }

                // Routinely reported for a gesture that worked: the keyguard grabs the touch
                // stream to run its own dismiss animation. Never treated as authoritative.
                override fun onCancelled(description: GestureDescription?) {
                    Logcat.i("gesture $label reported cancelled (may still have landed)")
                    if (continuation.isActive) continuation.resume(false)
                }
            }
            val accepted = dispatchGesture(gesture, callback, null)
            if (!accepted && continuation.isActive) continuation.resume(false)
        }

    private fun findByBounds(
        node: AccessibilityNodeInfo?,
        target: NodeBounds,
        depth: Int,
    ): AccessibilityNodeInfo? {
        if (node == null || depth > MAX_DEPTH) return null
        with(NodeFinder) {
            if (node.toUiNode().bounds == target) return node
        }
        for (i in 0 until node.childCount) {
            findByBounds(node.getChild(i), target, depth + 1)?.let { return it }
        }
        return null
    }

    private companion object {
        const val TAP_DURATION_MS = 60L
        const val MAX_DEPTH = 40
    }
}
