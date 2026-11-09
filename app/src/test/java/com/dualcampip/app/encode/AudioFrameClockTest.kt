package com.dualcampip.app.encode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFrameClockTest {
    @Test
    fun framesAdvanceByOneAacDuration() {
        val clock = AudioFrameClock(sampleRate = 44_100, samplesPerFrame = 1024)
        assertEquals(0L, clock.nextPtsUs())
        assertEquals(23_219L, clock.nextPtsUs())
        assertEquals(46_439L, clock.nextPtsUs())
    }

    @Test
    fun aSecondOfFramesLastsAboutASecond() {
        val clock = AudioFrameClock(sampleRate = 44_100, samplesPerFrame = 1024)
        var last = 0L
        repeat(43) { last = clock.nextPtsUs() }
        val end = last + 1024L * 1_000_000L / 44_100
        assertTrue(end in 990_000L..1_010_000L)
    }
}
