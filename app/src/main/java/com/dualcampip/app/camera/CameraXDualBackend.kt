package com.dualcampip.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.dualcampip.app.encode.EncodeConfig
import com.dualcampip.app.model.CameraSlot
import com.dualcampip.app.model.StreamGeometry

class CameraXDualBackend(
    context: Context,
) {
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private var provider: ProcessCameraProvider? = null
    private var rearPreview: Preview? = null
    private var frontPreview: Preview? = null
    @Volatile private var acceptErrors = false

    fun bindProvider(cameraProvider: ProcessCameraProvider) {
        provider = cameraProvider
    }

    fun findFrontBack(): Pair<CameraInfo, CameraInfo>? {
        val groups = provider?.availableConcurrentCameraInfos.orEmpty()
        for (group in groups.sortedBy { it.size }) {
            val back = group.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_BACK }
            val front = group.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
            if (back != null && front != null) return back to front
        }
        return null
    }

    fun openConcurrent(
        owner: LifecycleOwner,
        rearTexture: SurfaceTexture,
        frontTexture: SurfaceTexture,
        displayRotation: Int,
        onStream: (CameraSlot, StreamGeometry) -> Unit,
        onCameraError: (String) -> Unit,
    ) {
        val cameraProvider = provider ?: error("CameraX provider is not ready")
        val (back, front) = findFrontBack() ?: error("CameraX has no front+back concurrent pair")
        close()
        acceptErrors = true
        try {
            bindPair(
                cameraProvider,
                owner,
                back,
                front,
                rearTexture,
                frontTexture,
                displayRotation,
                onStream,
                onCameraError,
                relaxed = false,
            )
        } catch (first: RuntimeException) {
            Log.w(TAG, "CameraX concurrent bind retry without resolution hint", first)
            close()
            acceptErrors = true
            bindPair(
                cameraProvider,
                owner,
                back,
                front,
                rearTexture,
                frontTexture,
                displayRotation,
                onStream,
                onCameraError,
                relaxed = true,
            )
        }
    }

    fun openRearOnly(
        owner: LifecycleOwner,
        rearTexture: SurfaceTexture,
        displayRotation: Int,
        onStream: (CameraSlot, StreamGeometry) -> Unit,
    ) {
        val cameraProvider = provider ?: return
        close()
        acceptErrors = false
        val preview = buildPreview(displayRotation, relaxed = true)
        rearPreview = preview
        preview.setSurfaceProvider(mainExecutor) { request ->
            provide(request, rearTexture, CameraSlot.REAR, onStream)
        }
        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        cameraProvider.bindToLifecycle(owner, selector, preview)
    }

    fun close() {
        acceptErrors = false
        rearPreview?.setSurfaceProvider(null)
        frontPreview?.setSurfaceProvider(null)
        rearPreview = null
        frontPreview = null
        runCatching { provider?.unbindAll() }
    }

    private fun bindPair(
        cameraProvider: ProcessCameraProvider,
        owner: LifecycleOwner,
        back: CameraInfo,
        front: CameraInfo,
        rearTexture: SurfaceTexture,
        frontTexture: SurfaceTexture,
        displayRotation: Int,
        onStream: (CameraSlot, StreamGeometry) -> Unit,
        onCameraError: (String) -> Unit,
        relaxed: Boolean,
    ) {
        val rear = buildPreview(displayRotation, relaxed)
        val frontPreview = buildPreview(displayRotation, relaxed)
        this.rearPreview = rear
        this.frontPreview = frontPreview
        rear.setSurfaceProvider(mainExecutor) { request ->
            provide(request, rearTexture, CameraSlot.REAR, onStream)
        }
        frontPreview.setSurfaceProvider(mainExecutor) { request ->
            provide(request, frontTexture, CameraSlot.FRONT, onStream)
        }
        val viewPort = ViewPort.Builder(Rational(9, 16), displayRotation)
            .setScaleType(ViewPort.FILL_CENTER)
            .build()
        val rearGroup = UseCaseGroup.Builder().setViewPort(viewPort).addUseCase(rear).build()
        val frontGroup = UseCaseGroup.Builder().setViewPort(viewPort).addUseCase(frontPreview).build()
        val concurrent = cameraProvider.bindToLifecycle(
            listOf(
                ConcurrentCamera.SingleCameraConfig(selectorFor(back), rearGroup, owner),
                ConcurrentCamera.SingleCameraConfig(selectorFor(front), frontGroup, owner),
            ),
        )
        observeErrors(concurrent.cameras, owner, onCameraError)
    }

    private fun observeErrors(
        cameras: List<Camera>,
        owner: LifecycleOwner,
        onCameraError: (String) -> Unit,
    ) {
        val observer = Observer<androidx.camera.core.CameraState> { state ->
            if (!acceptErrors) return@Observer
            val error = state.error ?: return@Observer
            acceptErrors = false
            onCameraError("CameraX ${error.code}")
        }
        cameras.forEach { camera ->
            camera.cameraInfo.cameraState.observe(owner, observer)
        }
    }

    private fun provide(
        request: androidx.camera.core.SurfaceRequest,
        texture: SurfaceTexture,
        slot: CameraSlot,
        onStream: (CameraSlot, StreamGeometry) -> Unit,
    ) {
        val size = request.resolution
        texture.setDefaultBufferSize(size.width, size.height)
        request.setTransformationInfoListener(mainExecutor) { info ->
            onStream(
                slot,
                StreamGeometry(
                    bufferWidth = size.width,
                    bufferHeight = size.height,
                    rotationDegrees = info.rotationDegrees,
                    mirror = info.isMirroring,
                    cropLeft = info.cropRect.left,
                    cropTop = info.cropRect.top,
                    cropRight = info.cropRect.right,
                    cropBottom = info.cropRect.bottom,
                ),
            )
        }
        val surface = Surface(texture)
        request.provideSurface(surface, mainExecutor) {
            surface.release()
        }
    }

    private fun buildPreview(displayRotation: Int, relaxed: Boolean): Preview {
        val builder = Preview.Builder().setTargetRotation(displayRotation)
        if (!relaxed) {
            builder.setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(EncodeConfig.CAMERA_TARGET_WIDTH, EncodeConfig.CAMERA_TARGET_HEIGHT),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        ),
                    )
                    .build(),
            )
        }
        return builder.build()
    }

    private fun selectorFor(info: CameraInfo): CameraSelector {
        val identifier = info.cameraIdentifier
        if (identifier != null) return CameraSelector.of(identifier)
        val cameraId = Camera2CameraInfo.from(info).cameraId
        return CameraSelector.Builder()
            .addCameraFilter { cameras ->
                cameras.filter { Camera2CameraInfo.from(it).cameraId == cameraId }
            }
            .build()
    }

    @SuppressLint("RestrictedApi")
    private companion object {
        const val TAG = "DualCamPiP"
    }
}
