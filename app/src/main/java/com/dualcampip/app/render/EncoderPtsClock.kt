package com.dualcampip.app.render

/**
 * Presentation timestamps for the encoder surface, in nanoseconds.
 *
 * [android.graphics.SurfaceTexture.getTimestamp] and [System.nanoTime] are not
 * the same clock on every device. Subtracting them leaves an offset around the
 * time since boot, which shows up as a multi-hour gallery duration. Frames from
 * the camera stay on the camera clock; the first camera timestamp is zero.
 *
 * Two cameras do not have to share a clock, so [reanchor] exists for the case
 * where the frame source changes mid-recording (the user swaps which camera is
 * fullscreen). The new clock becomes the origin, but the output timeline
 * continues from the last emitted frame instead of restarting at zero: the
 * muxer would otherwise see the timestamps jump backwards and squeeze every
 * following frame 1 µs apart, or leave a multi-second hole in the file.
 */
internal class EncoderPtsClock(
    private val minIntervalNs: Long,
) {
    private var originSourceNs = Long.MIN_VALUE
    /** Output time that [originSourceNs] maps to, so a new source can continue the timeline. */
    private var outputBaseNs = 0L
    private var originIsCamera = false
    private var lastNs = 0L
    private var emitted = false
    private var pendingReanchor = false

    fun reset() {
        originSourceNs = Long.MIN_VALUE
        outputBaseNs = 0L
        originIsCamera = false
        lastNs = 0L
        emitted = false
        pendingReanchor = false
    }

    /** Call when the next frame comes from a different clock than the current origin. */
    fun reanchor() {
        if (emitted) pendingReanchor = true
    }

    /** A timestamp to pass to EGL, or null when this frame is too soon. */
    fun take(textureTimestampNs: Long, nanoTimeNs: Long): Long? {
        val fromCamera = textureTimestampNs > 0L
        val source = if (fromCamera) textureTimestampNs else nanoTimeNs
        val sourceChanged = originSourceNs == Long.MIN_VALUE ||
            pendingReanchor ||
            (fromCamera && !originIsCamera)
        if (sourceChanged) {
            originSourceNs = source
            originIsCamera = fromCamera
            outputBaseNs = if (emitted) lastNs + minIntervalNs else 0L
            pendingReanchor = false
        }
        val elapsed = (source - originSourceNs).coerceAtLeast(0L)
        val raw = outputBaseNs + elapsed
        val pts = raw.coerceAtLeast(if (emitted) lastNs + 1_000_000L else 1_000_000L)
        if (emitted && pts - lastNs < minIntervalNs) return null
        emitted = true
        lastNs = pts
        return pts
    }
}
