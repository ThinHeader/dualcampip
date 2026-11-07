package com.dualcampip.app.encode

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.nio.ByteBuffer

internal enum class TrackKind {
    VIDEO,
    AUDIO,
}

/**
 * Accepts encoded samples from the video and audio drain threads and writes one MP4.
 * Samples that arrive before both tracks have a format are copied and flushed later.
 */
internal class SampleMuxer(
    fd: java.io.FileDescriptor,
) {
    private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val lock = Any()
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private var released = false
    private val pending = ArrayList<PendingSample>(64)
    private val videoTimestamps = TrackTimestamps()
    private val audioClock = AudioFrameClock(
        sampleRate = EncodeConfig.AUDIO_SAMPLE_RATE,
        samplesPerFrame = EncodeConfig.AAC_FRAME_SAMPLES,
    )
    private var loggedVideoBase = false
    private var loggedAudioBase = false

    var videoSamplesWritten: Int = 0
        private set

    val isStarted: Boolean get() = synchronized(lock) { started }

    init {
        muxer.setOrientationHint(0)
    }

    fun onFormat(kind: TrackKind, format: MediaFormat) = synchronized(lock) {
        if (released) return
        if (kind == TrackKind.VIDEO) videoFormat = format else audioFormat = format
        val video = videoFormat ?: return
        val audio = audioFormat ?: return
        if (started) return
        videoTrack = muxer.addTrack(video)
        audioTrack = muxer.addTrack(audio)
        muxer.start()
        started = true
        pending.forEach { writeLocked(it) }
        pending.clear()
    }

    fun write(kind: TrackKind, data: ByteBuffer, info: MediaCodec.BufferInfo) = synchronized(lock) {
        if (released || info.size <= 0) return
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
        val bytes = ByteArray(info.size)
        val duplicate = data.duplicate()
        duplicate.position(info.offset)
        duplicate.limit(info.offset + info.size)
        duplicate.get(bytes)
        val sample = PendingSample(
            kind = kind,
            bytes = bytes,
            ptsUs = info.presentationTimeUs,
            flags = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv(),
        )
        if (!started) {
            if (pending.size < MAX_PENDING) pending += sample
            return
        }
        writeLocked(sample)
    }

    fun finish() = synchronized(lock) {
        if (released) return
        released = true
        try {
            if (started && videoSamplesWritten > 0) muxer.stop()
        } finally {
            runCatching { muxer.release() }
        }
    }

    private fun writeLocked(sample: PendingSample) {
        val track = if (sample.kind == TrackKind.VIDEO) videoTrack else audioTrack
        if (track < 0) return
        val ptsUs = when (sample.kind) {
            TrackKind.VIDEO -> {
                if (!loggedVideoBase) {
                    loggedVideoBase = true
                    Log.i(TAG, "video pts base ${sample.ptsUs} us")
                }
                videoTimestamps.rebase(sample.ptsUs)
            }
            TrackKind.AUDIO -> {
                val pts = audioClock.nextPtsUs()
                if (!loggedAudioBase) {
                    loggedAudioBase = true
                    Log.i(TAG, "audio frame pts ${pts} us, encoder had ${sample.ptsUs} us")
                }
                pts
            }
        }
        val buffer = ByteBuffer.wrap(sample.bytes)
        val info = MediaCodec.BufferInfo()
        info.set(0, sample.bytes.size, ptsUs, sample.flags)
        muxer.writeSampleData(track, buffer, info)
        if (sample.kind == TrackKind.VIDEO) videoSamplesWritten += 1
    }

    private data class PendingSample(
        val kind: TrackKind,
        val bytes: ByteArray,
        val ptsUs: Long,
        val flags: Int,
    )

    private companion object {
        const val TAG = "DualCamPiP"
        const val MAX_PENDING = 180
    }
}
