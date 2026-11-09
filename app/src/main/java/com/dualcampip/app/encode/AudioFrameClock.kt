package com.dualcampip.app.encode

/**
 * Presentation time for each AAC access unit, in microseconds.
 *
 * One PCM buffer often produces several AAC frames, and those frames come back
 * with the same encoder timestamp. Collapsing the ties to 1 µs makes playback
 * run fast and drop pieces of speech. Spacing every frame by
 * [samplesPerFrame] / [sampleRate] keeps the recorded voice at the speed it
 * was spoken.
 */
internal class AudioFrameClock(
    private val sampleRate: Int,
    private val samplesPerFrame: Int,
) {
    private var frameIndex = 0L

    fun nextPtsUs(): Long {
        val pts = frameIndex * samplesPerFrame.toLong() * 1_000_000L / sampleRate
        frameIndex += 1
        return pts
    }
}
