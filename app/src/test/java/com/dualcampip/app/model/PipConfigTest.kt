package com.dualcampip.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PipConfigTest {
    @Test
    fun sanitizesOutOfRangeValues() {
        val config = PipConfig(
            corner = PipCorner.TOP_START,
            widthFraction = 0.9f,
            radiusFraction = -1f,
        ).sanitized()
        assertEquals(PipConfig.MAX_WIDTH_FRACTION, config.widthFraction)
        assertEquals(0f, config.radiusFraction, 0.001f)
        assertEquals(PipCorner.TOP_START, config.corner)
    }
}
