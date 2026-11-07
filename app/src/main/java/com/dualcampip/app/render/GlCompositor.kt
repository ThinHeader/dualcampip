package com.dualcampip.app.render

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.dualcampip.app.encode.EncodeConfig
import com.dualcampip.app.model.CameraSlot
import com.dualcampip.app.model.PipConfig
import com.dualcampip.app.model.StreamGeometry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Draws the rear camera full-frame and the front camera as a rounded PiP into the
 * preview surface and, while recording, into the encoder surface with the same layout.
 */
class GlCompositor {
    private val thread = HandlerThread("DualCamGL").apply { start() }
    private val handler = Handler(thread.looper)
    private val egl = EglCore()

    private var pbuffer: android.opengl.EGLSurface? = null
    private var previewEgl: android.opengl.EGLSurface? = null
    private var encoderEgl: android.opengl.EGLSurface? = null
    private var previewWidth = 0
    private var previewHeight = 0
    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var aLocal = 0
    private var uMask = 0
    private var uSize = 0
    private var uRadius = 0
    private var ready = false

    private val rear = CameraStream()
    private val front = CameraStream()
    private val renderQueued = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(24 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    @Volatile var pip: PipConfig = PipConfig.Default
    @Volatile var recordClockBaseNs: Long = 0L
    @Volatile var recording: Boolean = false
    @Volatile var onBothFrames: (() -> Unit)? = null

    private val frameGate = AtomicInteger(0)
    private var notifiedGate = -1
    private var loggedFrame = false
    private var lastEncoderPtsNs = 0L
    private val mainHandler = Handler(android.os.Looper.getMainLooper())

    fun attachPreview(surface: Surface, width: Int, height: Int) = call {
        ensureContext()
        previewWidth = width
        previewHeight = height
        previewEgl?.let { old ->
            egl.makeCurrent(pbuffer!!)
            egl.destroySurface(old)
        }
        previewEgl = egl.createWindowSurface(surface)
        egl.makeCurrent(previewEgl!!)
    }

    fun detachPreview() = runCatching {
        call {
            if (!ready) return@call
            pbuffer?.let { egl.makeCurrent(it) }
            previewEgl?.let { egl.destroySurface(it) }
            previewEgl = null
        }
    }

    fun beginRecording(surface: Surface, clockBaseNs: Long) = call {
        ensureContext()
        val anchor = previewEgl ?: pbuffer
        if (anchor != null) egl.makeCurrent(anchor)
        encoderEgl?.let { egl.destroySurface(it) }
        encoderEgl = egl.createWindowSurface(surface)
        recordClockBaseNs = clockBaseNs
        lastEncoderPtsNs = 0L
        recording = true
    }

    fun endRecordingInput(signalEos: () -> Unit) = call {
        recording = false
        signalEos()
    }

    fun detachEncoder() = runCatching {
        call {
            if (!ready) return@call
            val current = previewEgl ?: pbuffer
            if (current != null) egl.makeCurrent(current)
            encoderEgl?.let { egl.destroySurface(it) }
            encoderEgl = null
        }
    }

    fun updatePipLayout(config: PipConfig) {
        pip = config.sanitized()
        schedule()
    }

    fun updateGeometry(slot: CameraSlot, geometry: StreamGeometry) {
        post {
            val stream = stream(slot)
            stream.geometry = geometry
            if (!loggedFrame && slot == CameraSlot.REAR) {
                loggedFrame = true
                Log.i(
                    TAG,
                    "rear buffer ${geometry.bufferWidth}x${geometry.bufferHeight} " +
                        "rot=${geometry.rotationDegrees} mirror=${geometry.mirror}",
                )
            }
        }
    }

    fun resetFrameGate() {
        frameGate.incrementAndGet()
        post {
            rear.hasFrame = false
            front.hasFrame = false
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        runCatching {
            call {
                if (ready) {
                    rear.release()
                    front.release()
                    if (program != 0) GLES20.glDeleteProgram(program)
                    program = 0
                    egl.makeNothingCurrent()
                    previewEgl?.let { egl.destroySurface(it) }
                    encoderEgl?.let { egl.destroySurface(it) }
                    pbuffer?.let { egl.destroySurface(it) }
                    previewEgl = null
                    encoderEgl = null
                    pbuffer = null
                    egl.release()
                    ready = false
                }
            }
        }.onFailure { Log.e(TAG, "gl release failed", it) }
        thread.quitSafely()
    }

    private fun ensureContext() {
        if (ready) return
        egl.init()
        pbuffer = egl.createPbuffer(1, 1)
        egl.makeCurrent(pbuffer!!)
        program = linkProgram(GlShaders.VERTEX, GlShaders.FRAGMENT)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        aLocal = GLES20.glGetAttribLocation(program, "aLocal")
        uMask = GLES20.glGetUniformLocation(program, "uMask")
        uSize = GLES20.glGetUniformLocation(program, "uSize")
        uRadius = GLES20.glGetUniformLocation(program, "uRadius")
        rear.init(handler) { schedule() }
        front.init(handler) { schedule() }
        ready = true
    }

    fun rearTexture(): SurfaceTexture = call { ensureContext(); rear.texture }
    fun frontTexture(): SurfaceTexture = call { ensureContext(); front.texture }

    private fun schedule() {
        if (renderQueued.compareAndSet(false, true)) {
            handler.post {
                renderQueued.set(false)
                runCatching { render() }.onFailure { Log.e(TAG, "render failed", it) }
            }
        }
    }

    private fun render() {
        if (!ready) return
        val anchor = previewEgl ?: pbuffer ?: return
        egl.makeCurrent(anchor)
        rear.consume()
        front.consume()
        val gate = frameGate.get()
        if (rear.hasFrame && front.hasFrame && notifiedGate != gate) {
            notifiedGate = gate
            val callback = onBothFrames
            if (callback != null) {
                mainHandler.post {
                    if (frameGate.get() == gate) callback()
                }
            }
        }
        val preview = previewEgl
        if (preview != null && previewWidth > 0 && previewHeight > 0) {
            egl.makeCurrent(preview)
            drawScene(previewWidth, previewHeight)
            egl.swap(preview)
        }
        val encoder = encoderEgl
        if (recording && encoder != null && rear.hasFrame) {
            val pts = nextPts(rear.texture.timestamp)
            if (pts - lastEncoderPtsNs >= FRAME_INTERVAL_NS || lastEncoderPtsNs == 0L) {
                egl.makeCurrent(encoder)
                drawScene(EncodeConfig.WIDTH, EncodeConfig.HEIGHT)
                egl.setPresentationTime(encoder, pts)
                egl.swap(encoder)
                lastEncoderPtsNs = pts
            }
        }
    }

    private fun nextPts(textureTimestampNs: Long): Long {
        val raw = if (textureTimestampNs > 0L) {
            textureTimestampNs - recordClockBaseNs
        } else {
            System.nanoTime() - recordClockBaseNs
        }
        val pts = raw.coerceAtLeast(lastEncoderPtsNs + 1_000_000L)
        return pts
    }

    private fun drawScene(width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        val frame = PixelRect(0f, 0f, width.toFloat(), height.toFloat())
        if (rear.hasFrame) {
            GLES20.glDisable(GLES20.GL_BLEND)
            drawStream(rear, frame, width, height, mask = false, radiusPx = 0f)
        }
        if (front.hasFrame) {
            val aspect = uprightAspect(front.geometry)
            val pipRect = pipRect(
                frameWidth = width.toFloat(),
                frameHeight = height.toFloat(),
                contentAspect = aspect,
                corner = pip.corner,
                widthFraction = pip.widthFraction,
            )
            val radius = pipCornerRadiusPx(pipRect, pip.radiusFraction)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            drawStream(front, pipRect, width, height, mask = radius > 0.5f, radiusPx = radius)
            GLES20.glDisable(GLES20.GL_BLEND)
        }
    }

    private fun drawStream(
        stream: CameraStream,
        rect: PixelRect,
        viewWidth: Int,
        viewHeight: Int,
        mask: Boolean,
        radiusPx: Float,
    ) {
        if (abs(rect.width) < 1f || abs(rect.height) < 1f) return
        val vertices = buildQuadVertices(rect, viewWidth, viewHeight, stream.geometry, stream.stMatrix)
        vertexBuffer.clear()
        vertexBuffer.put(vertices)
        val stride = 6 * 4
        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, stride, vertexBuffer)
        vertexBuffer.position(2)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, stride, vertexBuffer)
        vertexBuffer.position(4)
        GLES20.glEnableVertexAttribArray(aLocal)
        GLES20.glVertexAttribPointer(aLocal, 2, GLES20.GL_FLOAT, false, stride, vertexBuffer)
        GLES20.glUniform1f(uMask, if (mask) 1f else 0f)
        GLES20.glUniform2f(uSize, rect.width, rect.height)
        GLES20.glUniform1f(uRadius, radiusPx)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, stream.texId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun stream(slot: CameraSlot): CameraStream = when (slot) {
        CameraSlot.REAR -> rear
        CameraSlot.FRONT -> front
    }

    private fun post(block: () -> Unit) {
        if (!thread.isAlive) return
        handler.post(block)
    }

    private fun <T> call(timeoutMs: Long = 3_000, block: () -> T): T {
        val future = CompletableFuture<T>()
        handler.post {
            try {
                future.complete(block())
            } catch (thrown: Throwable) {
                future.completeExceptionally(thrown)
            }
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (thrown: java.util.concurrent.ExecutionException) {
            throw thrown.cause ?: thrown
        }
    }

    private class CameraStream {
        lateinit var texture: SurfaceTexture
        var texId: Int = 0
        val stMatrix = identityStMatrix()
        var geometry: StreamGeometry = StreamGeometry(1280, 720, 90, mirror = false)
        var hasFrame: Boolean = false
        private val pending = AtomicBoolean(false)

        fun init(handler: Handler, onFrame: () -> Unit) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            texId = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            texture = SurfaceTexture(texId)
            texture.setOnFrameAvailableListener({
                pending.set(true)
                onFrame()
            }, handler)
        }

        fun consume() {
            if (!pending.compareAndSet(true, false)) return
            texture.updateTexImage()
            texture.getTransformMatrix(stMatrix)
            hasFrame = true
        }

        fun release() {
            if (this::texture.isInitialized) texture.release()
            if (texId != 0) GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
            texId = 0
        }
    }

    private companion object {
        const val TAG = "DualCamPiP"
        const val FRAME_INTERVAL_NS = 1_000_000_000L / EncodeConfig.FRAME_RATE
    }
}

private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
    val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
    val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
    val program = GLES20.glCreateProgram()
    GLES20.glAttachShader(program, vertex)
    GLES20.glAttachShader(program, fragment)
    GLES20.glLinkProgram(program)
    val status = IntArray(1)
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
    if (status[0] == 0) {
        val log = GLES20.glGetProgramInfoLog(program)
        GLES20.glDeleteProgram(program)
        error("program link failed: $log")
    }
    GLES20.glDeleteShader(vertex)
    GLES20.glDeleteShader(fragment)
    return program
}

private fun compileShader(type: Int, source: String): Int {
    val shader = GLES20.glCreateShader(type)
    GLES20.glShaderSource(shader, source)
    GLES20.glCompileShader(shader)
    val status = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
    if (status[0] == 0) {
        val log = GLES20.glGetShaderInfoLog(shader)
        GLES20.glDeleteShader(shader)
        error("shader compile failed: $log")
    }
    return shader
}
