package com.dualcampip.app.render

import com.dualcampip.app.model.StreamGeometry
import kotlin.math.abs

/**
 * Maps a camera buffer into a destination rectangle.
 *
 * Texture coordinates follow the Android SurfaceTexture contract: the matrix from
 * [android.graphics.SurfaceTexture.getTransformMatrix] is applied to Grafika-style
 * coordinates where (0,0) is the bottom-left and (0,1) is the top-left.
 *
 * A surface connected straight to the camera already stores sensor rotation and the
 * front-camera mirror in that matrix ([StreamGeometry.cameraAppliesTransform]).
 * [StreamGeometry.rotationDegrees] is then only the amount the buffer must turn to
 * match the target, used for aspect and crop — not applied a second time.
 * [StreamGeometry.displayRotationDegrees] is the extra turn when the display is
 * not in its natural orientation. When the matrix does not contain the camera
 * transform, [StreamGeometry.rotationDegrees] is applied in buffer space instead.
 */
fun sampleTexCoord(
    lu: Float,
    lv: Float,
    destAspect: Float,
    geometry: StreamGeometry,
    surfaceTextureMatrix: FloatArray,
): Pair<Float, Float> {
    if (geometry.bufferWidth <= 0 || geometry.bufferHeight <= 0) return 0f to 0f
    val srcAspect = uprightAspect(geometry)
    val (su0, sv0) = centerCropUv(lu, lv, destAspect.coerceAtLeast(0.01f), srcAspect)
    val su = if (geometry.mirror) 1f - su0 else su0
    if (geometry.cameraAppliesTransform) {
        val window = orientedCropNorm(geometry)
        val u = window[0] + su * (window[2] - window[0])
        val v = window[1] + sv0 * (window[3] - window[1])
        val (nu, nv) = uprightToBufferUv(u, v, normalizeRotation(geometry.displayRotationDegrees))
        return applyStMatrix(surfaceTextureMatrix, nu, 1f - nv)
    }
    val cropW = (geometry.cropRight - geometry.cropLeft).coerceAtLeast(1)
    val cropH = (geometry.cropBottom - geometry.cropTop).coerceAtLeast(1)
    val (bu, bv) = uprightToBufferUv(su, sv0, normalizeRotation(geometry.rotationDegrees))
    val fullU = (geometry.cropLeft + bu * cropW) / geometry.bufferWidth.toFloat()
    val fullV = (geometry.cropTop + bv * cropH) / geometry.bufferHeight.toFloat()
    return applyStMatrix(surfaceTextureMatrix, fullU, 1f - fullV)
}

fun uprightAspect(geometry: StreamGeometry): Float {
    if (geometry.bufferWidth <= 0 || geometry.bufferHeight <= 0) return 9f / 16f
    val cropW = (geometry.cropRight - geometry.cropLeft).coerceAtLeast(1)
    val cropH = (geometry.cropBottom - geometry.cropTop).coerceAtLeast(1)
    val rot = normalizeRotation(geometry.rotationDegrees)
    val uprightW = if (rot == 90 || rot == 270) cropH else cropW
    val uprightH = if (rot == 90 || rot == 270) cropW else cropH
    return uprightW.toFloat() / uprightH.coerceAtLeast(1)
}

/**
 * Triangle strip: top-left, bottom-left, top-right, bottom-right.
 * Each vertex is ndcX, ndcY, texU, texV, localU, localV.
 */
fun buildQuadVertices(
    rect: PixelRect,
    viewWidth: Int,
    viewHeight: Int,
    geometry: StreamGeometry,
    surfaceTextureMatrix: FloatArray,
): FloatArray {
    val destAspect = if (rect.height == 0f) 1f else rect.width / rect.height
    val corners = floatArrayOf(
        0f, 0f,
        0f, 1f,
        1f, 0f,
        1f, 1f,
    )
    val out = FloatArray(24)
    var index = 0
    var corner = 0
    while (corner < corners.size) {
        val lu = corners[corner]
        val lv = corners[corner + 1]
        val x = rect.left + lu * rect.width
        val y = rect.top + lv * rect.height
        val (tu, tv) = sampleTexCoord(lu, lv, destAspect, geometry, surfaceTextureMatrix)
        out[index++] = if (viewWidth == 0) 0f else x / viewWidth * 2f - 1f
        out[index++] = if (viewHeight == 0) 0f else 1f - y / viewHeight * 2f
        out[index++] = tu
        out[index++] = tv
        out[index++] = lu
        out[index++] = lv
        corner += 2
    }
    return out
}

internal fun centerCropUv(lu: Float, lv: Float, destAspect: Float, srcAspect: Float): Pair<Float, Float> {
    if (abs(destAspect - srcAspect) < 0.0005f) return lu to lv
    return if (srcAspect > destAspect) {
        val visible = destAspect / srcAspect
        ((1f - visible) / 2f + lu * visible) to lv
    } else {
        val visible = srcAspect / destAspect
        lu to ((1f - visible) / 2f + lv * visible)
    }
}

internal fun uprightToBufferUv(su: Float, sv: Float, rotationClockwise: Int): Pair<Float, Float> {
    return when (rotationClockwise) {
        90 -> sv to (1f - su)
        180 -> (1f - su) to (1f - sv)
        270 -> (1f - sv) to su
        else -> su to sv
    }
}

internal fun applyStMatrix(matrix: FloatArray, x: Float, y: Float): Pair<Float, Float> {
    if (matrix.size < 16) return x to y
    val ox = matrix[0] * x + matrix[4] * y + matrix[12]
    val oy = matrix[1] * x + matrix[5] * y + matrix[13]
    val ow = matrix[3] * x + matrix[7] * y + matrix[15]
    return if (abs(ow) > 0.0001f && abs(ow - 1f) > 0.0001f) ox / ow to oy / ow else ox to oy
}

/** Crop rect mapped into the upright image, as fractions of that image. */
internal fun orientedCropNorm(geometry: StreamGeometry): FloatArray {
    val rot = normalizeRotation(geometry.rotationDegrees)
    val bw = geometry.bufferWidth.toFloat().coerceAtLeast(1f)
    val bh = geometry.bufferHeight.toFloat().coerceAtLeast(1f)
    val uprightW = if (rot == 90 || rot == 270) bh else bw
    val uprightH = if (rot == 90 || rot == 270) bw else bh
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    val xs = floatArrayOf(geometry.cropLeft.toFloat(), geometry.cropRight.toFloat())
    val ys = floatArrayOf(geometry.cropTop.toFloat(), geometry.cropBottom.toFloat())
    for (x in xs) {
        for (y in ys) {
            val (ux, uy) = rotateBufferToUpright(x, y, bw, bh, rot)
            minX = minOf(minX, ux)
            maxX = maxOf(maxX, ux)
            minY = minOf(minY, uy)
            maxY = maxOf(maxY, uy)
        }
    }
    return floatArrayOf(
        (minX / uprightW).coerceIn(0f, 1f),
        (minY / uprightH).coerceIn(0f, 1f),
        (maxX / uprightW).coerceIn(0f, 1f),
        (maxY / uprightH).coerceIn(0f, 1f),
    )
}

internal fun rotateBufferToUpright(
    x: Float,
    y: Float,
    bufferWidth: Float,
    bufferHeight: Float,
    rotation: Int,
): Pair<Float, Float> {
    return when (normalizeRotation(rotation)) {
        90 -> (bufferHeight - y) to x
        180 -> (bufferWidth - x) to (bufferHeight - y)
        270 -> y to (bufferWidth - x)
        else -> x to y
    }
}

internal fun normalizeRotation(degrees: Int): Int {
    val wrapped = ((degrees % 360) + 360) % 360
    return when (wrapped) {
        90, 180, 270 -> wrapped
        else -> 0
    }
}

fun identityStMatrix(): FloatArray = FloatArray(16).also {
    it[0] = 1f
    it[5] = 1f
    it[10] = 1f
    it[15] = 1f
}

/** Column-major Y flip commonly returned by SurfaceTexture. */
fun yFlipStMatrix(): FloatArray = FloatArray(16).also {
    it[0] = 1f
    it[5] = -1f
    it[10] = 1f
    it[13] = 1f
    it[15] = 1f
}
