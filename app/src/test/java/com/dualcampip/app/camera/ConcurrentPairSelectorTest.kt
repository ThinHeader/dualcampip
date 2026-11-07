package com.dualcampip.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcurrentPairSelectorTest {
    @Test
    fun prefersTheDedicatedFrontBackPair() {
        val cameras = listOf(
            CameraIdInfo("0", LensFacing.BACK),
            CameraIdInfo("1", LensFacing.BACK),
            CameraIdInfo("2", LensFacing.FRONT),
        )
        val sets = setOf(setOf("0", "1", "2"), setOf("0", "2"))
        val pair = selectConcurrentPair(sets, cameras)
        assertEquals("0", pair?.first?.id)
        assertEquals("2", pair?.second?.id)
    }

    @Test
    fun ignoresBackOnlySets() {
        val cameras = listOf(
            CameraIdInfo("0", LensFacing.BACK),
            CameraIdInfo("1", LensFacing.BACK),
        )
        assertNull(selectConcurrentPair(setOf(setOf("0", "1")), cameras))
    }

    @Test
    fun unsetSelectorFacingsAreNotAFrontBackPair() {
        // CameraX reports null lensFacing when requireLensFacing was never called.
        // null == null is the path that binds both previews to the first camera.
        assertFalse(selectorFacingsAreRearMainFrontPip(null, null, back = BACK, front = FRONT))
    }

    @Test
    fun sameOrSwappedSelectorFacingsAreRejected() {
        assertFalse(selectorFacingsAreRearMainFrontPip(BACK, BACK, back = BACK, front = FRONT))
        assertFalse(selectorFacingsAreRearMainFrontPip(FRONT, FRONT, back = BACK, front = FRONT))
        assertFalse(selectorFacingsAreRearMainFrontPip(FRONT, BACK, back = BACK, front = FRONT))
    }

    @Test
    fun rearMainAndFrontPipSelectorsAreAccepted() {
        assertTrue(selectorFacingsAreRearMainFrontPip(BACK, FRONT, back = BACK, front = FRONT))
    }

    @Test
    fun openedPairMustBeDistinctRearThenFront() {
        assertTrue(openedCamerasAreRearMainFrontPip(LensFacing.BACK, LensFacing.FRONT, "0", "1"))
        assertFalse(openedCamerasAreRearMainFrontPip(LensFacing.BACK, LensFacing.BACK, "0", "2"))
        assertFalse(openedCamerasAreRearMainFrontPip(LensFacing.FRONT, LensFacing.FRONT, "1", "3"))
        assertFalse(openedCamerasAreRearMainFrontPip(LensFacing.FRONT, LensFacing.BACK, "1", "0"))
        assertFalse(openedCamerasAreRearMainFrontPip(LensFacing.BACK, LensFacing.FRONT, "0", "0"))
        assertFalse(openedCamerasAreRearMainFrontPip(LensFacing.BACK, LensFacing.FRONT, "", "1"))
    }

    private companion object {
        const val BACK = 1
        const val FRONT = 0
    }
}
