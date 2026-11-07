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
        if (rearTexture === frontTexture) {
            throw SameFacingCamerasException("rear and front preview textures are the same object")
        }
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
        } catch (mismatch: SameFacingCamerasException) {
            close()
            throw mismatch
        } catch (first: RuntimeException) {
            Log.w(TAG, "CameraX concurrent bind retry without resolution hint", first)
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
                    relaxed = true,
                )
            } catch (mismatch: SameFacingCamerasException) {
                close()
                throw mismatch
            }
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
        val rearSelector = selectorFor(back)
        val frontSelector = selectorFor(front)
        Log.i(
            TAG,
            "CameraX selector lensFacing main=${rearSelector.lensFacing} pip=${frontSelector.lensFacing}",
        )
        if (
            !selectorFacingsAreRearMainFrontPip(
                mainLensFacing = rearSelector.lensFacing,
                pipLensFacing = frontSelector.lensFacing,
                back = CameraSelector.LENS_FACING_BACK,
                front = CameraSelector.LENS_FACING_FRONT,
            )
        ) {
            throw SameFacingCamerasException(
                "CameraX selectors are not rear main + front pip " +
                    "(main=${rearSelector.lensFacing}, pip=${frontSelector.lensFacing})",
            )
        }
        // Separate ViewPort instances: if lens facings ever compare equal, CameraX refuses
        // the bind instead of attaching both previews to the first camera.
        val rearGroup = UseCaseGroup.Builder()
            .setViewPort(portraitViewPort(displayRotation))
            .addUseCase(rear)
            .build()
        val frontGroup = UseCaseGroup.Builder()
            .setViewPort(portraitViewPort(displayRotation))
            .addUseCase(frontPreview)
            .build()
        val concurrent = cameraProvider.bindToLifecycle(
            listOf(
                ConcurrentCamera.SingleCameraConfig(rearSelector, rearGroup, owner),
                ConcurrentCamera.SingleCameraConfig(frontSelector, frontGroup, owner),
            ),
        )
        requireOpenedRearMainFrontPip(concurrent.cameras)
        observeErrors(concurrent.cameras, owner, onCameraError)
    }

    private fun portraitViewPort(displayRotation: Int): ViewPort {
        return ViewPort.Builder(Rational(9, 16), displayRotation)
            .setScaleType(ViewPort.FILL_CENTER)
            .build()
    }

    private fun requireOpenedRearMainFrontPip(cameras: List<Camera>) {
        val main = cameras.getOrNull(0)?.cameraInfo
        val pip = cameras.getOrNull(1)?.cameraInfo
        val mainFacing = main?.let(::lensFacingOf) ?: LensFacing.OTHER
        val pipFacing = pip?.let(::lensFacingOf) ?: LensFacing.OTHER
        val mainId = main?.let(::stableCameraId).orEmpty()
        val pipId = pip?.let(::stableCameraId).orEmpty()
        Log.i(
            TAG,
            "CameraX opened count=${cameras.size} main=$mainId/$mainFacing pip=$pipId/$pipFacing",
        )
        if (!openedCamerasAreRearMainFrontPip(mainFacing, pipFacing, mainId, pipId)) {
            throw SameFacingCamerasException(
                "CameraX opened main=$mainId/$mainFacing pip=$pipId/$pipFacing",
            )
        }
    }

    private fun lensFacingOf(info: CameraInfo): LensFacing = when (info.lensFacing) {
        CameraSelector.LENS_FACING_FRONT -> LensFacing.FRONT
        CameraSelector.LENS_FACING_BACK -> LensFacing.BACK
        else -> LensFacing.OTHER
    }

    private fun stableCameraId(info: CameraInfo): String? {
        val camera2Id = runCatching { Camera2CameraInfo.from(info).cameraId }.getOrNull()
        if (!camera2Id.isNullOrBlank()) return camera2Id
        return info.cameraIdentifier?.toString()?.takeIf { it.isNotBlank() }
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

    /**
     * `CameraSelector.of(cameraIdentifier)` does not set lens facing. CameraX then treats
     * the two configs as one logical camera (`null == null`) and feeds both surfaces from
     * the first selector, which is the rear camera. [CameraSelector.Builder.requireLensFacing]
     * is what selects the concurrent front+back path. The id filter keeps the specific
     * camera from the concurrent group, not "any" back or front camera.
     */
    private fun selectorFor(info: CameraInfo): CameraSelector {
        val facing = info.lensFacing
        check(facing == CameraSelector.LENS_FACING_BACK || facing == CameraSelector.LENS_FACING_FRONT) {
            "Refusing to bind camera with lens facing $facing"
        }
        val identifier = info.cameraIdentifier
        val cameraId = runCatching { Camera2CameraInfo.from(info).cameraId }.getOrNull()
        check(identifier != null || !cameraId.isNullOrBlank()) { "Concurrent camera has no id" }
        return CameraSelector.Builder()
            .requireLensFacing(facing)
            .addCameraFilter { cameras ->
                val byId = if (cameraId.isNullOrBlank()) {
                    emptyList()
                } else {
                    cameras.filter { candidate ->
                        runCatching { Camera2CameraInfo.from(candidate).cameraId }.getOrNull() == cameraId
                    }
                }
                when {
                    byId.isNotEmpty() -> byId
                    identifier != null -> cameras.filter { it.cameraIdentifier == identifier }
                    else -> emptyList()
                }
            }
            .build()
    }

    @SuppressLint("RestrictedApi")
    private companion object {
        const val TAG = "DualCamPiP"
    }
}
