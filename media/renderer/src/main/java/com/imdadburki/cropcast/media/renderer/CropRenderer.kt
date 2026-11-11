package com.imdadburki.cropcast.media.renderer

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import com.imdadburki.cropcast.core.common.NormalizedRect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * EGL crop renderer (Phase 3, spec §3).
 *
 * The VirtualDisplay renders the full screen into [inputSurface] (backed by a
 * [SurfaceTexture]). This class draws each frame through a cropped texture
 * quad onto the encoder's input surface, so the saved file contains ONLY the
 * selected region — no full-screen intermediate is ever written to disk.
 *
 * Pause behaviour: while paused, incoming frames are drained (updateTexImage)
 * but never presented, and the presentation-time clock is frozen — the muxed
 * timeline has no gap, so a paused session plays back seamlessly.
 *
 * Runs on its own thread; all EGL calls happen there.
 *
 * TEXTURE-ID RULE (bug found by the VibeView device test, 2026-11-10): the
 * SurfaceTexture constructor takes the GL texture object name its images are
 * bound to. It MUST be the id returned by glGenTextures — passing the
 * GL_TEXTURE_EXTERNAL_OES enum (0x8D65) binds the images to a texture that
 * is never sampled, so every recorded frame came out black. The texture is
 * therefore generated inside [initGl] and the SurfaceTexture is created
 * there, on the render thread; [start] waits for that init before returning
 * so [inputSurface] is valid when the service reads it.
 */
class CropRenderer(
    private val outputWidth: Int,
    private val outputHeight: Int,
    crop: NormalizedRect,
    /** Native display size — the texture holds the full frame at this size. */
    private val frameWidth: Int,
    private val frameHeight: Int,
) {
    @Volatile
    var crop: NormalizedRect = crop.clamped()
        private set

    private var surfaceTexture: SurfaceTexture? = null

    /** The surface the VirtualDisplay draws into (valid after [start] returns). */
    val inputSurface: Surface
        get() = _inputSurface
            ?: throw IllegalStateException("CropRenderer not started")
    private var _inputSurface: Surface? = null

    private var thread: Thread? = null
    private val lock = Object()
    private val initLatch = java.util.concurrent.CountDownLatch(1)

    @Volatile
    private var running = false

    @Volatile
    private var frameAvailable = false

    @Volatile
    private var paused = false

    /** Accumulated pause time (ns, monotonic clock) subtracted from frame times. */
    private var pausedOffsetNs = 0L
    private var pauseStartNs = 0L

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var textureId = 0

    fun setCrop(rect: NormalizedRect) {
        crop = rect.clamped()
    }

    fun setPaused(p: Boolean) {
        if (p == paused) return
        paused = p
        val now = System.nanoTime()
        if (p) {
            pauseStartNs = now
        } else if (pauseStartNs != 0L) {
            pausedOffsetNs += now - pauseStartNs
            pauseStartNs = 0L
        }
    }

    fun start(encoderSurface: Surface) {
        check(thread == null) { "CropRenderer already started" }
        running = true
        thread = Thread({
            try {
                initGl(encoderSurface)
                initLatch.countDown()
                renderLoop()
            } catch (t: Throwable) {
                // The encoder/service owns error reporting; just stop cleanly.
                running = false
                initLatch.countDown()
            } finally {
                releaseGl()
            }
        }, "cropcast-egl").apply { start() }
        // Wait for GL init (texture + SurfaceTexture) so inputSurface is
        // valid before the service hands it to createVirtualDisplay.
        initLatch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        surfaceTexture?.setOnFrameAvailableListener(
            { synchronized(lock) { frameAvailable = true; lock.notifyAll() } },
            null,
        )
    }

    fun release() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        try {
            thread?.join(2000)
        } catch (_: InterruptedException) {
        }
        thread = null
        surfaceTexture?.release()
        surfaceTexture = null
        _inputSurface?.release()
        _inputSurface = null
    }

    // ---- GL setup (render thread only) ------------------------------------

    private fun initGl(encoderSurface: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        val config = chooseConfig()
        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }

        program = buildProgram()
        textureId = createExternalTexture()
        // The SurfaceTexture MUST be bound to the generated texture id —
        // its images land on that texture object, which is what drawFrame
        // samples. (Passing the GL_TEXTURE_EXTERNAL_OES enum here was the
        // black-frames bug: images went to a texture that is never read.)
        val st = SurfaceTexture(textureId).apply {
            setDefaultBufferSize(frameWidth, frameHeight)
        }
        surfaceTexture = st
        _inputSurface = Surface(st)
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
    }

    private fun chooseConfig(): EGLConfig {
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0)) {
            "eglChooseConfig failed"
        }
        return configs[0]!!
    }

    private fun buildProgram(): Int {
        val vertexShader = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()
        val fragmentShader = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """.trimIndent()
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexShader)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
        check(linkStatus[0] == GLES20.GL_TRUE) { "program link failed: ${GLES20.glGetProgramInfoLog(prog)}" }
        return prog
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    private fun createExternalTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        GLES20.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR.toFloat(),
        )
        GLES20.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR.toFloat(),
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        return textures[0]
    }

    // ---- Render loop -------------------------------------------------------

    private fun renderLoop() {
        val transform = FloatArray(16)
        while (running) {
            synchronized(lock) {
                while (running && !frameAvailable) {
                    try {
                        lock.wait(100)
                    } catch (_: InterruptedException) {
                    }
                }
                frameAvailable = false
            }
            if (!running) break
            val st = surfaceTexture ?: break
            st.updateTexImage()
            st.getTransformMatrix(transform)
            if (!paused) {
                val frameTimeNs = st.timestamp - pausedOffsetNs
                drawFrame(transform, frameTimeNs)
            }
        }
    }

    /**
     * Draw the cropped quad. The SurfaceTexture transform maps unit texture
     * space onto the captured frame; the crop rect selects a sub-region of it.
     */
    private fun drawFrame(transform: FloatArray, presentationTimeNs: Long) {
        val rect = crop
        // Four corners of the crop in unit space, pushed through the transform.
        val corners = arrayOf(
            floatArrayOf(rect.left, rect.top),
            floatArrayOf(rect.right, rect.top),
            floatArrayOf(rect.left, rect.bottom),
            floatArrayOf(rect.right, rect.bottom),
        )
        val uvs = FloatArray(8)
        for (i in corners.indices) {
            val x = corners[i][0]
            val y = corners[i][1]
            // transform is column-major 4x4; apply to (x, y, 0, 1).
            val u = transform[0] * x + transform[4] * y + transform[12]
            val v = transform[1] * x + transform[5] * y + transform[13]
            uvs[i * 2] = u
            uvs[i * 2 + 1] = v
        }
        // Triangle strip order: top-left, top-right, bottom-left, bottom-right.
        val positions = floatArrayOf(
            -1f, 1f,
            1f, 1f,
            -1f, -1f,
            1f, -1f,
        )
        val texCoords = floatArrayOf(
            uvs[0], uvs[1],
            uvs[2], uvs[3],
            uvs[4], uvs[5],
            uvs[6], uvs[7],
        )
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        val posLoc = GLES20.glGetAttribLocation(program, "aPosition")
        val texLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, floatBuffer(positions))
        GLES20.glEnableVertexAttribArray(texLoc)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 0, floatBuffer(texCoords))
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(texLoc)
        EGLExt.setPresentationTime(eglDisplay, eglSurface, presentationTimeNs)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(values); position(0) }

    private fun releaseGl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    companion object {
        private const val GL_TEXTURE_EXTERNAL_OES = 0x8D65
        /** EGL_RECORDABLE_ANDROID (0x3142): marks the surface usable by MediaCodec. */
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}

/** Small wrapper: eglPresentationTimeANDROID lives in EGLExt on API 26+. */
private object EGLExt {
    fun setPresentationTime(display: EGLDisplay, surface: EGLSurface, timeNs: Long) {
        android.opengl.EGLExt.eglPresentationTimeANDROID(display, surface, timeNs)
    }
}
