package com.dualcampip.app.render

import com.dualcampip.app.model.PipConfig
import com.dualcampip.app.model.PipCorner
import kotlin.math.min

data class PixelRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

fun pipRect(
    frameWidth: Float,
    frameHeight: Float,
    contentAspect: Float,
    corner: PipCorner,
    widthFraction: Float,
    marginFraction: Float = PipConfig.MARGIN_FRACTION,
    startIsLeft: Boolean = true,
): PixelRect {
    val aspect = contentAspect.coerceAtLeast(0.05f)
    val margin = marginFraction.coerceIn(0f, 0.2f) * frameWidth
    var pipW = widthFraction.coerceIn(0.05f, 0.9f) * frameWidth
    var pipH = pipW / aspect
    val maxW = (frameWidth - margin * 2f).coerceAtLeast(1f)
    val maxH = (frameHeight - margin * 2f).coerceAtLeast(1f)
    if (pipW > maxW) {
        pipW = maxW
        pipH = pipW / aspect
    }
    if (pipH > maxH) {
        pipH = maxH
        pipW = pipH * aspect
    }
    val towardStart = when (corner) {
        PipCorner.TOP_START, PipCorner.BOTTOM_START -> true
        PipCorner.TOP_END, PipCorner.BOTTOM_END -> false
    }
    val towardTop = when (corner) {
        PipCorner.TOP_START, PipCorner.TOP_END -> true
        PipCorner.BOTTOM_START, PipCorner.BOTTOM_END -> false
    }
    val placeLeft = if (startIsLeft) towardStart else !towardStart
    val left = if (placeLeft) margin else frameWidth - margin - pipW
    val top = if (towardTop) margin else frameHeight - margin - pipH
    return PixelRect(left, top, left + pipW, top + pipH)
}

fun pipCornerRadiusPx(rect: PixelRect, radiusFraction: Float): Float {
    val half = min(rect.width, rect.height) / 2f
    if (half <= 1f) return 0f
    return (radiusFraction.coerceIn(0f, 1f) * half).coerceAtMost(half - 0.5f).coerceAtLeast(0f)
}
