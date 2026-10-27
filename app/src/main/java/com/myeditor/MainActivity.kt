package com.myeditor

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: VideoRenderer
    private lateinit var project: Project
    private lateinit var projectFile: File
    private lateinit var strip: LinearLayout
    private lateinit var info: TextView
    private lateinit var seek: SeekBar
    private lateinit var playBtn: Button
    private var selected = -1
    private var dragging = false
    private val thumbs = HashMap<String, Bitmap>()
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 50)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun fmt(ms: Long): String {
        val t = ms / 100
        return "%d:%02d.%d".format(t / 600, (t / 10) % 60, t % 10)
    }

    private fun small(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 12f
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
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

        seek = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) renderer.seek(p.toLong())
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {
                    dragging = true
                }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    dragging = false
                }
            })
        }

        playBtn = small("Pause") { togglePlay() }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(playBtn)
            addView(small("Split") { splitHere() })
            addView(small("Set In") { setIn() })
            addView(small("Set Out") { setOut() })
        }

        strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scroll = HorizontalScrollView(this).apply { addView(strip) }

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(small("Add clips") { pickClips() })
            addView(small("Remove") { removeSelected() })
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            fitsSystemWindows = true
            addView(glView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(info)
            addView(seek)
            addView(controls)
            addView(
                scroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(bottom)
        }
        setContentView(root)
        refresh()
    }

    private fun tick() {
        val clip = project.clips.getOrNull(selected) ?: return
        val pos = renderer.position()
        if (!dragging) seek.progress = pos.toInt()
        if ((renderer.isPlaying() || renderer.ended) && pos >= clip.trimEndMs - 40) {
            renderer.seek(clip.trimStartMs)
            renderer.play()
        }
        playBtn.text = if (renderer.isPlaying()) "Pause" else "Play"
    }

    private fun togglePlay() {
        if (renderer.isPlaying()) renderer.pause() else renderer.play()
    }

    private fun splitHere() {
        val clip = project.clips.getOrNull(selected) ?: return
        val p = renderer.position()
        if (p <= clip.trimStartMs + 300 || p >= clip.trimEndMs - 300) {
            Toast.makeText(this, "Move the bar inside the clip first", Toast.LENGTH_SHORT).show()
            return
        }
        renderer.pause()
        project.clips[selected] = clip.copy(trimEndMs = p)
        project.clips.add(selected + 1, clip.copy(trimStartMs = p))
        project.save(projectFile)
        refresh()
    }

    private fun setIn() {
        val clip = project.clips.getOrNull(selected) ?: return
        val p = renderer.position()
        if (p >= clip.trimEndMs - 300) {
            Toast.makeText(this, "In point must be before Out", Toast.LENGTH_SHORT).show()
            return
        }
        project.clips[selected] = clip.copy(trimStartMs = p)
        project.save(projectFile)
        refresh()
    }

    private fun setOut() {
        val clip = project.clips.getOrNull(selected) ?: return
        val p = renderer.position()
        if (p <= clip.trimStartMs + 300) {
            Toast.makeText(this, "Out point must be after In", Toast.LENGTH_SHORT).show()
            return
        }
        project.clips[selected] = clip.copy(trimEndMs = p)
        project.save(projectFile)
        refresh()
    }

    private fun removeSelected() {
        if (selected in project.clips.indices) {
            project.clips.removeAt(selected)
            selected = -1
            renderer.pause()
            project.save(projectFile)
            refresh()
        }
    }

    private fun pickClips() {
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
                renderer.setVideo(Uri.parse(clip.uri), clip.trimStartMs)
                refresh()
            }
            strip.addView(box)
            loadThumb(clip.uri, clip.trimStartMs, img)
        }
        val sel = project.clips.getOrNull(selected)
        if (sel != null) seek.max = sel.durationMs.toInt()
        var text = "${project.clips.size} clips  |  ${fmt(project.totalMs())}"
        if (sel != null) text += "\nIn ${fmt(sel.trimStartMs)}   Out ${fmt(sel.trimEndMs)}"
        info.text = text
    }

    private fun loadThumb(uriStr: String, startMs: Long, view: ImageView) {
        val key = "$uriStr@$startMs"
        val cached = thumbs[key]
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        thread {
            try {
                val r = MediaMetadataRetriever()
                r.setDataSource(this, Uri.parse(uriStr))
                val frame = r.getFrameAtTime(
                    startMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
                r.release()
                if (frame != null) {
                    val h = (frame.height * 240f / frame.width).toInt().coerceAtLeast(1)
                    val smallBmp = Bitmap.createScaledBitmap(frame, 240, h, true)
                    runOnUiThread {
                        thumbs[key] = smallBmp
                        view.setImageBitmap(smallBmp)
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
        handler.removeCallbacks(ticker)
        renderer.pause()
        glView.onPause()
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        handler.post(ticker)
    }

    override fun onDestroy() {
        super.onDestroy()
        renderer.release()
    }
}