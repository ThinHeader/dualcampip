package com.dualcampip.app.render

import com.dualcampip.app.model.PipConfig
import com.dualcampip.app.model.PipCorner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipGeometryTest {
    @Test
    fun defaultBottomEndIsAboutQuarterWidthAtBottomRight() {
        val frameW = 720f
        val frameH = 1280f
        val contentAspect = frameW / frameH
        val rect = pipRect(
            frameWidth = frameW,
            frameHeight = frameH,
            contentAspect = contentAspect,
            corner = PipCorner.BOTTOM_END,
            widthFraction = PipConfig.DEFAULT_WIDTH_FRACTION,
            marginFraction = PipConfig.MARGIN_FRACTION,
            startIsLeft = true,
        )
        assertEquals(180f, rect.width, 0.5f)
        assertEquals(320f, rect.height, 0.5f)
        assertEquals(511.2f, rect.left, 0.5f)
        assertEquals(931.2f, rect.top, 0.5f)
    }

    @Test
    fun coverFrameFillsATallerViewByOverflowingTheSides() {
        val frame = coverFrame(viewWidth = 1080f, viewHeight = 2400f, contentAspect = 720f / 1280f)
        assertEquals(2400f, frame.height, 0.5f)
        assertEquals(0f, frame.top, 0.5f)
        assertTrue(frame.width > 1080f)
        assertTrue(frame.left < 0f)
        assertEquals(720f / 1280f, frame.width / frame.height, 0.001f)
    }

    @Test
    fun coverFrameMatchesAnExactPortraitFrame() {
        val frame = coverFrame(viewWidth = 720f, viewHeight = 1280f, contentAspect = 720f / 1280f)
        assertEquals(0f, frame.left, 0.5f)
        assertEquals(0f, frame.top, 0.5f)
        assertEquals(720f, frame.width, 0.5f)
        assertEquals(1280f, frame.height, 0.5f)
    }

    @Test
    fun topStartSitsOnTheMargin() {
        val rect = pipRect(
            frameWidth = 720f,
            frameHeight = 1280f,
            contentAspect = 720f / 1280f,
            corner = PipCorner.TOP_START,
            widthFraction = 0.25f,
            marginFraction = 0.04f,
            startIsLeft = true,
        )
        assertEquals(28.8f, rect.left, 0.5f)
        assertEquals(28.8f, rect.top, 0.5f)
    }

    @Test
    fun endCornerFlipsInRtl() {
        val ltr = pipRect(200f, 400f, 0.5f, PipCorner.BOTTOM_END, 0.25f, 0f, startIsLeft = true)
        val rtl = pipRect(200f, 400f, 0.5f, PipCorner.BOTTOM_END, 0.25f, 0f, startIsLeft = false)
        assertEquals(ltr.left, 200f - rtl.right, 0.5f)
    }

    @Test
    fun radiusUsesTheShortSide() {
        val rect = PixelRect(0f, 0f, 180f, 320f)
        assertEquals(0f, pipCornerRadiusPx(rect, 0f), 0.01f)
        assertEquals(31.5f, pipCornerRadiusPx(rect, 0.35f), 0.2f)
    }
}
