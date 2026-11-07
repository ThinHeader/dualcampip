package com.dualcampip.app.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EncoderPtsClockTest {
    @Test
    fun cameraTimestampsAreNotSubtractedFromNanoTime() {
        val clock = EncoderPtsClock(minIntervalNs = 30_000_000L)
        val bootNs = 25L * 3_600_000_000_000L + 32L * 60_000_000_000L + 31_000_000_000L
        val nanoTime = 3_000_000_000L
        val first = clock.take(textureTimestampNs = bootNs, nanoTimeNs = nanoTime)
        val second = clock.take(textureTimestampNs = bootNs + 40_000_000L, nanoTimeNs = nanoTime + 40_000_000L)
        assertEquals(1_000_000L, first)
        assertEquals(40_000_000L, second)
    }

    @Test
    fun dropsFramesCloserThanTheInterval() {
        val clock = EncoderPtsClock(minIntervalNs = 30_000_000L)
        clock.take(textureTimestampNs = 1_000_000_000L, nanoTimeNs = 0L)
        assertNull(clock.take(textureTimestampNs = 1_000_000_000L + 10_000_000L, nanoTimeNs = 0L))
    }

    @Test
    fun switchesOntoTheCameraClockWhenItAppears() {
        val clock = EncoderPtsClock(minIntervalNs = 30_000_000L)
        clock.take(textureTimestampNs = 0L, nanoTimeNs = 50_000_000_000L)
        val camera = clock.take(textureTimestampNs = 90_000_000_000_000L, nanoTimeNs = 50_000_000_000L)
        assertEquals(1_000_000L, camera)
    }
}
