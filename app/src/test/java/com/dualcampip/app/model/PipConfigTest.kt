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

    @Test
    fun freePositionRequiresBothEdges() {
        val config = PipConfig(leftFraction = 1.4f, topFraction = null).sanitized()
        assertEquals(null, config.leftFraction)
        assertEquals(null, config.topFraction)
        val placed = PipConfig(leftFraction = -0.2f, topFraction = 0.5f).sanitized()
        assertEquals(0f, placed.leftFraction ?: -1f, 0.001f)
        assertEquals(0.5f, placed.topFraction ?: -1f, 0.001f)
    }
}
