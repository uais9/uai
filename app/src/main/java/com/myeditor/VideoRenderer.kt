package com.myeditor

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Build
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class VideoRenderer(private val context: Context) : GLSurfaceView.Renderer {

    private class Slot {
        var texId = 0
        var st: SurfaceTexture? = null
        var surface: Surface? = null
        var player: MediaPlayer? = null
        @Volatile var frameAvailable = false
        var hasFrame = false
        @Volatile var prepared = false
        @Volatile var wantPlay = false
        @Volatile var ended = false
        @Volatile var targetMs = 0L
        @Volatile var videoW = 0
        @Volatile var videoH = 0
        @Volatile var visible = false
        @Volatile var opacity = 1f
        val stMatrix = FloatArray(16)
    }

    private val OES = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
    private var program = 0
    private val slots = Array(MAX_TRACKS) { Slot() }
    private var surfaceW = 1
    private var surfaceH = 1

    private val vertexBuf = floatBuf(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val uvBuf = floatBuf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

    private fun floatBuf(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(a).also { it.position(0) }

    private val vertexShader = """
        attribute vec2 aPosition;
        attribute vec2 aUv;
        varying vec2 vUv;
        void main() {
            gl_Position = vec4(aPosition, 0.0, 1.0);
            vUv = aUv;
        }
    """

    private val fragmentShader = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vUv;
        uniform samplerExternalOES uTex;
        uniform mat4 uSTMatrix;
        uniform float uOpacity;
        void main() {
            vec2 t = (uSTMatrix * vec4(vUv, 0.0, 1.0)).xy;
            vec4 c = texture2D(uTex, t);
            gl_FragColor = vec4(c.rgb, uOpacity);
        }
    """

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram(vertexShader, fragmentShader)
        for (s in slots) {
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            s.texId = t[0]
            GLES20.glBindTexture(OES, s.texId)
            GLES20.glTexParameteri(OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            Matrix.setIdentityM(s.stMatrix, 0)
            s.hasFrame = false
            s.frameAvailable = false

            val oldSt = s.st
            val oldSurface = s.surface
            val st = SurfaceTexture(s.texId)
            st.setOnFrameAvailableListener { s.frameAvailable = true }
            val sf = Surface(st)
            s.st = st
            s.surface = sf
            s.player?.setSurface(sf)
            try {
                oldSurface?.release()
            } catch (e: Exception) {
            }
            try {
                oldSt?.release()
            } catch (e: Exception) {
            }
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceW = width
        surfaceH = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glViewport(0, 0, surfaceW, surfaceH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (program == 0) return

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(program)
        val pos = GLES20.glGetAttribLocation(program, "aPosition")
        val uv = GLES20.glGetAttribLocation(program, "aUv")
        val uMat = GLES20.glGetUniformLocation(program, "uSTMatrix")
        val uOp = GLES20.glGetUniformLocation(program, "uOpacity")
        val uTex = GLES20.glGetUniformLocation(program, "uTex")

        for (s in slots) {
            val st = s.st ?: continue
            if (s.frameAvailable) {
                s.frameAvailable = false
                st.updateTexImage()
                st.getTransformMatrix(s.stMatrix)
                s.hasFrame = true
            }
            if (!s.hasFrame || !s.visible) continue

            var vx = 0
            var vy = 0
            var vw = surfaceW
            var vh = surfaceH
            if (s.videoW > 0 && s.videoH > 0) {
                val videoAspect = s.videoW.toFloat() / s.videoH
                val screenAspect = surfaceW.toFloat() / surfaceH
                if (videoAspect > screenAspect) {
                    vh = (surfaceW / videoAspect).toInt()
                    vy = (surfaceH - vh) / 2
                } else {
                    vw = (surfaceH * videoAspect).toInt()
                    vx = (surfaceW - vw) / 2
                }
            }
            GLES20.glViewport(vx, vy, vw, vh)

            GLES20.glEnableVertexAttribArray(pos)
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, vertexBuf)
            GLES20.glEnableVertexAttribArray(uv)
            GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, uvBuf)
            GLES20.glUniformMatrix4fv(uMat, 1, false, s.stMatrix, 0)
            GLES20.glUniform1f(uOp, s.opacity)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(OES, s.texId)
            GLES20.glUniform1i(uTex, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun seekExact(mp: MediaPlayer, ms: Long) {
        if (Build.VERSION.SDK_INT >= 26) {
            mp.seekTo(ms, MediaPlayer.SEEK_CLOSEST)
        } else {
            mp.seekTo(ms.toInt())
        }
    }

    // Returns false only when the GL surface is not ready yet
    fun setVideo(slot: Int, uri: Uri, startMs: Long): Boolean {
        val s = slots[slot]
        val sf = s.surface ?: return false
        try {
            s.player?.release()
        } catch (e: Exception) {
        }
        s.player = null
        s.hasFrame = false
        s.prepared = false
        s.ended = false
        s.videoW = 0
        s.videoH = 0
        s.targetMs = startMs
        val mp = MediaPlayer()
        try {
            mp.setDataSource(context, uri)
            mp.setSurface(sf)
            mp.isLooping = false
            mp.setOnVideoSizeChangedListener { _, w, h ->
                s.videoW = w
                s.videoH = h
            }
            mp.setOnCompletionListener { s.ended = true }
            mp.setOnPreparedListener { m ->
                s.prepared = true
                seekExact(m, s.targetMs)
                if (s.wantPlay) m.start()
            }
            mp.prepareAsync()
            s.player = mp
        } catch (e: Exception) {
            try {
                mp.release()
            } catch (e2: Exception) {
            }
            s.player = null
        }
        return true
    }

    fun setLayer(slot: Int, visible: Boolean, opacity: Float) {
        val s = slots[slot]
        s.visible = visible
        s.opacity = opacity
    }

    fun setOpacity(slot: Int, opacity: Float) {
        slots[slot].opacity = opacity
    }

    fun isReady(slot: Int): Boolean {
        val s = slots[slot]
        return s.player != null && s.prepared
    }

    fun hasEnded(slot: Int): Boolean = slots[slot].ended

    fun seek(slot: Int, ms: Long) {
        val s = slots[slot]
        s.targetMs = ms
        val p = s.player ?: return
        if (!s.prepared) return
        try {
            s.ended = false
            seekExact(p, ms)
        } catch (e: Exception) {
        }
    }

    fun position(slot: Int): Long {
        val s = slots[slot]
        val p = s.player ?: return 0L
        if (!s.prepared) return 0L
        return try {
            p.currentPosition.toLong()
        } catch (e: Exception) {
            0L
        }
    }

    fun play(slot: Int) {
        val s = slots[slot]
        s.wantPlay = true
        val p = s.player ?: return
        if (!s.prepared) return
        try {
            s.ended = false
            p.start()
        } catch (e: Exception) {
        }
    }

    fun pause(slot: Int) {
        val s = slots[slot]
        s.wantPlay = false
        val p = s.player ?: return
        if (!s.prepared) return
        try {
            if (p.isPlaying) p.pause()
        } catch (e: Exception) {
        }
    }

    fun pauseAll() {
        for (i in 0 until MAX_TRACKS) pause(i)
    }

    fun release() {
        for (s in slots) {
            try {
                s.player?.release()
            } catch (e: Exception) {
            }
            s.player = null
            try {
                s.st?.release()
            } catch (e: Exception) {
            }
        }
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException(GLES20.glGetShaderInfoLog(s))
        return s
    }
}