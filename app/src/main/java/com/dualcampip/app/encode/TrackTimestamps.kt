package com.dualcampip.app.encode

/**
 * Makes one MP4 track start at zero.
 *
 * Video encoders sometimes emit absolute monotonic timestamps. Subtracting the
 * first sample leaves the real length. Audio does not use this: several AAC
 * frames often share one timestamp, and forcing those ties 1 µs apart makes
 * speech play too fast.
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
