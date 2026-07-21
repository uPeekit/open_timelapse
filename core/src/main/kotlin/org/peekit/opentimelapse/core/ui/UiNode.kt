package org.peekit.opentimelapse.core.ui

/** Screen-space rectangle, in pixels. Mirrors an accessibility node's bounds. */
data class NodeBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val area: Long get() = width.toLong() * height.toLong()
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}

/**
 * A flattened accessibility node.
 *
 * Exists so shutter detection is a pure function over plain data: the Android layer
 * converts `AccessibilityNodeInfo` into these, and the detection logic can then be
 * tested against real UI trees from any manufacturer without a device.
 */
data class UiNode(
    val bounds: NodeBounds,
    val viewId: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val text: String? = null,
    val clickable: Boolean = false,
    val enabled: Boolean = true,
    val visible: Boolean = true,
)
