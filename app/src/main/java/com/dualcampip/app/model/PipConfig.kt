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
) {
    fun sanitized(): PipConfig = copy(
        widthFraction = widthFraction.coerceIn(MIN_WIDTH_FRACTION, MAX_WIDTH_FRACTION),
        radiusFraction = radiusFraction.coerceIn(0f, 1f),
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
