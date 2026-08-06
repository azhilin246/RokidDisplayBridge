package com.azhilin.rokid.displaybridge.companion

import android.content.Context
import android.graphics.RectF
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt

class FramePlacementStage(context: Context) : FrameLayout(context) {
    private val previewClip = FrameLayout(context).apply {
        clipChildren = true
        clipToPadding = true
        visibility = View.INVISIBLE
    }
    val previewView = TextureView(context).apply {
        id = R.id.frame_preview_surface
        visibility = View.INVISIBLE
        isOpaque = true
        contentDescription = "Low-rate content preview"
    }
    val editor = DisplayLayoutEditorView(context).apply { id = R.id.frame_editor }

    init {
        id = R.id.frame_stage
        setBackgroundColor(MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorSurfaceContainerHigh,
            0xFFE8E5ED.toInt(),
        ))
        clipChildren = true
        previewClip.addView(previewView)
        addView(previewClip)
        addView(editor, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        editor.onViewportChanged = { syncPreviewPlacement(it) }
    }

    fun setPreviewEnabled(enabled: Boolean) {
        previewClip.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
        previewView.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
    }

    fun previewBounds(): RectF = editor.viewportBounds()

    fun syncPreviewPlacement() = syncPreviewPlacement(editor.viewportBounds())

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        syncPreviewPlacement(editor.viewportBounds())
    }

    private fun syncPreviewPlacement(viewport: RectF) {
        previewClip.layout(
            viewport.left.roundToInt(),
            viewport.top.roundToInt(),
            viewport.right.roundToInt(),
            viewport.bottom.roundToInt(),
        )
        val frame = editor.frameBounds()
        previewView.layout(
            (frame.left - viewport.left).roundToInt(),
            (frame.top - viewport.top).roundToInt(),
            (frame.right - viewport.left).roundToInt(),
            (frame.bottom - viewport.top).roundToInt(),
        )
    }
}
