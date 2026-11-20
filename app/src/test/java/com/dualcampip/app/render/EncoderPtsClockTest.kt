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
    fun switchingOntoTheCameraClockKeepsTheTimelineMovingForward() {
        val clock = EncoderPtsClock(minIntervalNs = 30_000_000L)
        val first = clock.take(textureTimestampNs = 0L, nanoTimeNs = 50_000_000_000L)!!
        assertEquals(1_000_000L, first)
        val camera = clock.take(textureTimestampNs = 90_000_000_000_000L, nanoTimeNs = 50_000_000_000L)!!
        // The camera clock shares nothing with nanoTime, but the file must not restart at zero.
        assertEquals(first + 30_000_000L, camera)
    }

    @Test
    fun swappingCamerasContinuesTheTimeline() {
        val clock = EncoderPtsClock(minIntervalNs = 30_000_000L)
        val rearNs = 10_000_000_000L
        assertEquals(1_000_000L, clock.take(textureTimestampNs = rearNs, nanoTimeNs = 0L))
        val beforeSwap = clock.take(textureTimestampNs = rearNs + 40_000_000L, nanoTimeNs = 0L)!!
        assertEquals(40_000_000L, beforeSwap)

        // The front camera reports its own sensor clock, far behind the rear one.
        clock.reanchor()
        val frontNs = 500_000_000L
        val afterSwap = clock.take(textureTimestampNs = frontNs, nanoTimeNs = 0L)!!
        assertEquals(beforeSwap + 30_000_000L, afterSwap)
        assertEquals(afterSwap + 40_000_000L, clock.take(textureTimestampNs = frontNs + 40_000_000L, nanoTimeNs = 0L))
    }

    @Test
    fun reanchorBeforeTheFirstFrameChangesNothing() {
        val clock = EncoderPtsClock(minIntervalNs = 30_000_000L)
        clock.reanchor()
        assertEquals(1_000_000L, clock.take(textureTimestampNs = 7_000_000_000L, nanoTimeNs = 0L))
    }
}
