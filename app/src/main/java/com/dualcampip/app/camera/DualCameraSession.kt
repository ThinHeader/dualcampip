package com.dualcampip.app.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import com.dualcampip.app.model.CameraSlot
import com.dualcampip.app.model.StreamGeometry

/**
 * Opens front + rear together. CameraX concurrent binding is tried first.
 * Fullscreen must be the rear camera and PiP the front camera. A bind that
 * opens two streams of the same facing is rejected. Camera2 multi-open is used
 * when CameraX is missing, throws, or cannot prove a front+back pair. Otherwise
 * only the rear camera is previewed and recording stays disabled.
 */
class DualCameraSession(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onDualReady(backendLabel: String)
        fun onUnsupported(message: String)
        fun onStream(slot: CameraSlot, geometry: StreamGeometry)
        fun onFatal(message: String)
    }

    private val cameraX = CameraXDualBackend(context)
    private val camera2 = Camera2DualBackend(context)
    private val main = Handler(Looper.getMainLooper())
    private var owner: LifecycleOwner? = null
    private var rearTexture: SurfaceTexture? = null
    private var frontTexture: SurfaceTexture? = null
    private var rotation: Int = 0
    private var camera2Available = false
    private var mode = Mode.IDLE
    private var unsupportedMessage = ""
    private var openFailedMessage = ""
    private var sameFacingMessage = ""

    fun start(
        owner: LifecycleOwner,
        provider: ProcessCameraProvider,
        rearTexture: SurfaceTexture,
        frontTexture: SurfaceTexture,
        displayRotation: Int,
        unsupportedMessage: String,
        openFailedMessage: String,
        sameFacingMessage: String,
    ) {
        this.owner = owner
        this.rearTexture = rearTexture
        this.frontTexture = frontTexture
        this.rotation = displayRotation
        this.unsupportedMessage = unsupportedMessage
        this.openFailedMessage = openFailedMessage
        this.sameFacingMessage = sameFacingMessage
        cameraX.bindProvider(provider)
        val cameraXPair = runCatching { cameraX.findFrontBack() }.getOrNull()
        camera2Available = runCatching { camera2.findFrontBackIds() != null }.getOrDefault(false)
        Log.i(TAG, "concurrent probe cameraX=${cameraXPair != null} camera2=$camera2Available")
        if (cameraXPair == null && !camera2Available) {
            openRearOnly(unsupportedMessage)
            return
        }
        if (cameraXPair != null) {
            when (openCameraX()) {
                OpenResult.OPENED -> {
                    mode = Mode.CAMERAX
                    listener.onDualReady("CameraX")
                    return
                }
                OpenResult.SAME_FACING -> {
                    if (camera2Available) {
                        openCamera2()
                    } else {
                        openRearOnly(sameFacingMessage)
                    }
                    return
                }
                OpenResult.FAILED -> Unit
            }
        }
        if (camera2Available) {
            openCamera2()
            return
        }
        openRearOnly(openFailedMessage)
    }

    fun stop() {
        mode = Mode.IDLE
        cameraX.close()
        camera2.close()
    }

    fun release() {
        stop()
        camera2.release()
    }

    private fun openCameraX(): OpenResult {
        val lifecycle = owner ?: return OpenResult.FAILED
        val rear = rearTexture ?: return OpenResult.FAILED
        val front = frontTexture ?: return OpenResult.FAILED
        return try {
            cameraX.openConcurrent(
                owner = lifecycle,
                rearTexture = rear,
                frontTexture = front,
                displayRotation = rotation,
                onStream = ::emitStream,
                onCameraError = { reason ->
                    Log.e(TAG, "CameraX runtime error: $reason")
                    main.post { onCameraXRuntimeFailure() }
                },
            )
            OpenResult.OPENED
        } catch (thrown: SameFacingCamerasException) {
            Log.e(TAG, "CameraX did not open a front+back pair", thrown)
            cameraX.close()
            OpenResult.SAME_FACING
        } catch (thrown: RuntimeException) {
            Log.e(TAG, "CameraX concurrent bind failed", thrown)
            cameraX.close()
            OpenResult.FAILED
        }
    }

    private fun onCameraXRuntimeFailure() {
        if (mode != Mode.CAMERAX) return
        mode = Mode.IDLE
        cameraX.close()
        if (camera2Available) {
            openCamera2()
        } else {
            openRearOnly(openFailedMessage)
        }
    }

    private fun openCamera2() {
        val rear = rearTexture ?: return
        val front = frontTexture ?: return
        mode = Mode.CAMERA2
        cameraX.close()
        camera2.openConcurrent(
            rearTexture = rear,
            frontTexture = front,
            displayRotation = rotation,
            onStream = { slot, geometry -> main.post { emitStream(slot, geometry) } },
            onOpened = { main.post { if (mode == Mode.CAMERA2) listener.onDualReady("Camera2") } },
            onFailed = { reason ->
                Log.e(TAG, "Camera2 dual open failed: $reason")
                main.post {
                    if (mode != Mode.CAMERA2) return@post
                    mode = Mode.IDLE
                    val message = if (reason == SAME_FACING_FAILURE) sameFacingMessage else openFailedMessage
                    openRearOnly(message)
                }
            },
        )
    }

    private fun openRearOnly(message: String) {
        val lifecycle = owner ?: return
        val rear = rearTexture ?: return
        mode = Mode.REAR_ONLY
        cameraX.close()
        camera2.close()
        val opened = runCatching {
            cameraX.openRearOnly(lifecycle, rear, rotation, ::emitStream)
        }.onFailure { Log.e(TAG, "rear preview via CameraX failed", it) }.isSuccess
        if (!opened) {
            camera2.openRearOnly(
                rearTexture = rear,
                displayRotation = rotation,
                onStream = { slot, geometry -> main.post { emitStream(slot, geometry) } },
                onOpened = { main.post { listener.onUnsupported(message) } },
                onFailed = { reason ->
                    Log.e(TAG, "rear preview failed: $reason")
                    main.post { listener.onFatal(message) }
                },
            )
            return
        }
        listener.onUnsupported(message)
    }

    private fun emitStream(slot: CameraSlot, geometry: StreamGeometry) {
        listener.onStream(slot, geometry)
    }

    private enum class Mode {
        IDLE,
        CAMERAX,
        CAMERA2,
        REAR_ONLY,
    }

    private enum class OpenResult {
        OPENED,
        FAILED,
        SAME_FACING,
    }

    private companion object {
        const val TAG = "DualCamPiP"
    }
}
