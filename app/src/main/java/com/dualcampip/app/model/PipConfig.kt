package com.dualcampip.app.model

/**
 * Picture-in-picture placement for both the live preview and the encoded file.
 * [widthFraction] is the PiP width as a fraction of the frame width.
 * [radiusFraction] is 0 for square corners and 1 for a fully rounded short side.
 */
data class PipConfig(
    val corner: PipCorner = PipCorner.BOTTOM_END,
    val widthFraction: Float = DEFAULT_WIDTH_FRACTION,
    val radiusFraction: Float = DEFAULT_RADIUS_FRACTION,
    /**
     * Left edge of the PiP as a fraction of the 9:16 frame.
     * Together with [topFraction], this overrides [corner] after a drag.
     */
    val leftFraction: Float? = null,
    /** Top edge of the PiP as a fraction of the 9:16 frame. */
    val topFraction: Float? = null,
) {
    fun sanitized(): PipConfig {
        val left = leftFraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
        val top = topFraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
        val placed = left != null && top != null
        return copy(
            widthFraction = widthFraction.coerceIn(MIN_WIDTH_FRACTION, MAX_WIDTH_FRACTION),
            radiusFraction = radiusFraction.coerceIn(0f, 1f),
            leftFraction = if (placed) left else null,
            topFraction = if (placed) top else null,
        )
    }

    fun atCorner(corner: PipCorner): PipConfig = copy(
        corner = corner,
        leftFraction = null,
        topFraction = null,
    )

    companion object {
        const val MIN_WIDTH_FRACTION = 0.18f
        const val MAX_WIDTH_FRACTION = 0.40f
        const val DEFAULT_WIDTH_FRACTION = 0.25f
        const val DEFAULT_RADIUS_FRACTION = 0.35f
        const val MARGIN_FRACTION = 0.04f

        val Default = PipConfig()
    }
}

enum class PipCorner {
    TOP_START,
    TOP_END,
    BOTTOM_START,
    BOTTOM_END,
}
