package com.myeditor

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.transformer.Effects
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import kotlin.concurrent.thread

@OptIn(UnstableApi::class)
class MainActivity : Activity() {
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: VideoRenderer
    private lateinit var project: Project
    private lateinit var projectFile: File
    private lateinit var timeline: TimelineView
    private lateinit var info: TextView
    private lateinit var playBtn: Button

    private var selected = -1
    private var playheadMs = 0L
    private var playing = false
    private var loadedClip: Clip? = null
    private var lastTickAt = 0L
    private var lastSeekAt = 0L
    private var needShow = true

    private var exporting = false
    private var transformer: Transformer? = null
    private val progressHolder = ProgressHolder()

    private val thumbs = HashMap<String, Bitmap>()
    private val loading = HashSet<String>()
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 33)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun fmt(ms: Long): String {
        val t = ms / 100
        return "%d:%02d.%d".format(t / 600, (t / 10) % 60, t % 10)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
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

        timeline = TimelineView(this).apply {
            clips = project.clips
            thumbs = this@MainActivity.thumbs
            onScrub = { ms -> scrubTo(ms) }
            onSelect = { i -> selectClip(i) }
            onMoveClip = { i, s -> moveClip(i, s) }
        }

        playBtn = small("Play") { togglePlay() }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(playBtn)
            addView(small("Split") { splitHere() })
            addView(small("Trim start") { trimStart() })
            addView(small("Trim end") { trimEnd() })
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(small("Add clips") { pickClips() })
            addView(small("Remove") { removeSelected() })
            addView(small("Export") { startExport() })
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            fitsSystemWindows = true
            addView(glView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(info)
            addView(timeline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)))
            addView(controls)
            addView(bottom)
        }
        setContentView(root)
        refresh()
    }

    // ---------- export ----------

    private fun outputSize(first: Clip): Pair<Int, Int> {
        var w = 1280
        var h = 720
        try {
            val r = MediaMetadataRetriever()
            r.setDataSource(this, Uri.parse(first.uri))
            val vw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val vh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            r.release()
            var dw = vw
            var dh = vh
            if (rot == 90 || rot == 270) {
                dw = vh
                dh = vw
            }
            if (dw > 0 && dh > 0) {
                val longSide = maxOf(dw, dh)
                val scale = if (longSide > 1920) 1920f / longSide else 1f
                w = ((dw * scale).toInt() / 2) * 2
                h = ((dh * scale).toInt() / 2) * 2
            }
        } catch (e: Exception) {
        }
        return Pair(w, h)
    }

    private fun startExport() {
        if (exporting) {
            toast("Export already running")
            return
        }
        val sorted = project.clips.sortedBy { it.startMs }
        if (sorted.isEmpty()) {
            toast("Add clips first")
            return
        }
        stopPlayback()
        val (w, h) = outputSize(sorted[0])

        val seq = EditedMediaItemSequence.Builder(emptyList<EditedMediaItem>())
        var cursor = 0L
        for (c in sorted) {
            if (c.startMs > cursor) {
                seq.addGap((c.startMs - cursor) * 1000L)
            }
            val clipping = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(c.trimStartMs)
                .setEndPositionMs(c.trimEndMs)
                .build()
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse(c.uri))
                .setClippingConfiguration(clipping)
                .build()
            seq.addItem(EditedMediaItem.Builder(mediaItem).build())
            cursor = c.endMs
        }
        seq.experimentalSetForceVideoTrack(true)
        seq.experimentalSetForceAudioTrack(true)

        val composition = Composition.Builder(seq.build())
            .setEffects(
                Effects(
                    emptyList(),
                    listOf(
                        Presentation.createForWidthAndHeight(
                            w, h, Presentation.LAYOUT_SCALE_TO_FIT
                        )
                    )
                )
            )
            .build()

        val out = File(cacheDir, "export_" + System.currentTimeMillis() + ".mp4")
        if (out.exists()) out.delete()

        val t = Transformer.Builder(this)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    finishExport(out)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    failExport(exportException)
                }
            })
            .build()
        transformer = t
        exporting = true
        info.text = "Exporting..."
        t.start(composition, out.absolutePath)
    }

    private fun finishExport(file: File) {
        exporting = false
        transformer = null
        info.text = "Saving to gallery..."
        thread {
            val where = try {
                saveToGallery(file)
            } catch (e: Exception) {
                ""
            }
            file.delete()
            runOnUiThread {
                if (where.isEmpty()) {
                    Toast.makeText(this, "Export finished but saving failed", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Saved to $where", Toast.LENGTH_LONG).show()
                }
                updateInfo()
            }
        }
    }

    private fun failExport(e: ExportException) {
        exporting = false
        transformer = null
        val msg = "Export failed (code ${e.errorCode}): ${e.message}"
        info.text = msg
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun saveToGallery(src: File): String {
        val name = "MyEditor_" + System.currentTimeMillis() + ".mp4"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MyEditor")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return ""
            contentResolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            return "Movies/MyEditor (open your Gallery)"
        } else {
            val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
            val dest = File(dir, name)
            src.copyTo(dest, true)
            return dest.absolutePath
        }
    }

    // ---------- playback ----------

    private fun showFrameAt(t: Long): Boolean {
        val clip = project.clipAt(t)
        if (clip == null) {
            renderer.showVideo = false
            renderer.pause()
            loadedClip = null
            return true
        }
        val pos = clip.trimStartMs + (t - clip.startMs)
        renderer.showVideo = true
        val prev = loadedClip
        if (prev !== clip) {
            if (prev != null && prev.uri == clip.uri && renderer.isReady()) {
                renderer.seek(pos)
                loadedClip = clip
            } else {
                if (!renderer.setVideo(Uri.parse(clip.uri), pos)) return false
                loadedClip = clip
            }
        } else {
            renderer.seek(pos)
        }
        return true
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val dt = now - lastTickAt
        lastTickAt = now

        if (exporting) {
            val tr = transformer
            if (tr != null) {
                val state = tr.getProgress(progressHolder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    info.text = "Exporting... ${progressHolder.progress}%  (keep the app open)"
                } else {
                    info.text = "Exporting..."
                }
            }
            return
        }

        if (!playing) {
            if (needShow && showFrameAt(playheadMs)) needShow = false
            playBtn.text = "Play"
            return
        }
        playBtn.text = "Pause"

        val total = project.totalMs()
        if (playheadMs >= total) {
            stopPlayback()
            return
        }

        var t = playheadMs
        val clip = project.clipAt(t)
        if (clip == null) {
            renderer.showVideo = false
            renderer.pause()
            loadedClip = null
            t += dt
        } else {
            renderer.showVideo = true
            val prev = loadedClip
            if (prev !== clip) {
                if (prev != null && prev.uri == clip.uri && renderer.isReady()) {
                    val contiguous = clip.trimStartMs == prev.trimEndMs &&
                        clip.startMs == prev.endMs
                    if (!contiguous) {
                        renderer.seek(clip.trimStartMs + (t - clip.startMs))
                    }
                    renderer.play()
                    loadedClip = clip
                } else {
                    val pos = clip.trimStartMs + (t - clip.startMs)
                    renderer.play()
                    if (renderer.setVideo(Uri.parse(clip.uri), pos)) loadedClip = clip
                    t += dt
                }
            } else if (renderer.isReady()) {
                val pos = renderer.position()
                if (renderer.ended || pos >= clip.trimEndMs - 30) {
                    t = clip.endMs
                } else if (pos >= clip.trimStartMs - 50) {
                    t = maxOf(t, clip.startMs + (pos - clip.trimStartMs))
                } else {
                    t += dt
                }
            } else {
                t += dt
            }
        }

        if (t >= total) {
            setPlayhead(total)
            stopPlayback()
        } else {
            setPlayhead(t)
        }
    }

    private fun togglePlay() {
        if (exporting) return
        if (playing) {
            stopPlayback()
            return
        }
        if (project.clips.isEmpty()) return
        if (playheadMs >= project.totalMs()) setPlayhead(0L)
        showFrameAt(playheadMs)
        renderer.play()
        lastTickAt = SystemClock.elapsedRealtime()
        playing = true
    }

    private fun stopPlayback() {
        playing = false
        renderer.pause()
        playBtn.text = "Play"
    }

    private fun setPlayhead(ms: Long) {
        playheadMs = ms.coerceIn(0L, project.totalMs())
        timeline.playheadMs = playheadMs
        timeline.invalidate()
        if (!exporting) updateInfo()
    }

    private fun scrubTo(ms: Long) {
        if (playing) stopPlayback()
        setPlayhead(ms)
        val now = SystemClock.elapsedRealtime()
        if (now - lastSeekAt > 60) {
            lastSeekAt = now
            if (!showFrameAt(playheadMs)) needShow = true
        } else {
            needShow = true
        }
    }

    // ---------- editing ----------

    private fun selectClip(i: Int) {
        selected = i
        timeline.selectedIndex = i
        timeline.invalidate()
        if (!exporting) updateInfo()
    }

    private fun afterEdit() {
        stopPlayback()
        loadedClip = null
        needShow = true
        project.save(projectFile)
        refresh()
    }

    private fun splitHere() {
        val clip = project.clips.getOrNull(selected)
        if (clip == null) {
            toast("Tap a clip first")
            return
        }
        val t = playheadMs
        if (t <= clip.startMs + 300 || t >= clip.endMs - 300) {
            toast("Move the playhead inside the clip")
            return
        }
        val p = clip.trimStartMs + (t - clip.startMs)
        project.clips[selected] = clip.copy(trimEndMs = p)
        project.clips.add(selected + 1, clip.copy(startMs = t, trimStartMs = p))
        afterEdit()
    }

    private fun trimStart() {
        val clip = project.clips.getOrNull(selected)
        if (clip == null) {
            toast("Tap a clip first")
            return
        }
        val t = playheadMs
        if (t <= clip.startMs || t >= clip.endMs - 300) {
            toast("Put the playhead inside the clip")
            return
        }
        val p = clip.trimStartMs + (t - clip.startMs)
        project.clips[selected] = clip.copy(startMs = t, trimStartMs = p)
        afterEdit()
    }

    private fun trimEnd() {
        val clip = project.clips.getOrNull(selected)
        if (clip == null) {
            toast("Tap a clip first")
            return
        }
        val t = playheadMs
        if (t <= clip.startMs + 300 || t >= clip.endMs) {
            toast("Put the playhead inside the clip")
            return
        }
        val p = clip.trimStartMs + (t - clip.startMs)
        project.clips[selected] = clip.copy(trimEndMs = p)
        afterEdit()
    }

    private fun removeSelected() {
        if (selected !in project.clips.indices) {
            toast("Tap a clip first")
            return
        }
        project.clips.removeAt(selected)
        selected = -1
        timeline.selectedIndex = -1
        afterEdit()
    }

    private fun moveClip(i: Int, newStart: Long) {
        val clip = project.clips.getOrNull(i) ?: return
        val s = maxOf(0L, newStart)
        val e = s + clip.trimmedMs
        for ((j, o) in project.clips.withIndex()) {
            if (j != i && s < o.endMs && e > o.startMs) {
                toast("No room there, clips can't overlap")
                timeline.invalidate()
                return
            }
        }
        project.clips[i] = clip.copy(startMs = s)
        afterEdit()
    }

    // ---------- importing ----------

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
                var cursor = project.totalMs()
                for (c in added) {
                    project.clips.add(c.copy(startMs = cursor))
                    cursor += c.trimmedMs
                }
                project.save(projectFile)
                needShow = true
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

    // ---------- UI refresh ----------

    private fun loadThumb(uriStr: String) {
                if (thumbs.containsKey(uriStr) || loading.contains(uriStr)) return
        loading.add(uriStr)
        thread {
            try {
                val r = MediaMetadataRetriever()
                r.setDataSource(this, Uri.parse(uriStr))
                val frame = r.getFrameAtTime(0)
                r.release()
                if (frame != null) {
                    val h = 160
                    val w = (frame.width * h.toFloat() / frame.height).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createScaledBitmap(frame, w, h, true)
                    runOnUiThread {
                        thumbs[uriStr] = bmp
                        timeline.invalidate()
                    }
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun updateInfo() {
        val sel = project.clips.getOrNull(selected)
        var s = "${fmt(playheadMs)} / ${fmt(project.totalMs())}   |   ${project.clips.size} clips"
        if (sel != null) {
            s += "\nSelected: starts ${fmt(sel.startMs)}, length ${fmt(sel.trimmedMs)}"
        }
        info.text = s
    }

    private fun refresh() {
        timeline.totalMs = project.totalMs()
        timeline.selectedIndex = selected
        for (c in project.clips) loadThumb(c.uri)
        setPlayhead(playheadMs)
    }

    // ---------- lifecycle ----------

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        stopPlayback()
        glView.onPause()
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        loadedClip = null
        needShow = true
        lastTickAt = SystemClock.elapsedRealtime()
        handler.post(ticker)
    }

    override fun onDestroy() {
        super.onDestroy()
        transformer?.cancel()
        renderer.release()
    }
}