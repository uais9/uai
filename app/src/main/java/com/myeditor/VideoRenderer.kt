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

class VideoRenderer(private val context: Context) :
    GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    private val OES = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
    private var program = 0
    private var texId = 0
    @Volatile private var surfaceTexture: SurfaceTexture? = null
    private var player: MediaPlayer? = null
    @Volatile private var frameAvailable = false
    private var hasFrame = false
    private val startTime = System.nanoTime()
    private val stMatrix = FloatArray(16)

    @Volatile var effectOn = false
    @Volatile var showVideo = true
    @Volatile var ended = false
    @Volatile private var videoW = 0
    @Volatile private var videoH = 0
    @Volatile private var prepared = false
    @Volatile private var wantPlay = false
    @Volatile private var targetMs = 0L
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
        uniform float uTime;
        uniform float uEffect;
        void main() {
            vec2 uv = vUv;
            if (uEffect > 0.5) {
                vec2 c = uv - 0.5;
                float d = length(c);
                uv += normalize(c + 0.0001) * 0.02 * sin(d * 40.0 - uTime * 4.0);
            }
            vec2 t = (uSTMatrix * vec4(uv, 0.0, 1.0)).xy;
            gl_FragColor = texture2D(uTex, t);
        }
    """

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram(vertexShader, fragmentShader)
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        texId = t[0]
        GLES20.glBindTexture(OES, texId)
        GLES20.glTexParameteri(OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        Matrix.setIdentityM(stMatrix, 0)
        hasFrame = false
        val st = SurfaceTexture(texId)
        st.setOnFrameAvailableListener(this)
        surfaceTexture = st
        player?.setSurface(Surface(st))
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
        val st = surfaceTexture ?: return
        if (frameAvailable) {
            frameAvailable = false
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)
            hasFrame = true
        }
        if (!hasFrame || !showVideo) return

        var vx = 0
        var vy = 0
        var vw = surfaceW
        var vh = surfaceH
        if (videoW > 0 && videoH > 0) {
            val videoAspect = videoW.toFloat() / videoH
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

        GLES20.glUseProgram(program)
        val pos = GLES20.glGetAttribLocation(program, "aPosition")
        val uv = GLES20.glGetAttribLocation(program, "aUv")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, vertexBuf)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, uvBuf)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uSTMatrix"), 1, false, stMatrix, 0)
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(program, "uTime"),
            (System.nanoTime() - startTime) / 1_000_000_000f
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(program, "uEffect"),
            if (effectOn) 1f else 0f
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(OES, texId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun onFrameAvailable(st: SurfaceTexture?) {
        frameAvailable = true
    }

    private fun seekExact(mp: MediaPlayer, ms: Long) {
        if (Build.VERSION.SDK_INT >= 26) {
            mp.seekTo(ms, MediaPlayer.SEEK_CLOSEST)
        } else {
            mp.seekTo(ms.toInt())
        }
    }

    // Returns false only when the GL surface is not ready yet
    fun setVideo(uri: Uri, startMs: Long): Boolean {
        val st = surfaceTexture ?: return false
        try {
            player?.release()
        } catch (e: Exception) {
        }
        player = null
        hasFrame = false
        prepared = false
        ended = false
        videoW = 0
        videoH = 0
        targetMs = startMs
        val mp = MediaPlayer()
        try {
            mp.setDataSource(context, uri)
            mp.setSurface(Surface(st))
            mp.isLooping = false
            mp.setOnVideoSizeChangedListener { _, w, h ->
                videoW = w
                videoH = h
            }
            mp.setOnCompletionListener { ended = true }
            mp.setOnPreparedListener { m ->
                prepared = true
                seekExact(m, targetMs)
                if (wantPlay) m.start()
            }
            mp.prepareAsync()
            player = mp
        } catch (e: Exception) {
            try {
                mp.release()
            } catch (e2: Exception) {
            }
            player = null
        }
        return true
    }

    fun isReady(): Boolean = player != null && prepared

    fun seek(ms: Long) {
        targetMs = ms
        val p = player ?: return
        if (!prepared) return
        try {
            ended = false
            seekExact(p, ms)
        } catch (e: Exception) {
        }
    }

    fun position(): Long {
        val p = player ?: return 0L
        if (!prepared) return 0L
        return try {
            p.currentPosition.toLong()
        } catch (e: Exception) {
            0L
        }
    }

    fun play() {
        wantPlay = true
        val p = player ?: return
        if (!prepared) return
        try {
            ended = false
            p.start()
        } catch (e: Exception) {
        }
    }

    fun pause() {
        wantPlay = false
        val p = player ?: return
        if (!prepared) return
        try {
            if (p.isPlaying) p.pause()
        } catch (e: Exception) {
        }
    }

    fun release() {
        try {
            player?.release()
        } catch (e: Exception) {
        }
        player = null
        surfaceTexture?.release()
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