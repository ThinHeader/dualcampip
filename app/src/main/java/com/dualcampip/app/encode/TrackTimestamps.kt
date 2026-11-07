package com.dualcampip.app.encode

/**
 * Makes one MP4 track start at zero.
 *
 * Encoders sometimes emit absolute monotonic timestamps (nanoseconds since boot,
 * divided into microseconds). MediaMuxer then stores that value, and gallery
 * apps report it as the duration. Subtracting the first sample's timestamp
 * leaves the real length.
 */
internal class TrackTimestamps {
    private var baseUs = Long.MIN_VALUE
    private var lastUs = -1L

    fun rebase(ptsUs: Long): Long {
        if (baseUs == Long.MIN_VALUE) baseUs = ptsUs
        var relative = ptsUs - baseUs
        if (relative < 0L) relative = 0L
        if (lastUs >= 0L && relative <= lastUs) relative = lastUs + 1L
        lastUs = relative
        return relative
    }
}
