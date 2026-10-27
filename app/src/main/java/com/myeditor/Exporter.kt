package com.myeditor

import android.app.Activity
import android.content.ContentValues
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import kotlin.concurrent.thread

@OptIn(UnstableApi::class)
class Exporter(private val activity: Activity) {

    private val progressHolder = ProgressHolder()
    private var transformer: Transformer? = null

    var running = false
        private set

    fun progressText(): String {
        val tr = transformer ?: return "Exporting..."
        val state = tr.getProgress(progressHolder)
        return if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
            "Exporting... ${progressHolder.progress}% (keep the app open)"
        } else {
            "Exporting..."
        }
    }

    fun cancel() {
        try {
            transformer?.cancel()
        } catch (e: Exception) {
        }
        transformer = null
        running = false
    }

    private fun outputSize(first: Clip): Pair<Int, Int> {
        var w = 1280
        var h = 720
        try {
            val r = MediaMetadataRetriever()
            r.setDataSource(activity, Uri.parse(first.uri))
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

    // Builds one "lane" of clips (one track), with gaps where the track is empty.
    private fun buildSequence(trackClips: List<Clip>): EditedMediaItemSequence {
        val seq = EditedMediaItemSequence.Builder(emptyList<EditedMediaItem>())
        var cursor = 0L
        for (c in trackClips) {
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
        return seq.build()
    }

    fun start(clips: List<Clip>, onFinished: (String, Boolean) -> Unit) {
        val sorted = clips.sortedBy { it.startMs }
        if (sorted.isEmpty()) {
            onFinished("Add clips first", false)
            return
        }
        val (w, h) = outputSize(sorted[0])

        // One lane per track, lowest track first (the bottom layer).
        val byTrack = clips.groupBy { it.track }.toSortedMap()
        val trackOrder = byTrack.keys.toList()
        val sequences = trackOrder.map { t ->
            buildSequence(byTrack[t]!!.sortedBy { it.startMs })
        }

        // Decides how see-through each layer is at each moment.
        val compositorSettings = object : VideoCompositorSettings {
            override fun getOutputSize(inputSizes: List<Size>): Size = Size(w, h)

            override fun getOverlaySettings(
                inputId: Int,
                presentationTimeUs: Long
            ): OverlaySettings {
                val tMs = presentationTimeUs / 1000L
                val track = trackOrder.getOrNull(inputId)
                val clip = if (track == null) null else {
                    byTrack[track]?.firstOrNull { tMs >= it.startMs && tMs < it.endMs }
                }
                return OverlaySettings.Builder()
                    .setAlphaScale(clip?.opacity ?: 0f)
                    .build()
            }
        }

        val composition = Composition.Builder(sequences)
            .setVideoCompositorSettings(compositorSettings)
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

        val out = File(activity.cacheDir, "export_" + System.currentTimeMillis() + ".mp4")
        if (out.exists()) out.delete()

        val t = Transformer.Builder(activity)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    running = false
                    transformer = null
                    thread {
                        val where = try {
                            saveToGallery(out)
                        } catch (e: Exception) {
                            ""
                        }
                        out.delete()
                        activity.runOnUiThread {
                            if (where.isEmpty()) {
                                onFinished("Export finished but saving failed", false)
                            } else {
                                onFinished("Saved to $where", true)
                            }
                        }
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    running = false
                    transformer = null
                    onFinished(
                        "Export failed (code ${exportException.errorCode}): ${exportException.message}",
                        false
                    )
                }
            })
            .build()
        transformer = t
        running = true
        t.start(composition, out.absolutePath)
    }

    private fun saveToGallery(src: File): String {
        val name = "MyEditor_" + System.currentTimeMillis() + ".mp4"
        val resolver = activity.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MyEditor")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return ""
            resolver.openOutputStream(uri)?.use { o ->
                src.inputStream().use { it.copyTo(o) }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "Movies/MyEditor (open your Gallery)"
        } else {
            val dir = activity.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: activity.filesDir
            val dest = File(dir, name)
            src.copyTo(dest, true)
            return dest.absolutePath
        }
    }
}