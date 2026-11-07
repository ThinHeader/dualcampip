package com.dualcampip.app.camera

enum class LensFacing {
    FRONT,
    BACK,
    OTHER,
}

data class CameraIdInfo(
    val id: String,
    val facing: LensFacing,
)

/**
 * Picks one front camera and one back camera from the sets reported by
 * [android.hardware.camera2.CameraManager.getConcurrentCameraIds].
 * Smaller sets win, so a dedicated front+back pair is preferred over a larger group.
 */
fun selectConcurrentPair(
    concurrentSets: Set<Set<String>>,
    cameras: List<CameraIdInfo>,
): Pair<CameraIdInfo, CameraIdInfo>? {
    val byId = cameras.associateBy { it.id }
    return concurrentSets
        .sortedBy { it.size }
        .firstNotNullOfOrNull { set ->
            val known = set.mapNotNull { byId[it] }
            val back = known.firstOrNull { it.facing == LensFacing.BACK } ?: return@firstNotNullOfOrNull null
            val front = known.firstOrNull { it.facing == LensFacing.FRONT } ?: return@firstNotNullOfOrNull null
            back to front
        }
}

/**
 * CameraX 1.6 `bindToLifecycle(List<SingleCameraConfig>)` compares
 * [androidx.camera.core.CameraSelector.getLensFacing]. Equal values — including both
 * unset (`null`) — take the single-camera path and attach every use case to the first
 * selector. Fullscreen must declare the back lens facing and PiP the front one.
 *
 * [back] and [front] are the CameraSelector constants for those facings.
 */
fun selectorFacingsAreRearMainFrontPip(
    mainLensFacing: Int?,
    pipLensFacing: Int?,
    back: Int,
    front: Int,
): Boolean = back != front && mainLensFacing == back && pipLensFacing == front

/**
 * The cameras that actually opened, not the pair the probe hoped for.
 * Fullscreen is the rear id, PiP is a different front id.
 */
fun openedCamerasAreRearMainFrontPip(
    main: LensFacing,
    pip: LensFacing,
    mainCameraId: String,
    pipCameraId: String,
): Boolean = main == LensFacing.BACK &&
    pip == LensFacing.FRONT &&
    mainCameraId.isNotBlank() &&
    pipCameraId.isNotBlank() &&
    mainCameraId != pipCameraId

/** Passed to session failure callbacks when the opened cameras are not front plus back. */
const val SAME_FACING_FAILURE = "dualcampip-same-facing"

class SameFacingCamerasException(message: String) : IllegalStateException(message)
