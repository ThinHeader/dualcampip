package com.dualcampip.app.encode

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackTimestampsTest {
    @Test
    fun absoluteUptimeBecomesTheRealClipLength() {
        val clock = TrackTimestamps()
        val start = 25 * 3_600_000_000L + 32 * 60_000_000L + 31_000_000L
        assertEquals(0L, clock.rebase(start))
        assertEquals(33_333L, clock.rebase(start + 33_333L))
        assertEquals(8_000_000L, clock.rebase(start + 8_000_000L))
    }

    @Test
    fun alreadyZeroBasedTimestampsStayPut() {
        val clock = TrackTimestamps()
        assertEquals(0L, clock.rebase(0L))
        assertEquals(1_000_000L, clock.rebase(1_000_000L))
    }

    @Test
    fun backwardsTimestampDoesNotGoBackwards() {
        val clock = TrackTimestamps()
        assertEquals(0L, clock.rebase(5_000_000L))
        assertEquals(1L, clock.rebase(4_000_000L))
        assertEquals(1_000L, clock.rebase(5_001_000L))
    }
}
