package com.dualcampip.app.ui

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
)

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
