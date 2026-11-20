package com.dualcampip.app.ui

import com.dualcampip.app.model.CameraSlot
import com.dualcampip.app.model.PipConfig

data class DualCamUiState(
    val permission: PermissionState = PermissionState.Unknown,
    val phase: Phase = Phase.Idle,
    val backendLabel: String? = null,
    val unsupportedMessage: String? = null,
    val statusMessage: String? = null,
    val canRecord: Boolean = false,
    val recording: RecordingUi = RecordingUi.Idle,
    val pip: PipConfig = PipConfig.Default,
    val frontAspect: Float = DEFAULT_ASPECT,
    val rearAspect: Float = DEFAULT_ASPECT,
    /** Camera shown in the small window. The other one fills the screen. */
    val pipSlot: CameraSlot = CameraSlot.FRONT,
    /** False hides the small window from the preview and from the recording. */
    val pipVisible: Boolean = true,
) {
    /** Upright aspect (width/height) of whichever stream currently sits in the PiP. */
    val pipAspect: Float
        get() = when (pipSlot) {
            CameraSlot.FRONT -> frontAspect
            CameraSlot.REAR -> rearAspect
        }

    /** True only when a real front+back pair is streaming; rear-only previews are not swappable. */
    val dualActive: Boolean
        get() = phase == Phase.Preview && unsupportedMessage == null

    companion object {
        const val DEFAULT_ASPECT = 9f / 16f
    }
}

enum class PermissionState {
    Unknown,
    Granted,
    Denied,
}

enum class Phase {
    Idle,
    Probing,
    Preview,
    Failed,
}

sealed interface RecordingUi {
    data object Idle : RecordingUi
    data class Active(val startedAtElapsedMs: Long) : RecordingUi
    data object Saving : RecordingUi
    data class Saved(val displayName: String) : RecordingUi
    data class Failed(val message: String) : RecordingUi
}
