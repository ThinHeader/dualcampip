package com.dualcampip.app.encode

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import com.dualcampip.app.gallery.GallerySaver
import com.dualcampip.app.gallery.recordingDisplayName
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Encodes the compositor's surface as H.264 and the microphone as AAC, then muxes
 * them into one MP4 that [GallerySaver] publishes to the local gallery.
 */
class CompositeRecorder(context: Context) {
    private val gallery = GallerySaver(context.applicationContext.contentResolver)
    private var uri: Uri? = null
    private var pfd: ParcelFileDescriptor? = null
    private var muxer: SampleMuxer? = null
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var encoderSurface: Surface? = null
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private val audioRunning = AtomicBoolean(false)
    private var displayName: String = ""
    private var recordStartNs: Long = 0L
    @Volatile private var failure: String? = null
    private var released = false

    fun start(): Surface {
        check(!released)
        displayName = recordingDisplayName()
        val pending = gallery.createPending(displayName)
        uri = pending
        val descriptor = gallery.open(pending)
        pfd = descriptor
        val sampleMuxer = SampleMuxer(descriptor.fileDescriptor)
        muxer = sampleMuxer

        val video = createVideoEncoder()
        videoCodec = video.codec
        encoderSurface = video.surface
        val audio = createAudioEncoder()
        audioCodec = audio
        val mic = createMicrophone()
        audioRecord = mic

        recordStartNs = System.nanoTime()
        video.codec.start()
        audio.start()
        mic.startRecording()
        audioRunning.set(true)
        videoThread = Thread({ drainVideo(video.codec, sampleMuxer) }, "DualCamVideoDrain").also { it.start() }
        audioThread = Thread({ pumpAudio(mic, audio, sampleMuxer) }, "DualCamAudio").also {
            it.start()
        }
        return video.surface
    }

    fun signalVideoEnd() {
        runCatching { videoCodec?.signalEndOfInputStream() }
            .onFailure { Log.e(TAG, "signalEndOfInputStream failed", it) }
    }

    fun finish(): String {
        audioRunning.set(false)
        audioThread?.join(2_500)
        videoThread?.join(4_000)
        val sampleMuxer = muxer
        val wroteVideo = sampleMuxer != null && sampleMuxer.isStarted && sampleMuxer.videoSamplesWritten > 0
        runCatching { sampleMuxer?.finish() }
        releaseCodecs()
        val target = uri
        uri = null
        if (wroteVideo && target != null && failure == null) {
            gallery.publish(target)
            return displayName
        }
        if (target != null) gallery.abandon(target)
        throw IllegalStateException(failure ?: "no video samples")
    }

    fun abort() {
        audioRunning.set(false)
        runCatching { audioThread?.join(400) }
        runCatching { videoThread?.join(400) }
        runCatching { muxer?.finish() }
        releaseCodecs()
        uri?.let { gallery.abandon(it) }
        uri = null
    }

    private fun drainVideo(codec: MediaCodec, sampleMuxer: SampleMuxer) {
        val info = MediaCodec.BufferInfo()
        var eos = false
        while (!eos) {
            try {
                val index = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        sampleMuxer.onFormat(TrackKind.VIDEO, codec.outputFormat)
                    }
                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null) sampleMuxer.write(TrackKind.VIDEO, buffer, info)
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                    }
                }
            } catch (thrown: IllegalStateException) {
                Log.e(TAG, "video drain stopped", thrown)
                failure = failure ?: "video encoder stopped"
                break
            }
        }
    }

    private fun pumpAudio(mic: AudioRecord, codec: MediaCodec, sampleMuxer: SampleMuxer) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buffer = ByteArray(4096)
        var samples = 0L
        val audioStartNs = System.nanoTime()
        while (audioRunning.get()) {
            val read = mic.read(buffer, 0, buffer.size)
            if (read > 0) {
                queueAudio(codec, buffer, read, ptsUs(audioStartNs, samples))
                samples += read / 2
            } else if (read < 0) {
                failure = failure ?: "microphone read failed"
                break
            }
            drainAudio(codec, sampleMuxer, waitEos = false)
        }
        queueAudioEos(codec, ptsUs(audioStartNs, samples))
        drainAudio(codec, sampleMuxer, waitEos = true)
        runCatching { mic.stop() }
    }

    private fun ptsUs(audioStartNs: Long, samplesWritten: Long): Long {
        val offsetUs = (audioStartNs - recordStartNs) / 1_000L
        val sampleUs = samplesWritten * 1_000_000L / EncodeConfig.AUDIO_SAMPLE_RATE
        return (offsetUs + sampleUs).coerceAtLeast(0L)
    }

    private fun queueAudio(codec: MediaCodec, data: ByteArray, size: Int, ptsUs: Long) {
        val index = codec.dequeueInputBuffer(10_000)
        if (index < 0) return
        val input = codec.getInputBuffer(index) ?: return
        input.clear()
        input.put(data, 0, size)
        codec.queueInputBuffer(index, 0, size, ptsUs, 0)
    }

    private fun queueAudioEos(codec: MediaCodec, ptsUs: Long) {
        val index = codec.dequeueInputBuffer(20_000)
        if (index >= 0) {
            codec.queueInputBuffer(index, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
    }

    private fun drainAudio(codec: MediaCodec, sampleMuxer: SampleMuxer, waitEos: Boolean) {
        val info = MediaCodec.BufferInfo()
        val deadline = System.nanoTime() + if (waitEos) 2_000_000_000L else 0L
        while (true) {
            val timeout = if (waitEos) 10_000L else 0L
            val index = codec.dequeueOutputBuffer(info, timeout)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!waitEos || System.nanoTime() > deadline) return
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    sampleMuxer.onFormat(TrackKind.AUDIO, codec.outputFormat)
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null) sampleMuxer.write(TrackKind.AUDIO, buffer, info)
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                    if (eos || !waitEos) return
                }
                else -> if (!waitEos) return
            }
        }
    }

    private fun createVideoEncoder(): VideoEncoder {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            EncodeConfig.WIDTH,
            EncodeConfig.HEIGHT,
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, EncodeConfig.VIDEO_BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, EncodeConfig.FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, EncodeConfig.I_FRAME_INTERVAL_SEC)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        return VideoEncoder(codec, surface)
    }

    private fun createAudioEncoder(): MediaCodec {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            EncodeConfig.AUDIO_SAMPLE_RATE,
            EncodeConfig.AUDIO_CHANNEL_COUNT,
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, EncodeConfig.AUDIO_BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return codec
    }

    @SuppressLint("MissingPermission")
    private fun createMicrophone(): AudioRecord {
        val min = AudioRecord.getMinBufferSize(
            EncodeConfig.AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (min <= 0) throw IllegalStateException("microphone buffer unavailable")
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            EncodeConfig.AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            min * 2,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("microphone failed to initialize")
        }
        return record
    }

    private fun releaseCodecs() {
        if (released) return
        released = true
        runCatching { audioRecord?.release() }
        runCatching { audioCodec?.stop() }
        runCatching { audioCodec?.release() }
        runCatching { videoCodec?.stop() }
        runCatching { videoCodec?.release() }
        runCatching { encoderSurface?.release() }
        runCatching { pfd?.close() }
        audioRecord = null
        audioCodec = null
        videoCodec = null
        encoderSurface = null
        pfd = null
        muxer = null
    }

    private data class VideoEncoder(val codec: MediaCodec, val surface: Surface)

    private companion object {
        const val TAG = "DualCamPiP"
    }
}
