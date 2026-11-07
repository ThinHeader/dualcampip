package com.dualcampip.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
