package com.azhilin.rokid.displaybridge.companion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import com.azhilin.rokid.displaybridge.protocol.DisplayLayoutPayload
import com.google.android.material.color.MaterialColors
import kotlin.math.hypot

class DisplayLayoutEditorView(context: Context) : View(context) {
    var onLayoutEdited: ((DisplayLayoutPayload, Boolean) -> Unit)? = null
    var onViewportChanged: ((RectF) -> Unit)? = null
    var value: DisplayLayoutPayload = DisplayLayoutPayload()
        private set
    private val viewport = RectF()
    private var startValue = value
    private var startX = 0f
    private var startY = 0f
    private var resizeHandle: FrameResizeHandle? = null
    private var dragging = false
    private val primary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF536BAF.toInt())
    private val onPrimary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary, Color.WHITE)
    private val outline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline, 0xFF777680.toInt())
    private val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFFE8E5ED.toInt())
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = outline; style = Paint.Style.STROKE; strokeWidth = dp(2f) }
    private val frameFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(primary, 72); style = Paint.Style.FILL }
    private val frameBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = primary; style = Paint.Style.STROKE; strokeWidth = dp(3f) }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = onPrimary; style = Paint.Style.FILL }
    private val hatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(outline, 190)
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        contentDescription = "Glasses frame placement editor"
    }

    fun setLayoutValue(next: DisplayLayoutPayload) {
        value = next.clamped()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateViewport()
        canvas.drawRect(viewport, border)
        val frame = frameRect(value)
        canvas.drawRect(frame, frameFill)
        drawClippedHatching(canvas, frame)
        canvas.drawRect(frame, frameBorder)
        corners(frame).forEach { canvas.drawCircle(it.first, it.second, dp(7f), handlePaint) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        updateViewport()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val frame = frameRect(value)
                resizeHandle = nearestHandle(frame, event.x, event.y)
                dragging = resizeHandle != null || frame.contains(event.x, event.y)
                if (!dragging) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                startValue = value
                startX = event.x
                startY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                val dx = (event.x - startX) * DisplayLayoutEditorModel.VIEWPORT_WIDTH / viewport.width()
                val dy = (event.y - startY) * DisplayLayoutEditorModel.VIEWPORT_HEIGHT / viewport.height()
                value = resizeHandle?.let { DisplayLayoutEditorModel.resize(startValue, it, dx, dy) }
                    ?: DisplayLayoutEditorModel.move(startValue, dx, dy)
                invalidate()
                onLayoutEdited?.invoke(value, false)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                onLayoutEdited?.invoke(value, true)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun updateViewport() {
        val gestureGutter = dp(GESTURE_GUTTER_DP)
        val availableWidth = (width - paddingLeft - paddingRight - gestureGutter * 2f).coerceAtLeast(1f)
        val availableHeight = (height - paddingTop - paddingBottom - gestureGutter * 2f).coerceAtLeast(1f)
        val viewportAspect = .75f
        val w: Float
        val h: Float
        if (availableWidth / availableHeight > viewportAspect) { h = availableHeight; w = h * viewportAspect }
        else { w = availableWidth; h = w / viewportAspect }
        val left = paddingLeft + gestureGutter + (availableWidth - w) / 2f
        val top = paddingTop + gestureGutter + (availableHeight - h) / 2f
        val changed = viewport.left != left || viewport.top != top || viewport.right != left + w || viewport.bottom != top + h
        viewport.set(left, top, left + w, top + h)
        if (changed) onViewportChanged?.invoke(RectF(viewport))
    }

    fun viewportBounds(): RectF {
        updateViewport()
        return RectF(viewport)
    }

    fun frameBounds(): RectF {
        updateViewport()
        return frameRect(value)
    }

    fun hasClippedRegion(): Boolean {
        val frame = frameBounds()
        return frame.left < viewport.left || frame.top < viewport.top ||
            frame.right > viewport.right || frame.bottom > viewport.bottom
    }

    private fun drawClippedHatching(canvas: Canvas, frame: RectF) {
        if (!hasClippedRegion()) return
        val save = canvas.save()
        canvas.clipRect(frame)
        canvas.clipOutRect(viewport)
        val step = dp(10f)
        val diagonal = height.toFloat() + width.toFloat()
        var x = -height.toFloat()
        while (x < width.toFloat()) {
            canvas.drawLine(x, height.toFloat(), x + diagonal, height - diagonal, hatchPaint)
            x += step
        }
        canvas.restoreToCount(save)
    }

    private fun frameRect(layout: DisplayLayoutPayload): RectF {
        val geometry = DisplayLayoutEditorModel.geometry(layout)
        return RectF(
            viewport.left + (geometry.left / 480.0 * viewport.width()).toFloat(),
            viewport.top + (geometry.top / 640.0 * viewport.height()).toFloat(),
            viewport.left + ((geometry.left + geometry.width) / 480.0 * viewport.width()).toFloat(),
            viewport.top + ((geometry.top + geometry.height) / 640.0 * viewport.height()).toFloat(),
        )
    }

    private fun corners(frame: RectF) = listOf(frame.left to frame.top, frame.right to frame.top,
        frame.left to frame.bottom, frame.right to frame.bottom)

    private fun nearestHandle(frame: RectF, x: Float, y: Float): FrameResizeHandle? {
        val handles = FrameResizeHandle.entries.zip(corners(frame))
        return handles.minByOrNull { (_, point) -> hypot((point.first - x).toDouble(), (point.second - y).toDouble()) }
            ?.takeIf { (_, point) -> hypot((point.first - x).toDouble(), (point.second - y).toDouble()) <= dp(24f) }
            ?.first
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    companion object { const val GESTURE_GUTTER_DP = 36f }
}
