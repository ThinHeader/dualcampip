package com.dualcampip.app.render

import com.dualcampip.app.model.StreamGeometry
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameTransformTest {
    @Test
    fun yFlipMatrixPutsTopLeftAtTextureOrigin() {
        val geometry = StreamGeometry(
            bufferWidth = 160,
            bufferHeight = 90,
            rotationDegrees = 0,
            mirror = false,
        )
        val (u, v) = sampleTexCoord(
            lu = 0f,
            lv = 0f,
            destAspect = 160f / 90f,
            geometry = geometry,
            surfaceTextureMatrix = yFlipStMatrix(),
        )
        assertEquals(0f, u, 0.001f)
        assertEquals(0f, v, 0.001f)
    }

    @Test
    fun yFlipMatrixPutsBottomLeftAtTextureBottom() {
        val geometry = StreamGeometry(160, 90, 0, mirror = false)
        val (u, v) = sampleTexCoord(0f, 1f, 160f / 90f, geometry, yFlipStMatrix())
        assertEquals(0f, u, 0.001f)
        assertEquals(1f, v, 0.001f)
    }

    @Test
    fun cameraTransformLeavesSensorRotationToTheSurfaceMatrix() {
        val geometry = StreamGeometry(
            bufferWidth = 1280,
            bufferHeight = 720,
            rotationDegrees = 90,
            mirror = false,
            cameraAppliesTransform = true,
        )
        val (u, v) = sampleTexCoord(
            lu = 0f,
            lv = 0f,
            destAspect = 720f / 1280f,
            geometry = geometry,
            surfaceTextureMatrix = yFlipStMatrix(),
        )
        // A direct camera surface already rotates in getTransformMatrix. Applying the
        // sensor rotation again is what turned the portrait preview on its side.
        assertEquals(0f, u, 0.001f)
        assertEquals(0f, v, 0.001f)
    }

    @Test
    fun fullSensorCropStaysTheWholeUprightFrame() {
        val bounds = orientedCropNorm(StreamGeometry(1280, 720, 90, mirror = false))
        assertEquals(0f, bounds[0], 0.001f)
        assertEquals(0f, bounds[1], 0.001f)
        assertEquals(1f, bounds[2], 0.001f)
        assertEquals(1f, bounds[3], 0.001f)
    }

    @Test
    fun rotation90WithYFlipSamplesThePortraitTopLeft() {
        val geometry = StreamGeometry(1280, 720, 90, mirror = false)
        val (u, v) = sampleTexCoord(
            lu = 0f,
            lv = 0f,
            destAspect = 720f / 1280f,
            geometry = geometry,
            surfaceTextureMatrix = yFlipStMatrix(),
        )
        assertEquals(0f, u, 0.001f)
        assertEquals(1f, v, 0.001f)
    }

    @Test
    fun mirrorFlipsTheUprightTopLeft() {
        val geometry = StreamGeometry(100, 100, 0, mirror = true)
        val (u, v) = sampleTexCoord(0f, 0f, 1f, geometry, identityStMatrix())
        assertEquals(1f, u, 0.001f)
        assertEquals(1f, v, 0.001f)
    }

    @Test
    fun fullFrameQuadTopLeftIsNormalizedDeviceTopLeft() {
        val vertices = buildQuadVertices(
            rect = PixelRect(0f, 0f, 720f, 1280f),
            viewWidth = 720,
            viewHeight = 1280,
            geometry = StreamGeometry(1280, 720, 90, mirror = false),
            surfaceTextureMatrix = identityStMatrix(),
        )
        assertEquals(-1f, vertices[0], 0.001f)
        assertEquals(1f, vertices[1], 0.001f)
    }
}
