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
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: VideoRenderer
    private lateinit var project: Project
    private lateinit var projectFile: File
    private lateinit var timeline: TimelineView
    private lateinit var info: TextView
    private lateinit var playBtn: Button
    private lateinit var exporter: Exporter

    private var selected = -1
    private var playheadMs = 0L
    private var playing = false
    private val loaded = arrayOfNulls<Clip>(MAX_TRACKS)
    private var lastTickAt = 0L
    private var lastSeekAt = 0L
    private var needShow = true

    private val undoStack = ArrayList<List<Clip>>()
    private val redoStack = ArrayList<List<Clip>>()

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
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(2), 0, dp(2), 0)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectFile = File(filesDir, "project.json")
        project = Project.load(projectFile)
        exporter = Exporter(this)

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
            onMoveClip = { i, s, tr -> moveClip(i, s, tr) }
            onTrimClip = { i, edge, d -> trimClip(i, edge, d) }
            clampTrim = { i, edge, raw -> clampTrimDelta(i, edge, raw) }
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
            addView(small("Undo") { undo() })
            addView(small("Redo") { redo() })
            addView(small("Remove") { removeSelected() })
            addView(small("Add") { pickClips() })
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

    private fun startExport() {
        if (exporter.running) {
            toast("Export already running")
            return
        }
        if (project.clips.isEmpty()) {
            toast("Add clips first")
            return
        }
        stopPlayback()
        exporter.start(project.clips.toList()) { msg, ok ->
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            if (ok) updateInfo() else info.text = msg
        }
        if (exporter.running) info.text = "Exporting..."
    }

    // ---------- playback ----------

    // Makes one track (renderer slot) show the right clip for time t.
    // Returns false only when the GL surface is not ready yet.
    private fun syncTrack(track: Int, t: Long, isPlaying: Boolean): Boolean {
        val clip = project.clipOnTrack(t, track)
        if (clip == null) {
            renderer.setLayer(track, false, 1f)
            renderer.pause(track)
            loaded[track] = null
            return true
        }
        renderer.setLayer(track, true, clip.opacity)
        val pos = clip.trimStartMs + (t - clip.startMs)
        val prev = loaded[track]
        if (prev !== clip) {
            if (prev != null && prev.uri == clip.uri && renderer.isReady(track)) {
                val contiguous = clip.trimStartMs == prev.trimEndMs &&
                    clip.startMs == prev.endMs
                if (!isPlaying || !contiguous) {
                    renderer.seek(track, pos)
                }
                loaded[track] = clip
            } else {
                if (!renderer.setVideo(track, Uri.parse(clip.uri), pos)) return false
                loaded[track] = clip
            }
            if (isPlaying) renderer.play(track) else renderer.pause(track)
        } else if (!isPlaying) {
            renderer.seek(track, pos)
        }
        return true
    }

    private fun showFrameAt(t: Long): Boolean {
        var ok = true
        for (tr in 0 until MAX_TRACKS) {
            if (!syncTrack(tr, t, false)) ok = false
        }
        return ok
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val dt = now - lastTickAt
        lastTickAt = now

        if (exporter.running) {
            info.text = exporter.progressText()
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
        var master: Clip? = null
        var masterTrack = -1
        for (tr in 0 until MAX_TRACKS) {
            syncTrack(tr, t, true)
            if (master == null) {
                val c = project.clipOnTrack(t, tr)
                if (c != null && loaded[tr] === c) {
                    master = c
                    masterTrack = tr
                }
            }
        }

        if (master == null) {
            t += dt
        } else if (renderer.isReady(masterTrack)) {
            val pos = renderer.position(masterTrack)
            if (renderer.hasEnded(masterTrack) || pos >= master.trimEndMs - 30) {
                t = master.endMs
            } else if (pos >= master.trimStartMs - 50) {
                t = maxOf(t, master.startMs + (pos - master.trimStartMs))
            } else {
                t += dt
            }
        } else {
            t += dt
        }

        if (t >= total) {
            setPlayhead(total)
            stopPlayback()
        } else {
            setPlayhead(t)
        }
    }

    private fun togglePlay() {
        if (exporter.running) return
        if (playing) {
            stopPlayback()
            return
        }
        if (project.clips.isEmpty()) return
        if (playheadMs >= project.totalMs()) setPlayhead(0L)
        showFrameAt(playheadMs)
        for (tr in 0 until MAX_TRACKS) {
            if (project.clipOnTrack(playheadMs, tr) != null) renderer.play(tr)
        }
        lastTickAt = SystemClock.elapsedRealtime()
        playing = true
    }

    private fun stopPlayback() {
        playing = false
        renderer.pauseAll()
        playBtn.text = "Play"
    }

    private fun setPlayhead(ms: Long) {
        playheadMs = ms.coerceIn(0L, project.totalMs())
        timeline.playheadMs = playheadMs
        timeline.invalidate()
        if (!exporter.running) updateInfo()
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

    // ---------- undo / redo ----------

    private fun pushUndo() {
        undoStack.add(project.clips.toList())
        if (undoStack.size > 50) undoStack.removeAt(0)
        redoStack.clear()
    }

    private fun undo() {
        if (undoStack.isEmpty()) {
            toast("Nothing to undo")
            return
        }
        redoStack.add(project.clips.toList())
        restore(undoStack.removeAt(undoStack.size - 1))
    }

    private fun redo() {
        if (redoStack.isEmpty()) {
            toast("Nothing to redo")
            return
        }
        undoStack.add(project.clips.toList())
        restore(redoStack.removeAt(redoStack.size - 1))
    }

    private fun restore(list: List<Clip>) {
        project.clips.clear()
        project.clips.addAll(list)
        if (selected >= project.clips.size) selected = -1
        afterEdit()
    }

    // ---------- editing ----------

    private fun selectClip(i: Int) {
        selected = i
        timeline.selectedIndex = i
        timeline.invalidate()
        if (!exporter.running) updateInfo()
    }

    private fun afterEdit() {
        stopPlayback()
        loaded.fill(null)
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
        pushUndo()
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
        pushUndo()
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
        pushUndo()
        val p = clip.trimStartMs + (t - clip.startMs)
        project.clips[selected] = clip.copy(trimEndMs = p)
        afterEdit()
    }

    // Neighbours only count when they are on the same track.
    private fun clampTrimDelta(i: Int, edge: Int, raw: Long): Long {
        val clip = project.clips.getOrNull(i) ?: return 0L
        val minD: Long
        val maxD: Long
        if (edge == 1) {
            var prevEnd = 0L
            for ((j, o) in project.clips.withIndex()) {
                if (j != i && o.track == clip.track &&
                    o.endMs <= clip.startMs && o.endMs > prevEnd
                ) prevEnd = o.endMs
            }
            minD = maxOf(-clip.trimStartMs, prevEnd - clip.startMs)
            maxD = clip.trimmedMs - 300L
        } else {
            var nextStart = Long.MAX_VALUE
            for ((j, o) in project.clips.withIndex()) {
                if (j != i && o.track == clip.track &&
                    o.startMs >= clip.endMs && o.startMs < nextStart
                ) nextStart = o.startMs
            }
            val room = clip.durationMs - clip.trimEndMs
            maxD = if (nextStart == Long.MAX_VALUE) room else minOf(room, nextStart - clip.endMs)
            minD = 300L - clip.trimmedMs
        }
        if (minD > maxD) return 0L
        return raw.coerceIn(minD, maxD)
    }

    private fun trimClip(i: Int, edge: Int, rawDelta: Long) {
        val clip = project.clips.getOrNull(i) ?: return
        val d = clampTrimDelta(i, edge, rawDelta)
        if (d == 0L) {
            timeline.invalidate()
            return
        }
        pushUndo()
        if (edge == 1) {
            project.clips[i] = clip.copy(
                startMs = clip.startMs + d,
                trimStartMs = clip.trimStartMs + d
            )
        } else {
            project.clips[i] = clip.copy(trimEndMs = clip.trimEndMs + d)
        }
        afterEdit()
    }

    private fun removeSelected() {
        if (selected !in project.clips.indices) {
            toast("Tap a clip first")
            return
        }
        pushUndo()
        project.clips.removeAt(selected)
        selected = -1
        timeline.selectedIndex = -1
        afterEdit()
    }

    private fun moveClip(i: Int, newStart: Long, newTrack: Int) {
        val clip = project.clips.getOrNull(i) ?: return
        val s = maxOf(0L, newStart)
        val tr = newTrack.coerceIn(0, MAX_TRACKS - 1)
        val e = s + clip.trimmedMs
        for ((j, o) in project.clips.withIndex()) {
            if (j != i && o.track == tr && s < o.endMs && e > o.startMs) {
                toast("No room there, clips can't overlap")
                timeline.invalidate()
                return
            }
        }
        if (s == clip.startMs && tr == clip.track) {
            timeline.invalidate()
            return
        }
        pushUndo()
        project.clips[i] = clip.copy(startMs = s, track = tr)
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
                if (added.isNotEmpty()) pushUndo()
                // New clips go on the base track, after the last clip there.
                var cursor = project.clips.filter { it.track == 0 }
                    .maxOfOrNull { it.endMs } ?: 0L
                for (c in added) {
                    project.clips.add(c.copy(startMs = cursor, track = 0))
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
            s += "\nSelected: track ${sel.track + 1}, starts ${fmt(sel.startMs)}, length ${fmt(sel.trimmedMs)}"
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
        loaded.fill(null)
        needShow = true
        lastTickAt = SystemClock.elapsedRealtime()
        handler.post(ticker)
    }

    override fun onDestroy() {
        super.onDestroy()
        exporter.cancel()
        renderer.release()
    }
}
