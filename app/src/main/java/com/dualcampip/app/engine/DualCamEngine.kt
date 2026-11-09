package com.dualcampip.app.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.concurrent.futures.await
import androidx.lifecycle.LifecycleOwner
import com.dualcampip.app.R
import com.dualcampip.app.camera.DualCameraSession
import com.dualcampip.app.encode.CompositeRecorder
import com.dualcampip.app.model.CameraSlot
import com.dualcampip.app.model.PipConfig
import com.dualcampip.app.model.StreamGeometry
import com.dualcampip.app.render.GlCompositor
import com.dualcampip.app.render.uprightAspect
import com.dualcampip.app.settings.PipSettingsStore
import com.dualcampip.app.ui.DualCamUiState
import com.dualcampip.app.ui.PermissionState
import com.dualcampip.app.ui.Phase
import com.dualcampip.app.ui.RecordingUi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Wires the preview surface, camera session, GL compositor, and gallery recorder.
 * Camera binding stays on the main thread. Encoding work runs off the main thread.
 */
class DualCamEngine(
    private val app: Context,
    private val scope: CoroutineScope,
) {
    private val compositor = GlCompositor()
    private val settings = PipSettingsStore(app)
    private val main = Handler(Looper.getMainLooper())
    private val session = DualCameraSession(app, SessionListener())
    private val opMutex = Mutex()
    private val sessionGen = AtomicInteger(0)
    private val released = AtomicBoolean(false)

    private val _state = MutableStateFlow(DualCamUiState(pip = settings.load()))
    val state: StateFlow<DualCamUiState> = _state.asStateFlow()

    private var lifecycleOwner: LifecycleOwner? = null
    private var previewSurface: Surface? = null
    private var previewWidth = 0
    private var previewHeight = 0
    private var displayRotation = Surface.ROTATION_0
    private var cameraGranted = false
    private var audioGranted = false
    private var recorder: CompositeRecorder? = null
    private var recordingActive = false
    private var provider: ProcessCameraProvider? = null

    init {
        compositor.updatePipLayout(settings.load())
        compositor.onBothFrames = {
            scope.launch(Dispatchers.Main) { onBothFrames() }
        }
    }

    fun attachLifecycle(owner: LifecycleOwner) {
        lifecycleOwner = owner
    }

    fun onPermissions(camera: Boolean, audio: Boolean) {
        cameraGranted = camera
        audioGranted = audio
        if (!camera) {
            _state.update {
                it.copy(
                    permission = PermissionState.Denied,
                    phase = Phase.Idle,
                    canRecord = false,
                    unsupportedMessage = null,
                )
            }
            return
        }
        _state.update { it.copy(permission = PermissionState.Granted) }
        val surface = previewSurface ?: return
        onSurfaceAvailable(surface, previewWidth, previewHeight, displayRotation)
    }

    fun onSurfaceAvailable(surface: Surface, width: Int, height: Int, rotation: Int) {
        previewSurface = surface
        previewWidth = width
        previewHeight = height
        displayRotation = rotation
        if (!cameraGranted || width <= 0 || height <= 0) return
        val gen = sessionGen.incrementAndGet()
        scope.launch {
            opMutex.withLock {
                if (!stillCurrent(gen)) return@withLock
                openPipeline(gen)
            }
        }
    }

    fun onSurfaceDestroyed() {
        previewSurface = null
        sessionGen.incrementAndGet()
        runCatching { compositor.detachPreview() }
    }

    fun onHostStart() {
        val surface = previewSurface ?: return
        if (!cameraGranted) return
        onSurfaceAvailable(surface, previewWidth, previewHeight, displayRotation)
    }

    fun onHostStop() {
        sessionGen.incrementAndGet()
        scope.launch {
            opMutex.withLock {
                finishRecordingIfNeeded()
                withContext(Dispatchers.Main) { session.stop() }
                compositor.detachPreview()
            }
        }
    }

    fun startRecording() {
        val snapshot = _state.value
        if (!snapshot.canRecord || recordingActive || snapshot.unsupportedMessage != null) return
        if (!audioGranted) {
            _state.update { it.copy(statusMessage = app.getString(R.string.audio_failed)) }
            return
        }
        scope.launch {
            opMutex.withLock {
                if (released.get() || recordingActive || _state.value.unsupportedMessage != null) return@withLock
                val rec = CompositeRecorder(app)
                try {
                    val encoderSurface = withContext(Dispatchers.IO) { rec.start() }
                    compositor.beginRecording(encoderSurface)
                    recorder = rec
                    recordingActive = true
                    withContext(Dispatchers.Main) {
                        _state.update {
                            it.copy(
                                recording = RecordingUi.Active(SystemClock.elapsedRealtime()),
                                statusMessage = null,
                            )
                        }
                    }
                } catch (thrown: Throwable) {
                    Log.e(TAG, "record start failed", thrown)
                    recorder = null
                    recordingActive = false
                    rec.abort()
                    withContext(Dispatchers.Main) {
                        _state.update {
                            it.copy(recording = RecordingUi.Failed(app.getString(R.string.record_failed)))
                        }
                    }
                }
            }
        }
    }

    fun stopRecording() {
        if (!recordingActive && recorder == null) return
        scope.launch {
            opMutex.withLock { finishRecordingIfNeeded() }
        }
    }

    fun updatePip(config: PipConfig) {
        if (recordingActive) return
        val safe = config.sanitized()
        settings.save(safe)
        compositor.updatePipLayout(safe)
        _state.update { it.copy(pip = safe) }
    }

    fun releaseAsync() {
        if (!released.compareAndSet(false, true)) return
        sessionGen.incrementAndGet()
        thread(name = "DualCamRelease", start = true) {
            runBlocking {
                opMutex.withLock {
                    val rec = recorder
                    recorder = null
                    recordingActive = false
                    if (rec != null) {
                        runCatching { compositor.endRecordingInput { rec.signalVideoEnd() } }
                        runCatching { rec.finish() }.onFailure { runCatching { rec.abort() } }
                        runCatching { compositor.detachEncoder() }
                    }
                    val latch = CountDownLatch(1)
                    main.post {
                        runCatching { session.release() }
                        latch.countDown()
                    }
                    latch.await(2, TimeUnit.SECONDS)
                }
            }
            compositor.release()
        }
    }

    private suspend fun openPipeline(gen: Int) {
        val surface = previewSurface ?: return
        val owner = lifecycleOwner ?: return
        withContext(Dispatchers.Main) {
            if (!stillCurrent(gen)) return@withContext
            _state.update {
                it.copy(
                    phase = Phase.Probing,
                    canRecord = false,
                    unsupportedMessage = null,
                    backendLabel = null,
                    statusMessage = app.getString(R.string.probing),
                    recording = if (it.recording is RecordingUi.Active) it.recording else RecordingUi.Idle,
                )
            }
        }
        if (!stillCurrent(gen)) return
        try {
            compositor.attachPreview(surface, previewWidth, previewHeight)
            compositor.resetFrameGate()
            val cameraProvider = provider ?: ProcessCameraProvider.getInstance(app).await().also { provider = it }
            if (!stillCurrent(gen)) return
            withContext(Dispatchers.Main) {
                if (!stillCurrent(gen)) return@withContext
                session.stop()
                session.start(
                    owner = owner,
                    provider = cameraProvider,
                    rearTexture = compositor.rearTexture(),
                    frontTexture = compositor.frontTexture(),
                    displayRotation = displayRotation,
                    unsupportedMessage = app.getString(R.string.unsupported_body),
                    openFailedMessage = app.getString(R.string.open_failed),
                    sameFacingMessage = app.getString(R.string.same_facing_failed),
                )
            }
        } catch (thrown: Throwable) {
            Log.e(TAG, "pipeline open failed", thrown)
            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(
                        phase = Phase.Failed,
                        canRecord = false,
                        statusMessage = null,
                        unsupportedMessage = app.getString(R.string.open_failed),
                    )
                }
            }
        }
    }

    private suspend fun finishRecordingIfNeeded() {
        val rec = recorder ?: return
        recorder = null
        recordingActive = false
        withContext(Dispatchers.Main) {
            _state.update { it.copy(recording = RecordingUi.Saving, canRecord = false) }
        }
        try {
            compositor.endRecordingInput { rec.signalVideoEnd() }
            val name = withContext(Dispatchers.IO) { rec.finish() }
            compositor.detachEncoder()
            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(
                        recording = RecordingUi.Saved(name),
                        canRecord = it.unsupportedMessage == null && audioGranted,
                    )
                }
            }
        } catch (thrown: Throwable) {
            Log.e(TAG, "save failed", thrown)
            runCatching { compositor.detachEncoder() }
            runCatching { rec.abort() }
            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(
                        recording = RecordingUi.Failed(app.getString(R.string.save_failed)),
                        canRecord = it.unsupportedMessage == null && audioGranted,
                    )
                }
            }
        }
    }

    private fun onBothFrames() {
        if (released.get() || recordingActive) return
        _state.update { current ->
            if (current.unsupportedMessage != null || current.phase != Phase.Preview) current
            else current.copy(
                canRecord = audioGranted,
                statusMessage = app.getString(
                    if (audioGranted) R.string.preview_ready else R.string.audio_failed,
                ),
            )
        }
    }

    private fun stillCurrent(gen: Int): Boolean = !released.get() && gen == sessionGen.get()

    private inner class SessionListener : DualCameraSession.Listener {
        override fun onDualReady(backendLabel: String) {
            compositor.resetFrameGate()
            _state.update {
                it.copy(
                    phase = Phase.Preview,
                    backendLabel = app.getString(R.string.backend_label, backendLabel),
                    unsupportedMessage = null,
                    canRecord = false,
                    statusMessage = app.getString(R.string.waiting_frames),
                )
            }
        }

        override fun onUnsupported(message: String) {
            compositor.resetFrameGate()
            _state.update {
                it.copy(
                    phase = Phase.Preview,
                    backendLabel = app.getString(R.string.backend_rear_only),
                    unsupportedMessage = message,
                    canRecord = false,
                    statusMessage = app.getString(R.string.single_preview_hint),
                )
            }
        }

        override fun onStream(slot: CameraSlot, geometry: StreamGeometry) {
            compositor.updateGeometry(slot, geometry)
            if (slot == CameraSlot.FRONT) {
                val aspect = uprightAspect(geometry)
                _state.update { current ->
                    if (current.frontAspect == aspect) current else current.copy(frontAspect = aspect)
                }
            }
        }

        override fun onFatal(message: String) {
            _state.update {
                it.copy(
                    phase = Phase.Failed,
                    unsupportedMessage = message,
                    canRecord = false,
                    statusMessage = null,
                )
            }
        }
    }

    private companion object {
        const val TAG = "DualCamPiP"
    }
}
