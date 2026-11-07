package com.dualcampip.app.model

enum class CameraSlot {
    REAR,
    FRONT,
}

/**
 * How a camera buffer should be mapped into an upright frame.
 * Crop is in buffer pixels. [rotationDegrees] is clockwise from the sensor buffer
 * to the target orientation, and is what makes the upright aspect correct.
 *
 * When [cameraAppliesTransform] is true, the camera already wrote that sensor
 * orientation into [android.graphics.SurfaceTexture.getTransformMatrix]. Applying
 * [rotationDegrees] again turns an upright preview on its side. Only
 * [displayRotationDegrees] is applied on top of the matrix in that case.
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
    val cameraAppliesTransform: Boolean = false,
    val displayRotationDegrees: Int = 0,
)
