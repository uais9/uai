package com.myeditor

import android.app.Activity
import android.content.Intent
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout

class MainActivity : Activity() {
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: VideoRenderer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderer = VideoRenderer(this)
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        val button = Button(this).apply {
            text = "Pick video"
            setOnClickListener {
                val i = Intent(Intent.ACTION_GET_CONTENT).apply { type = "video/*" }
                startActivityForResult(i, 1)
            }
        }
        val root = FrameLayout(this)
        root.addView(glView)
        root.addView(
            button,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = 120 }
        )
        setContentView(root)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) {
            data?.data?.let { renderer.setVideo(it) }
        }
    }

    override fun onPause() { super.onPause(); glView.onPause() }
    override fun onResume() { super.onResume(); glView.onResume() }
    override fun onDestroy() { super.onDestroy(); renderer.release() }
}