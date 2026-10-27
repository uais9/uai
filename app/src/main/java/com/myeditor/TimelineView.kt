package com.myeditor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

class TimelineView(context: Context) : View(context) {
    var clips: List<Clip> = emptyList()
    var thumbs: Map<String, Bitmap> = emptyMap()
    var playheadMs: Long = 0L
    var totalMs: Long = 0L
    var selectedIndex: Int = -1
    var onScrub: ((Long) -> Unit)? = null
    var onSelect: ((Int) -> Unit)? = null
    var onMoveClip: ((Int, Long, Int) -> Unit)? = null
    var onTrimClip: ((Int, Int, Long) -> Unit)? = null
    var clampTrim: ((Int, Int, Long) -> Long)? = null

    private val density = resources.displayMetrics.density
    private val rulerH = 22f * density
    private val rowH = 40f * density
    private val rowGap = 4f * density
    private val tracksTop = rulerH + 4f * density

    private var pxPerSec = 80f * density
    private var movingIndex = -1
    private var movingTrack = 0
    private var moveDeltaMs = 0L
    private var trimIndex = -1
    private var trimEdge = 0
    private var trimDeltaMs = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var touchMoved = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val rect = RectF()
    private val tile = RectF()
    private val handleRect = RectF()

    private val tickPaint = Paint().apply {
        color = Color.GRAY
        strokeWidth = density
    }
    private val labelPaint = Paint().apply {
        color = Color.LTGRAY
        textSize = 10f * density
        isAntiAlias = true
    }
    private val rowPaint = Paint().apply { color = Color.parseColor("#1E1E27") }
    private val fillPaint = Paint().apply { color = Color.parseColor("#2B3A67") }
    private val shadePaint = Paint().apply { color = Color.parseColor("#66000000") }
    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        isAntiAlias = true
    }
    private val headPaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 2f * density
        isAntiAlias = true
    }
    private val handlePaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 11f * density
        isAntiAlias = true
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                pxPerSec = (pxPerSec * detector.scaleFactor)
                    .coerceIn(15f * density, 500f * density)
                invalidate()
                return true
            }
        }
    )

    private val longPress = Runnable {
        if (!touchMoved && !scaleDetector.isInProgress && trimIndex < 0) {
            val i = hit(downX, downY)
            if (i >= 0) {
                movingIndex = i
                movingTrack = clips[i].track
                moveDeltaMs = 0L
                onSelect?.invoke(i)
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                invalidate()
            }
        }
    }

    private fun pxPerMs(): Float = pxPerSec / 1000f

    private fun rowTop(track: Int): Float =
        tracksTop + (MAX_TRACKS - 1 - track) * (rowH + rowGap)

    private fun trackAt(y: Float): Int {
        val rel = ((y - tracksTop) / (rowH + rowGap)).toInt().coerceIn(0, MAX_TRACKS - 1)
        return MAX_TRACKS - 1 - rel
    }

    private fun hit(x: Float, y: Float): Int {
        val t = playheadMs + ((x - width / 2f) / pxPerMs()).toLong()
        val trk = trackAt(y)
        for (i in clips.indices) {
            val c = clips[i]
            if (c.track == trk && t >= c.startMs && t < c.endMs) return i
        }
        return -1
    }

    // 1 = left edge, 2 = right edge, 0 = none (selected clip only)
    private fun hitEdge(x: Float, y: Float): Int {
        val c = clips.getOrNull(selectedIndex) ?: return 0
        val top = rowTop(c.track)
        if (y < top || y > top + rowH) return 0
        val k = pxPerMs()
        val cx = width / 2f
        val left = cx + (c.startMs - playheadMs) * k
        val right = left + c.trimmedMs * k
        val zone = maxOf(14f * density, minOf(24f * density, (right - left) / 3f))
        val dl = abs(x - left)
        val dr = abs(x - right)
        if (dl <= zone && dl <= dr) return 1
        if (dr <= zone) return 2
        return 0
    }

    private fun stepMs(): Long {
        val options = longArrayOf(500, 1000, 2000, 5000, 10000, 30000, 60000)
        for (o in options) {
            if (o * pxPerMs() >= 60f * density) return o
        }
        return 60000L
    }

    private fun label(t: Long): String {
        val s = "%d:%02d".format(t / 60000, (t / 1000) % 60)
        return if (t % 1000 != 0L) "$s.5" else s
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val k = pxPerMs()
        canvas.drawColor(Color.parseColor("#15151A"))

        val firstMs = playheadMs - (cx / k).toLong() - 1000L
        val lastMs = playheadMs + (cx / k).toLong() + 1000L
        val step = stepMs()
        var t = (firstMs / step) * step
        if (t < 0L) t = 0L
        while (t <= lastMs) {
            val x = cx + (t - playheadMs) * k
            canvas.drawLine(x, 0f, x, rulerH * 0.5f, tickPaint)
            canvas.drawText(label(t), x + 3f * density, rulerH * 0.9f, labelPaint)
            t += step
        }

        for (k2 in 0 until MAX_TRACKS) {
            val rt = rowTop(k2)
            canvas.drawRect(0f, rt, w, rt + rowH, rowPaint)
        }

        if (clips.isEmpty()) {
            canvas.drawText(
                "Add clips to start",
                cx - 50f * density,
                rowTop(0) + rowH / 2f,
                labelPaint
            )
        }

        for (i in clips.indices) {
            val c = clips[i]
            var start = c.startMs
            var len = c.trimmedMs
            var trk = c.track
            if (i == movingIndex) {
                start += moveDeltaMs
                trk = movingTrack
            }
            if (i == trimIndex) {
                if (trimEdge == 1) {
                    start += trimDeltaMs
                    len -= trimDeltaMs
                } else {
                    len += trimDeltaMs
                }
            }
            val top = rowTop(trk)
            val bottom = top + rowH
            val left = cx + (start - playheadMs) * k
            val right = left + len * k
            if (right < 0f || left > w) continue
            rect.set(left, top, right, bottom)

            canvas.save()
            canvas.clipRect(rect)
            canvas.drawRect(rect, fillPaint)
            val bmp = thumbs[c.uri]
            if (bmp != null && bmp.height > 0) {
                val tileH = bottom - top
                val tileW = tileH * bmp.width / bmp.height
                if (tileW >= 1f) {
                    var x = left
                    while (x < right && x < w) {
                        if (x + tileW > 0f) {
                            tile.set(x, top, x + tileW, bottom)
                            canvas.drawBitmap(bmp, null, tile, null)
                        }
                        x += tileW
                    }
                }
            }
            canvas.drawRect(rect, shadePaint)
            if (right - left > 40f * density) {
                val tx = maxOf(left, 0f) + 4f * density
                var text = "%.1fs".format(len / 1000f)
                if (c.opacity < 0.99f) text += "  ${(c.opacity * 100).toInt()}%"
                canvas.drawText(text, tx, top + 14f * density, textPaint)
            }
            canvas.restore()

            if (i == movingIndex) {
                borderPaint.color = Color.YELLOW
                canvas.drawRect(rect, borderPaint)
            } else if (i == selectedIndex) {
                borderPaint.color = Color.parseColor("#5C8DFF")
                canvas.drawRect(rect, borderPaint)
            }

            if (i == selectedIndex && movingIndex < 0) {
                val hw = 7f * density
                val hh = (bottom - top) * 0.6f
                val mid = (top + bottom) / 2f
                handleRect.set(left, mid - hh / 2f, left + hw, mid + hh / 2f)
                canvas.drawRoundRect(handleRect, 3f * density, 3f * density, handlePaint)
                handleRect.set(right - hw, mid - hh / 2f, right, mid + hh / 2f)
                canvas.drawRoundRect(handleRect, 3f * density, 3f * density, handlePaint)
            }
        }

        canvas.drawLine(cx, 0f, cx, h, headPaint)
        canvas.drawCircle(cx, 4f * density, 4f * density, headPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                lastX = e.x
                touchMoved = false
                movingIndex = -1
                moveDeltaMs = 0L
                trimIndex = -1
                trimEdge = 0
                trimDeltaMs = 0L
                val edge = hitEdge(e.x, e.y)
                if (edge != 0) {
                    trimIndex = selectedIndex
                    trimEdge = edge
                } else {
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                touchMoved = true
                removeCallbacks(longPress)
                trimIndex = -1
                trimDeltaMs = 0L
                lastX = e.x
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val idx = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(idx)
                touchMoved = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount > 1 || scaleDetector.isInProgress) {
                    touchMoved = true
                    lastX = e.x
                } else {
                    if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) touchMoved = true
                    if (trimIndex >= 0) {
                        val raw = ((e.x - downX) / pxPerMs()).toLong()
                        trimDeltaMs = clampTrim?.invoke(trimIndex, trimEdge, raw) ?: raw
                        invalidate()
                    } else if (movingIndex >= 0) {
                        moveDeltaMs = ((e.x - downX) / pxPerMs()).toLong()
                        movingTrack = trackAt(e.y)
                        invalidate()
                    } else if (touchMoved) {
                        removeCallbacks(longPress)
                        val dx = e.x - lastX
                        val target = playheadMs - (dx / pxPerMs()).toLong()
                        onScrub?.invoke(target.coerceIn(0L, totalMs))
                    }
                    lastX = e.x
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (trimIndex >= 0) {
                    val i = trimIndex
                    val ed = trimEdge
                    val d = trimDeltaMs
                    trimIndex = -1
                    trimEdge = 0
                    trimDeltaMs = 0L
                    onTrimClip?.invoke(i, ed, d)
                    invalidate()
                } else if (movingIndex >= 0) {
                    val i = movingIndex
                    val c = clips.getOrNull(i)
                    val d = moveDeltaMs
                    val trk = movingTrack
                    movingIndex = -1
                    moveDeltaMs = 0L
                    if (c != null) onMoveClip?.invoke(i, c.startMs + d, trk)
                    invalidate()
                } else if (!touchMoved) {
                    onSelect?.invoke(hit(e.x, e.y))
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                movingIndex = -1
                moveDeltaMs = 0L
                trimIndex = -1
                trimEdge = 0
                trimDeltaMs = 0L
                invalidate()
            }
        }
        return true
    }
}