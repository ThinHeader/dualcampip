package com.dualcampip.app.render

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.util.Log
import android.view.Surface

internal class EglCore {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null

    fun init() {
        if (display !== EGL14.EGL_NO_DISPLAY) return
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display === EGL14.EGL_NO_DISPLAY) error("eglGetDisplay failed")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) error("eglInitialize failed")
        config = chooseConfig()
        val attrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, attrs, 0)
        if (context === EGL14.EGL_NO_CONTEXT) error("eglCreateContext failed ${EGL14.eglGetError()}")
    }

    fun createWindowSurface(surface: Surface): EGLSurface {
        val attrs = intArrayOf(EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, attrs, 0)
        if (eglSurface == null || eglSurface === EGL14.EGL_NO_SURFACE) {
            error("eglCreateWindowSurface failed ${EGL14.eglGetError()}")
        }
        return eglSurface
    }

    fun createPbuffer(width: Int, height: Int): EGLSurface {
        val attrs = intArrayOf(
            EGL14.EGL_WIDTH, width,
            EGL14.EGL_HEIGHT, height,
            EGL14.EGL_NONE,
        )
        val eglSurface = EGL14.eglCreatePbufferSurface(display, config, attrs, 0)
        if (eglSurface == null || eglSurface === EGL14.EGL_NO_SURFACE) {
            error("eglCreatePbufferSurface failed ${EGL14.eglGetError()}")
        }
        return eglSurface
    }

    fun makeCurrent(surface: EGLSurface) {
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            error("eglMakeCurrent failed ${EGL14.eglGetError()}")
        }
    }

    fun swap(surface: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, surface)

    fun setPresentationTime(surface: EGLSurface, nanoseconds: Long) {
        EGLExt.eglPresentationTimeANDROID(display, surface, nanoseconds)
    }

    fun makeNothingCurrent() {
        if (display === EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
    }

    fun destroySurface(surface: EGLSurface?) {
        if (surface == null || surface === EGL14.EGL_NO_SURFACE) return
        EGL14.eglDestroySurface(display, surface)
    }

    fun release() {
        if (display === EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (context !== EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        config = null
    }

    private fun chooseConfig(): EGLConfig {
        val recordable = choose(withRecordable = true)
        if (recordable != null) return recordable
        return choose(withRecordable = false) ?: error("no EGL config")
    }

    private fun choose(withRecordable: Boolean): EGLConfig? {
        val attribs = if (withRecordable) {
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE,
            )
        } else {
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE,
            )
        }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val ok = EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0)
        if (!ok || count[0] <= 0) {
            Log.w(TAG, "eglChooseConfig recordable=$withRecordable failed ${EGL14.eglGetError()}")
            return null
        }
        return configs[0]
    }

    private companion object {
        const val TAG = "DualCamPiP"
        const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
