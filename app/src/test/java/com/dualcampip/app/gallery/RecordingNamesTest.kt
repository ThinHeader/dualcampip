package com.dualcampip.app.gallery

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingNamesTest {
    @Test
    fun fileNameUsesLocalStamp() {
        val time = ZonedDateTime.of(2026, 11, 7, 15, 28, 30, 128_000_000, ZoneId.of("Asia/Shanghai"))
        assertEquals("DualCam_20261107_152830_128.mp4", recordingDisplayName(time))
    }
}
