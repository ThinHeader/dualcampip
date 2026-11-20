package com.dualcampip.app.ui

import com.dualcampip.app.model.CameraSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DualCamUiStateTest {
    @Test
    fun pipAspectFollowsWhicheverCameraIsInTheWindow() {
        val state = DualCamUiState(
            frontAspect = 0.75f,
            rearAspect = 0.5625f,
            pipSlot = CameraSlot.FRONT,
        )
        assertEquals(0.75f, state.pipAspect, 0.0001f)
        assertEquals(0.5625f, state.copy(pipSlot = CameraSlot.REAR).pipAspect, 0.0001f)
    }

    @Test
    fun swapIsOnlyAllowedWhenBothCamerasStream() {
        assertTrue(DualCamUiState(phase = Phase.Preview).dualActive)
        assertFalse(
            DualCamUiState(phase = Phase.Preview, unsupportedMessage = "rear only").dualActive,
        )
        assertFalse(DualCamUiState(phase = Phase.Probing).dualActive)
        assertFalse(DualCamUiState(phase = Phase.Failed).dualActive)
    }

    @Test
    fun pipIsVisibleUntilHidden() {
        assertTrue(DualCamUiState().pipVisible)
        assertEquals(CameraSlot.FRONT, DualCamUiState().pipSlot)
    }
}
