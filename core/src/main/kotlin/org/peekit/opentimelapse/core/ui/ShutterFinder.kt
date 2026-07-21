package org.peekit.opentimelapse.core.ui

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class ScoredNode(
    val node: UiNode,
    val score: Double,
    val reasons: List<String>,
)

/**
 * Finds the shutter button in an arbitrary camera app.
 *
 * Deliberately geometry-first. Resource ids differ per manufacturer and firmware, and
 * content descriptions are localised - neither can be relied on for "any Android device".
 * What is stable across every camera app ever shipped is the shape and placement: a large
 * round button centred on the short axis, at the far end of the long axis. Ids and
 * descriptions only add confidence on top of that.
 *
 * Pure: the Android layer flattens `AccessibilityNodeInfo` into [UiNode]s and passes them in.
 */
class ShutterFinder(private val screen: NodeBounds) {

    fun rank(nodes: List<UiNode>): List<ScoredNode> =
        nodes.mapNotNull(::score).sortedByDescending { it.score }

    /** The single best candidate, or null when nothing is convincing enough to click blind. */
    fun best(nodes: List<UiNode>): ScoredNode? =
        rank(nodes).firstOrNull { it.score >= MIN_SCORE }

    private fun score(node: UiNode): ScoredNode? {
        if (!node.clickable || !node.enabled || !node.visible) return null
        if (node.bounds.isEmpty) return null
        // A node covering most of the screen is the preview surface or a layout container.
        if (node.bounds.area > screen.area * MAX_AREA_FRACTION) return null
        // A shutter is round or square in every camera app ever shipped. Rejecting long
        // bars outright matters: ColorOS shows a "Statement of Use" consent dialog on first
        // camera launch whose full-width "Agree and continue" button sits bottom-centre and
        // scored 0.424 on position and size alone - uncomfortably close to being clicked.
        if (aspectRatio(node.bounds) < MIN_ASPECT_RATIO) return null

        val reasons = mutableListOf<String>()
        var total = 0.0

        if (node.viewId != null && ID_PATTERN.containsMatchIn(node.viewId)) {
            total += WEIGHT_ID
            reasons += "view id looks like a shutter"
        }
        // English-only on purpose: a bonus, never a requirement, so other locales are unaffected.
        if (node.contentDescription != null && DESCRIPTION_PATTERN.containsMatchIn(node.contentDescription)) {
            total += WEIGHT_DESCRIPTION
            reasons += "content description looks like a shutter"
        }

        val position = positionScore(node.bounds)
        if (position > 0.0) {
            total += WEIGHT_POSITION * position
            if (position > 0.6) reasons += "centred at the far edge, where shutters live"
        }

        val squareness = squarenessScore(node.bounds)
        if (squareness > 0.0) {
            total += WEIGHT_SQUARENESS * squareness
            if (squareness > 0.8) reasons += "round or square"
        }

        val size = sizeScore(node.bounds)
        total += WEIGHT_SIZE * size
        if (size > 0.7) reasons += "shutter-sized"

        return ScoredNode(node, total, reasons)
    }

    /**
     * Centred on the short axis, near the far end of the long axis. Handles both
     * orientations: portrait puts the shutter at the bottom, landscape on the right.
     */
    private fun positionScore(bounds: NodeBounds): Double {
        val portrait = screen.height >= screen.width

        val crossCenter = if (portrait) bounds.centerX else bounds.centerY
        val crossScreenCenter = if (portrait) screen.centerX else screen.centerY
        val crossHalfSpan = (if (portrait) screen.width else screen.height) / 2.0

        val mainCenter = if (portrait) bounds.centerY else bounds.centerX
        val mainSpan = (if (portrait) screen.height else screen.width).toDouble()

        if (crossHalfSpan <= 0.0 || mainSpan <= 0.0) return 0.0

        val crossOffset = abs(crossCenter - crossScreenCenter) / crossHalfSpan
        val centred = (1.0 - crossOffset / CROSS_AXIS_TOLERANCE).coerceIn(0.0, 1.0)

        val mainPosition = mainCenter / mainSpan
        val atFarEdge = ((mainPosition - FAR_EDGE_START) / (FAR_EDGE_FULL - FAR_EDGE_START))
            .coerceIn(0.0, 1.0)

        return 0.5 * centred + 0.5 * atFarEdge
    }

    private fun aspectRatio(bounds: NodeBounds): Double =
        min(bounds.width, bounds.height).toDouble() / max(bounds.width, bounds.height)

    private fun squarenessScore(bounds: NodeBounds): Double =
        ((aspectRatio(bounds) - SQUARE_MIN_RATIO) / (1.0 - SQUARE_MIN_RATIO)).coerceIn(0.0, 1.0)

    private fun sizeScore(bounds: NodeBounds): Double {
        val shortScreenEdge = min(screen.width, screen.height)
        if (shortScreenEdge <= 0) return 0.0
        val fraction = min(bounds.width, bounds.height).toDouble() / shortScreenEdge
        return (1.0 - abs(fraction - IDEAL_SIZE_FRACTION) / SIZE_TOLERANCE).coerceIn(0.0, 1.0)
    }

    companion object {
        fun forScreen(widthPx: Int, heightPx: Int) = ShutterFinder(NodeBounds(0, 0, widthPx, heightPx))

        /**
         * True when this control starts a video recording rather than taking a still.
         *
         * Camera apps reuse one node for both: on ColorOS `:id/shutter_button` is described
         * as `"Shutter" button` in photo mode and `Video Recording Button` in video mode -
         * same id, same bounds. Pressing it unattended records continuously (measured at
         * ~100 MB/min) and yields no frames at all, so the session must refuse to start.
         */
        fun isVideoControl(node: UiNode): Boolean {
            val haystack = "${node.viewId.orEmpty()} ${node.contentDescription.orEmpty()}"
            return VIDEO_PATTERN.containsMatchIn(haystack)
        }

        private val VIDEO_PATTERN =
            Regex("video|record|camcorder", RegexOption.IGNORE_CASE)

        /**
         * Perfect geometry alone reaches 0.60, so an unlabelled button in a camera app
         * whose language we cannot read is still detected. A stale or wrong view id
         * cannot drag a badly-placed node over the line on its own.
         */
        const val MIN_SCORE = 0.45

        private const val WEIGHT_POSITION = 0.35
        private const val WEIGHT_SQUARENESS = 0.15
        private const val WEIGHT_SIZE = 0.10
        private const val WEIGHT_ID = 0.25
        private const val WEIGHT_DESCRIPTION = 0.15

        private const val MAX_AREA_FRACTION = 0.25

        /** Anything longer than roughly 2.2:1 is a bar or a text button, not a shutter. */
        private const val MIN_ASPECT_RATIO = 0.45
        private const val CROSS_AXIS_TOLERANCE = 0.30
        private const val FAR_EDGE_START = 0.60
        private const val FAR_EDGE_FULL = 0.90
        private const val SQUARE_MIN_RATIO = 0.60
        private const val IDEAL_SIZE_FRACTION = 0.15
        private const val SIZE_TOLERANCE = 0.20

        private val ID_PATTERN =
            Regex("shutter|capture|take_?(photo|picture)|btn_?shot|snap", RegexOption.IGNORE_CASE)

        private val DESCRIPTION_PATTERN =
            Regex("shutter|take\\s+(a\\s+)?(photo|picture)|capture", RegexOption.IGNORE_CASE)
    }
}
