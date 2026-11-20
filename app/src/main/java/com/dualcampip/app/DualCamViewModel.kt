package com.dualcampip.app

import android.app.Application
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.dualcampip.app.engine.DualCamEngine
import com.dualcampip.app.model.PipConfig
import kotlinx.coroutines.flow.StateFlow

class DualCamViewModel(app: Application) : AndroidViewModel(app) {
    private val engine = DualCamEngine(app, viewModelScope)

    val state: StateFlow<com.dualcampip.app.ui.DualCamUiState> = engine.state

    fun attachLifecycle(owner: LifecycleOwner) = engine.attachLifecycle(owner)

    fun onPermissions(camera: Boolean, audio: Boolean) = engine.onPermissions(camera, audio)

    fun onSurfaceAvailable(surface: Surface, width: Int, height: Int, rotation: Int) {
        engine.onSurfaceAvailable(surface, width, height, rotation)
    }

    fun onSurfaceDestroyed() = engine.onSurfaceDestroyed()

    fun onHostStart() = engine.onHostStart()

    fun onHostStop() = engine.onHostStop()

    fun startRecording() = engine.startRecording()

    fun stopRecording() = engine.stopRecording()

    fun updatePip(config: PipConfig) = engine.updatePip(config)

    fun swapPipCamera() = engine.swapPipCamera()

    fun togglePipVisibility() = engine.togglePipVisibility()

    override fun onCleared() {
        engine.releaseAsync()
    }
}
