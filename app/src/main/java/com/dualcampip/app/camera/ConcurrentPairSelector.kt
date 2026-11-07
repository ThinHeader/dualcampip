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
