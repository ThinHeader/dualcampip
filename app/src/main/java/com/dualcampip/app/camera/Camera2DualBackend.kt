package com.dualcampip.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import com.dualcampip.app.encode.EncodeConfig
import com.dualcampip.app.model.CameraSlot
import com.dualcampip.app.model.StreamGeometry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Opens the front and back cameras together when Camera2 reports a concurrent pair.
 * Used when CameraX concurrent binding is unavailable or fails.
 */
class Camera2DualBackend(
    context: Context,
) {
    private val manager = context.applicationContext.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("DualCamCamera2").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { command -> handler.post(command) }
    private val generation = AtomicInteger(0)
    private val devices = mutableListOf<CameraDevice>()
    private val sessions = mutableListOf<CameraCaptureSession>()
    private val surfaces = mutableListOf<Surface>()

    fun findFrontBackIds(): Pair<String, String>? {
        val cameras = manager.cameraIdList.map { id ->
            CameraIdInfo(id = id, facing = facingOf(id))
        }
        val pair = selectConcurrentPair(manager.concurrentCameraIds, cameras) ?: return null
        return pair.first.id to pair.second.id
    }

    fun openConcurrent(
        rearTexture: SurfaceTexture,
        frontTexture: SurfaceTexture,
        displayRotation: Int,
        onStream: (CameraSlot, StreamGeometry) -> Unit,
        onOpened: () -> Unit,
        onFailed: (String) -> Unit,
    ) {
        val gen = generation.incrementAndGet()
        handler.post {
            if (gen != generation.get()) return@post
            try {
                closeOnThread()
                val (backId, frontId) = findFrontBackIds()
                    ?: throw IllegalStateException("no camera2 pair")
                val backFacing = facingOf(backId)
                val frontFacing = facingOf(frontId)
                Log.i(TAG, "Camera2 open back=$backId/$backFacing front=$frontId/$frontFacing")
                if (!openedCamerasAreRearMainFrontPip(backFacing, frontFacing, backId, frontId)) {
                    throw SameFacingCamerasException(
                        "Camera2 pair is not rear+front ($backId/$backFacing, $frontId/$frontFacing)",
                    )
                }
                if (rearTexture === frontTexture) {
                    throw SameFacingCamerasException("rear and front preview textures are the same object")
                }
                val pending = AtomicInteger(2)
                val failed = AtomicBoolean(false)
                openOne(
                    gen = gen,
                    id = backId,
                    texture = rearTexture,
                    slot = CameraSlot.REAR,
                    displayRotation = displayRotation,
                    onStream = onStream,
                    onReady = {
                        if (pending.decrementAndGet() == 0 && !failed.get() && gen == generation.get()) {
                            onOpened()
                        }
                    },
                    onError = { message ->
                        if (failed.compareAndSet(false, true) && gen == generation.get()) {
                            closeOnThread()
                            onFailed(message)
                        }
                    },
                )
                openOne(
                    gen = gen,
                    id = frontId,
                    texture = frontTexture,
                    slot = CameraSlot.FRONT,
                    displayRotation = displayRotation,
                    onStream = onStream,
                    onReady = {
                        if (pending.decrementAndGet() == 0 && !failed.get() && gen == generation.get()) {
                            onOpened()
                        }
                    },
                    onError = { message ->
                        if (failed.compareAndSet(false, true) && gen == generation.get()) {
                            closeOnThread()
                            onFailed(message)
                        }
                    },
                )
            } catch (thrown: SameFacingCamerasException) {
                Log.e(TAG, "Camera2 pair rejected", thrown)
                closeOnThread()
                if (gen == generation.get()) onFailed(SAME_FACING_FAILURE)
            } catch (thrown: RuntimeException) {
                Log.e(TAG, "Camera2 dual open failed", thrown)
                closeOnThread()
                if (gen == generation.get()) onFailed(thrown.message ?: "camera2")
            }
        }
    }

    fun openRearOnly(
        rearTexture: SurfaceTexture,
        displayRotation: Int,
        onStream: (CameraSlot, StreamGeometry) -> Unit,
        onOpened: () -> Unit,
        onFailed: (String) -> Unit,
    ) {
        val gen = generation.incrementAndGet()
        handler.post {
            if (gen != generation.get()) return@post
            try {
                closeOnThread()
                val backId = manager.cameraIdList.firstOrNull { id ->
                    manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_BACK
                } ?: throw IllegalStateException("no rear camera")
                openOne(
                    gen = gen,
                    id = backId,
                    texture = rearTexture,
                    slot = CameraSlot.REAR,
                    displayRotation = displayRotation,
                    onStream = onStream,
                    onReady = { if (gen == generation.get()) onOpened() },
                    onError = { message -> if (gen == generation.get()) onFailed(message) },
                )
            } catch (thrown: RuntimeException) {
                Log.e(TAG, "Camera2 rear open failed", thrown)
                if (gen == generation.get()) onFailed(thrown.message ?: "camera2")
            }
        }
    }

    fun close() {
        generation.incrementAndGet()
        val latch = CountDownLatch(1)
        handler.post {
            try {
                closeOnThread()
            } finally {
                latch.countDown()
            }
        }
        latch.await(800, TimeUnit.MILLISECONDS)
    }

    fun release() {
        close()
        thread.quitSafely()
    }

    @SuppressLint("MissingPermission")
    private fun openOne(
        gen: Int,
        id: String,
        texture: SurfaceTexture,
        slot: CameraSlot,
        displayRotation: Int,
        onStream: (CameraSlot, StreamGeometry) -> Unit,
        onReady: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val characteristics = manager.getCameraCharacteristics(id)
        val size = chooseSize(characteristics)
        texture.setDefaultBufferSize(size.width, size.height)
        onStream(slot, geometryFor(characteristics, size, displayRotation))
        val surface = Surface(texture)
        surfaces += surface
        manager.openCamera(
            id,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (gen != generation.get()) {
                        camera.close()
                        return
                    }
                    devices += camera
                    createSession(gen, camera, surface, characteristics, onReady, onError)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (gen == generation.get()) onError("camera disconnected")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (gen == generation.get()) onError("camera error $error")
                }
            },
            handler,
        )
    }

    private fun createSession(
        gen: Int,
        camera: CameraDevice,
        surface: Surface,
        characteristics: CameraCharacteristics,
        onReady: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            fpsRange(characteristics)?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            if (supportsAf(characteristics)) {
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
        }
        camera.createCaptureSession(
            android.hardware.camera2.params.SessionConfiguration(
                android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                listOf(android.hardware.camera2.params.OutputConfiguration(surface)),
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (gen != generation.get()) {
                            session.close()
                            return
                        }
                        sessions += session
                        try {
                            session.setRepeatingRequest(request.build(), null, handler)
                            onReady()
                        } catch (thrown: RuntimeException) {
                            Log.e(TAG, "repeating request failed", thrown)
                            onError("session request failed")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (gen == generation.get()) onError("session configure failed")
                    }
                },
            ),
        )
    }

    private fun closeOnThread() {
        sessions.toList().forEach { runCatching { it.close() } }
        devices.toList().forEach { runCatching { it.close() } }
        surfaces.toList().forEach { runCatching { it.release() } }
        sessions.clear()
        devices.clear()
        surfaces.clear()
    }

    private fun facingOf(id: String): LensFacing {
        val facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
        return when (facing) {
            CameraCharacteristics.LENS_FACING_FRONT -> LensFacing.FRONT
            CameraCharacteristics.LENS_FACING_BACK -> LensFacing.BACK
            else -> LensFacing.OTHER
        }
    }

    private fun chooseSize(characteristics: CameraCharacteristics): Size {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        if (sizes.isEmpty()) return Size(EncodeConfig.CAMERA_TARGET_WIDTH, EncodeConfig.CAMERA_TARGET_HEIGHT)
        val capped = sizes.filter { it.width <= 1920 && it.height <= 1080 }
        val pool = if (capped.isNotEmpty()) capped else sizes
        val preferW = EncodeConfig.CAMERA_TARGET_WIDTH
        val preferH = EncodeConfig.CAMERA_TARGET_HEIGHT
        return pool.minBy { size ->
            val ratio = size.width.toFloat() / size.height.toFloat()
            val aspectPenalty = if (abs(ratio - 16f / 9f) < 0.08f) 0 else 4_000
            aspectPenalty + abs(size.width - preferW) + abs(size.height - preferH)
        }
    }

    private fun geometryFor(
        characteristics: CameraCharacteristics,
        size: Size,
        displayRotation: Int,
    ): StreamGeometry {
        val sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
        val display = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val rotation = if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensor + display) % 360
        } else {
            (sensor - display + 360) % 360
        }
        return StreamGeometry(
            bufferWidth = size.width,
            bufferHeight = size.height,
            rotationDegrees = rotation,
            mirror = facing == CameraCharacteristics.LENS_FACING_FRONT,
        )
    }

    private fun fpsRange(characteristics: CameraCharacteristics): android.util.Range<Int>? {
        val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return null
        return ranges.firstOrNull { it.lower <= 30 && it.upper >= 30 }
            ?: ranges.maxByOrNull { it.upper }
    }

    private fun supportsAf(characteristics: CameraCharacteristics): Boolean {
        val modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: return false
        return modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
    }

    private companion object {
        const val TAG = "DualCamPiP"
    }
}
