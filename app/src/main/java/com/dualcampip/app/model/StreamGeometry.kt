package com.dualcampip.app.model

enum class CameraSlot {
    REAR,
    FRONT,
}

/**
 * How a camera buffer should be mapped into an upright frame.
 * Crop is in buffer pixels. [rotationDegrees] is clockwise, applied before [mirror].
 */
data class StreamGeometry(
    val bufferWidth: Int,
    val bufferHeight: Int,
    val rotationDegrees: Int,
    val mirror: Boolean,
    val cropLeft: Int = 0,
    val cropTop: Int = 0,
    val cropRight: Int = bufferWidth,
    val cropBottom: Int = bufferHeight,
)
