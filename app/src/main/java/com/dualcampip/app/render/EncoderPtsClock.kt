package com.dualcampip.app.render

/**
 * Presentation timestamps for the encoder surface, in nanoseconds.
 *
 * [android.graphics.SurfaceTexture.getTimestamp] and [System.nanoTime] are not
 * the same clock on every device. Subtracting them leaves an offset around the
 * time since boot, which shows up as a multi-hour gallery duration. Frames from
 * the camera stay on the camera clock; the first camera timestamp is zero.
 */
internal class EncoderPtsClock(
    private val minIntervalNs: Long,
) {
    private var originNs = Long.MIN_VALUE
    private var originIsCamera = false
    private var lastNs = 0L
    private var emitted = false

    fun reset() {
        originNs = Long.MIN_VALUE
        originIsCamera = false
        lastNs = 0L
        emitted = false
    }

    /** A timestamp to pass to EGL, or null when this frame is too soon. */
    fun take(textureTimestampNs: Long, nanoTimeNs: Long): Long? {
        val fromCamera = textureTimestampNs > 0L
        val source = if (fromCamera) textureTimestampNs else nanoTimeNs
        if (originNs == Long.MIN_VALUE || (fromCamera && !originIsCamera)) {
            originNs = source
            originIsCamera = fromCamera
            lastNs = 0L
            emitted = false
        }
        val raw = (source - originNs).coerceAtLeast(0L)
        val pts = raw.coerceAtLeast(if (emitted) lastNs + 1_000_000L else 1_000_000L)
        if (emitted && pts - lastNs < minIntervalNs) return null
        emitted = true
        lastNs = pts
        return pts
    }
}
