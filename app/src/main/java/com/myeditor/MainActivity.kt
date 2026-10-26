package com.myeditor

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: VideoRenderer
    private lateinit var project: Project
    private lateinit var projectFile: File
    private lateinit var strip: LinearLayout
    private lateinit var info: TextView
    private var selected = -1
    private val thumbs = HashMap<String, Bitmap>()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectFile = File(filesDir, "project.json")
        project = Project.load(projectFile)

        renderer = VideoRenderer(this)
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        info = TextView(this).apply {
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }

        strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scroll = HorizontalScrollView(this).apply { addView(strip) }

        val addBtn = Button(this).apply {
            text = "Add clips"
            setOnClickListener {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "video/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    )
                }
                startActivityForResult(i, 1)
            }
        }
        val removeBtn = Button(this).apply {
            text = "Remove"
            setOnClickListener {
                if (selected in project.clips.indices) {
                    project.clips.removeAt(selected)
                    selected = -1
                    project.save(projectFile)
                    refresh()
                }
            }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(addBtn)
            addView(removeBtn)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            fitsSystemWindows = true
            addView(glView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(info)
            addView(
                scroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(buttons)
        }
        setContentView(root)
        refresh()
    }

    private fun refresh() {
        strip.removeAllViews()
        project.clips.forEachIndexed { i, clip ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
                setBackgroundColor(if (i == selected) Color.parseColor("#3D6BFF") else Color.TRANSPARENT)
            }
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.DKGRAY)
                layoutParams = LinearLayout.LayoutParams(dp(96), dp(64))
            }
            val label = TextView(this).apply {
                text = fmt(clip.trimmedMs)
                setTextColor(Color.WHITE)
                textSize = 11f
                gravity = Gravity.CENTER_HORIZONTAL
            }
            box.addView(img)
            box.addView(label)
            box.setOnClickListener {
                selected = i
                renderer.setVideo(Uri.parse(clip.uri))
                refresh()
            }
            strip.addView(box)
            loadThumb(clip.uri, img)
        }
        info.text = "${project.clips.size} clips  |  ${fmt(project.totalMs())}"
    }

    private fun loadThumb(uriStr: String, view: ImageView) {
        val cached = thumbs[uriStr]
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        thread {
            try {
                val r = MediaMetadataRetriever()
                r.setDataSource(this, Uri.parse(uriStr))
                val frame = r.getFrameAtTime(0)
                r.release()
                if (frame != null) {
                    val w = 240
                    val h = (frame.height * 240f / frame.width).toInt().coerceAtLeast(1)
                    val small = Bitmap.createScaledBitmap(frame, w, h, true)
                    runOnUiThread {
                        thumbs[uriStr] = small
                        view.setImageBitmap(small)
                    }
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun addUris(uris: List<Uri>) {
        thread {
            val added = mutableListOf<Clip>()
            for (u in uris) {
                try {
                    contentResolver.takePersistableUriPermission(
                        u, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                }
                try {
                    val r = MediaMetadataRetriever()
                    r.setDataSource(this, u)
                    val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                    r.release()
                    if (d > 0) added.add(Clip(u.toString(), d))
                } catch (e: Exception) {
                }
            }
            runOnUiThread {
                project.clips.addAll(added)
                project.save(projectFile)
                refresh()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
            val uris = mutableListOf<Uri>()
            val cd = data.clipData
            if (cd != null) {
                for (i in 0 until cd.itemCount) {
                    uris.add(cd.getItemAt(i).uri)
                }
            } else {
                data.data?.let { uris.add(it) }
            }
            addUris(uris)
        }
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        renderer.release()
    }
}