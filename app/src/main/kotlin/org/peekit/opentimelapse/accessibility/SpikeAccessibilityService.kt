package org.peekit.opentimelapse.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.peekit.opentimelapse.spike.SpikeLog
import org.peekit.opentimelapse.core.ui.NodeBounds
import org.peekit.opentimelapse.core.ui.UiNode
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Phase 0 accessibility service. Provides the four things the production service will
 * need - gestures, node inspection, global actions, and (maybe) activity launch - so the
 * probes can measure each in isolation.
 */
class SpikeAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        SpikeLog.log("AccessibilityService connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        SpikeLog.log("AccessibilityService unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg != foregroundPackage) {
                foregroundPackage = pkg
                SpikeLog.log("foreground -> $pkg")
            }
        }
    }

    override fun onInterrupt() = Unit

    /** Real display size in pixels, insets included - gestures are dispatched in these coordinates. */
    fun screenBounds(): NodeBounds {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            NodeBounds(b.left, b.top, b.right, b.bottom)
        } else {
            @Suppress("DEPRECATION")
            val dm = resources.displayMetrics
            NodeBounds(0, 0, dm.widthPixels, dm.heightPixels)
        }
    }

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
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
                .build(),
            "tap($x,$y)",
        )
    }

    private suspend fun dispatch(gesture: GestureDescription, label: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            val callback = object : GestureResultCallback() {
                override fun onCompleted(description: GestureDescription?) {
                    SpikeLog.log("gesture $label completed")
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(description: GestureDescription?) {
                    SpikeLog.log("gesture $label CANCELLED")
                    if (continuation.isActive) continuation.resume(false)
                }
            }
            val accepted = dispatchGesture(gesture, callback, null)
            SpikeLog.log("dispatchGesture($label) accepted=$accepted")
            if (!accepted && continuation.isActive) continuation.resume(false)
        }

    /** Flattens the active window into plain data so [org.peekit.opentimelapse.core.ui.ShutterFinder] can score it. */
    fun dumpNodes(): List<UiNode> {
        val root = rootInActiveWindow ?: run {
            SpikeLog.log("rootInActiveWindow is null")
            return emptyList()
        }
        val out = mutableListOf<UiNode>()
        flatten(root, out, 0)
        return out
    }

    private fun flatten(node: AccessibilityNodeInfo?, out: MutableList<UiNode>, depth: Int) {
        if (node == null || depth > 40) return
        val rect = Rect().also { node.getBoundsInScreen(it) }
        out += UiNode(
            bounds = NodeBounds(rect.left, rect.top, rect.right, rect.bottom),
            viewId = node.viewIdResourceName,
            contentDescription = node.contentDescription?.toString(),
            className = node.className?.toString(),
            text = node.text?.toString(),
            clickable = node.isClickable,
            enabled = node.isEnabled,
            visible = node.isVisibleToUser,
        )
        for (i in 0 until node.childCount) flatten(node.getChild(i), out, depth + 1)
    }

    /** Clicks the node matching [bounds], falling back to a tap at its centre. */
    suspend fun clickNodeAt(bounds: NodeBounds): String {
        val root = rootInActiveWindow ?: return "no root"
        val match = findByBounds(root, bounds, 0)
        if (match != null && match.isClickable) {
            val performed = match.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (performed) return "ACTION_CLICK"
        }
        val tapped = tap(bounds.centerX.toFloat(), bounds.centerY.toFloat())
        return if (tapped) "coordinate tap" else "click failed"
    }

    private fun findByBounds(
        node: AccessibilityNodeInfo?,
        target: NodeBounds,
        depth: Int,
    ): AccessibilityNodeInfo? {
        if (node == null || depth > 40) return null
        val rect = Rect().also { node.getBoundsInScreen(it) }
        if (rect.left == target.left && rect.top == target.top &&
            rect.right == target.right && rect.bottom == target.bottom
        ) {
            return node
        }
        for (i in 0 until node.childCount) {
            findByBounds(node.getChild(i), target, depth + 1)?.let { return it }
        }
        return null
    }

    fun lockScreen(): Boolean = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

    companion object {
        @Volatile
        var instance: SpikeAccessibilityService? = null
            private set

        @Volatile
        var foregroundPackage: String? = null
            private set
    }
}
