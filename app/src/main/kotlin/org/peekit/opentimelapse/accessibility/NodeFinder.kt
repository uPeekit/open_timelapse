package org.peekit.opentimelapse.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.peekit.opentimelapse.core.engine.ShutterStrategy
import org.peekit.opentimelapse.core.model.ShutterConfig
import org.peekit.opentimelapse.core.model.ShutterMode
import org.peekit.opentimelapse.core.ui.NodeBounds
import org.peekit.opentimelapse.core.ui.ShutterFinder
import org.peekit.opentimelapse.core.ui.UiNode

/** A shutter candidate resolved from the live UI, plus how it was found. */
data class ResolvedShutter(
    val bounds: NodeBounds,
    val strategy: ShutterStrategy,
    val node: UiNode?,
    val detail: String,
)

/**
 * Turns the live accessibility tree into plain [UiNode]s and locates the shutter.
 *
 * All scoring lives in :core's [ShutterFinder]; this file only does the Android-side
 * translation, so the interesting logic stays unit-testable without a device.
 */
object NodeFinder {

    private const val MAX_DEPTH = 40

    fun flatten(root: AccessibilityNodeInfo?): List<UiNode> {
        val out = mutableListOf<UiNode>()
        collect(root, out, 0)
        return out
    }

    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<UiNode>, depth: Int) {
        if (node == null || depth > MAX_DEPTH) return
        out += node.toUiNode()
        for (i in 0 until node.childCount) collect(node.getChild(i), out, depth + 1)
    }

    fun AccessibilityNodeInfo.toUiNode(): UiNode {
        val rect = Rect().also { getBoundsInScreen(it) }
        return UiNode(
            bounds = NodeBounds(rect.left, rect.top, rect.right, rect.bottom),
            viewId = viewIdResourceName,
            contentDescription = contentDescription?.toString(),
            className = className?.toString(),
            text = text?.toString(),
            clickable = isClickable,
            enabled = isEnabled,
            visible = isVisibleToUser,
        )
    }

    /**
     * Resolves the shutter using the configured strategy, falling back in order:
     * view id, content description, geometric detection, then fixed coordinates.
     *
     * Returns null only when even the coordinate fallback is not applicable.
     */
    fun resolve(
        root: AccessibilityNodeInfo?,
        config: ShutterConfig,
        screen: NodeBounds,
    ): ResolvedShutter? {
        val nodes = flatten(root)

        if (config.mode == ShutterMode.COORDINATES) return coordinates(config, screen)

        if (config.mode == ShutterMode.AUTO || config.mode == ShutterMode.ACCESSIBILITY_ID) {
            byViewId(nodes, config.viewId)?.let { return it }
            if (config.mode == ShutterMode.ACCESSIBILITY_ID) return coordinates(config, screen)
        }

        if (config.mode == ShutterMode.AUTO || config.mode == ShutterMode.CONTENT_DESCRIPTION) {
            byContentDescription(nodes, config.contentDescription)?.let { return it }
            if (config.mode == ShutterMode.CONTENT_DESCRIPTION) return coordinates(config, screen)
        }

        // AUTO only: no calibration matched, so fall back on shape and placement. This is
        // the path that works on a camera app nobody has ever configured.
        ShutterFinder(screen).best(nodes)?.let { scored ->
            return ResolvedShutter(
                bounds = scored.node.bounds,
                strategy = ShutterStrategy.VIEW_ID.takeIf { scored.node.viewId != null }
                    ?: ShutterStrategy.CONTENT_DESCRIPTION,
                node = scored.node,
                detail = "detected by shape/position (score %.2f: %s)"
                    .format(java.util.Locale.ROOT, scored.score, scored.reasons.joinToString("; ")),
            )
        }

        return coordinates(config, screen)
    }

    private fun byViewId(nodes: List<UiNode>, viewId: String): ResolvedShutter? {
        if (viewId.isBlank()) return null
        val match = nodes.firstOrNull { it.viewId == viewId && it.clickable && it.visible }
            ?: return null
        return ResolvedShutter(match.bounds, ShutterStrategy.VIEW_ID, match, "matched view id $viewId")
    }

    private fun byContentDescription(nodes: List<UiNode>, description: String): ResolvedShutter? {
        if (description.isBlank()) return null
        val match = nodes.firstOrNull {
            it.clickable && it.visible &&
                it.contentDescription?.contains(description, ignoreCase = true) == true
        } ?: return null
        return ResolvedShutter(
            match.bounds,
            ShutterStrategy.CONTENT_DESCRIPTION,
            match,
            "matched description \"$description\"",
        )
    }

    private fun coordinates(config: ShutterConfig, screen: NodeBounds): ResolvedShutter {
        val x = (screen.width * config.fallbackXPercent).toInt()
        val y = (screen.height * config.fallbackYPercent).toInt()
        return ResolvedShutter(
            bounds = NodeBounds(x, y, x, y),
            strategy = ShutterStrategy.COORDINATES,
            node = null,
            detail = "no node matched; tapping ${config.fallbackXPercent}x${config.fallbackYPercent}",
        )
    }
}
