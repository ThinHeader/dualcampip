package com.dualcampip.app.encode

/**
 * Portrait 720x1280 matches a phone held upright and stays inside the 720p ceiling
 * CameraX documents for concurrent camera streaming.
 */
object EncodeConfig {
    const val WIDTH = 720
    const val HEIGHT = 1280
    const val FRAME_RATE = 30
    const val VIDEO_BIT_RATE = 4_000_000
    const val I_FRAME_INTERVAL_SEC = 1
    const val AUDIO_SAMPLE_RATE = 44_100
    const val AUDIO_BIT_RATE = 96_000
    const val AUDIO_CHANNEL_COUNT = 1
    const val AUDIO_BYTES_PER_SAMPLE = 2
    /** AAC-LC emits one access unit of this many PCM samples per output buffer. */
    const val AAC_FRAME_SAMPLES = 1024

    const val ASPECT_W_OVER_H = WIDTH.toFloat() / HEIGHT.toFloat()

    const val CAMERA_TARGET_WIDTH = 1280
    const val CAMERA_TARGET_HEIGHT = 720
}
