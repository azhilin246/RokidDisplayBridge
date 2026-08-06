package com.azhilin.rokid.displaybridge.glasses

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.azhilin.rokid.displaybridge.glasses.ui.OverlayControl
import com.azhilin.rokid.displaybridge.glasses.ui.PlaybackOverlayModel
import com.azhilin.rokid.displaybridge.protocol.DisplayLayoutPayload
import com.google.android.material.card.MaterialCardView
import kotlin.math.roundToInt

internal class GlassesUi(
    private val activity: Activity,
    private val onControlClicked: (OverlayControl) -> Unit,
    private val onControlFocused: (OverlayControl) -> Unit,
) {
    private val context: Context = ContextThemeWrapper(activity, R.style.AppTheme)
    val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
    val textureView = TextureView(context).apply { isOpaque = true }

    private val appTitle = TextView(context).apply {
        text = activity.getString(R.string.app_name)
        gravity = Gravity.CENTER
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(GLASSES_PRIMARY)
    }
    private val stateTitle = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 28f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(GLASSES_ON_SURFACE)
    }
    private val stateDetail = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 17f
        setTextColor(GLASSES_ON_SURFACE_VARIANT)
        setLineSpacing(0f, 1.08f)
    }
    private val gestureHint = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 13f
        letterSpacing = .04f
        setTextColor(GLASSES_PRIMARY)
    }
    private val stateCard = MaterialCardView(context).apply {
        radius = dp(28).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(Color.BLACK)
        strokeWidth = 0
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(26), dp(28), dp(24))
            addView(appTitle, LinearLayout.LayoutParams(-1, -2))
            addView(stateTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(26) })
            addView(stateDetail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(View(context).apply { setBackgroundColor(0x334A6551) }, LinearLayout.LayoutParams(-1, dp(1)).apply {
                topMargin = dp(20); bottomMargin = dp(16)
            })
            addView(gestureHint, LinearLayout.LayoutParams(-1, -2))
        })
    }
    private val stateLayer = FrameLayout(context).apply {
        setBackgroundColor(Color.BLACK)
        addView(stateCard, FrameLayout.LayoutParams(minOf(dp(410), 424), -2, Gravity.CENTER))
    }

    private val controls = linkedMapOf<OverlayControl, ImageButton>()
    private val controlBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }
    private val controlOverlay = MaterialCardView(context).apply {
        radius = dp(28).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(0xD9000000.toInt())
        strokeColor = 0x995B7160.toInt()
        strokeWidth = dp(1)
        visibility = View.GONE
        addView(controlBar)
    }
    private val exitConfirmationText = TextView(context).apply {
        setTextColor(GLASSES_ON_SURFACE)
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(28), dp(20), dp(28), dp(20))
        text = activity.getString(R.string.double_tap_again_to_exit)
    }
    private val exitConfirmation = MaterialCardView(context).apply {
        radius = dp(24).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(Color.BLACK)
        strokeColor = GLASSES_PRIMARY
        strokeWidth = dp(2)
        visibility = View.GONE
        addView(exitConfirmationText)
    }
    private var displayLayout = DisplayLayoutPayload()

    init {
        root.addView(textureView, FrameLayout.LayoutParams(480, 640, Gravity.TOP or Gravity.START))
        root.addView(stateLayer, FrameLayout.LayoutParams(-1, -1))
        buildControls()
        root.addView(controlOverlay, FrameLayout.LayoutParams(minOf(dp(452), 456), -2, Gravity.CENTER))
        root.addView(exitConfirmation, FrameLayout.LayoutParams(minOf(dp(410), 424), -2, Gravity.CENTER))
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyLayoutNow() }
        render(GlassesState.WAITING_FOR_BRIDGE)
    }

    fun render(state: GlassesState, detail: String? = null) {
        val value = GlassesStatePresentation.forState(state, detail)
        root.keepScreenOn = state == GlassesState.PLAYING
        // Keep the TextureView attached even while the opaque black state layer is shown.
        // A GONE TextureView has no SurfaceTexture, while LIVE_START used to wait for that
        // surface before acknowledging the session. That creates a startup deadlock: the
        // phone cannot create its source display until the glasses acknowledge LIVE_START,
        // and the glasses cannot expose the surface until video starts. The state layer still
        // completely hides the dormant TextureView outside playback.
        textureView.visibility = View.VISIBLE
        if (state == GlassesState.PLAYING) {
            stateLayer.visibility = View.GONE
        } else {
            controlOverlay.visibility = View.GONE
            stateLayer.visibility = View.VISIBLE
            stateTitle.text = value.title
            stateDetail.text = value.detail
            gestureHint.text = hintFor(state)
        }
    }

    fun beginWakePulse() {
        activity.setShowWhenLocked(true)
        activity.setTurnScreenOn(true)
        activity.window.addFlags(
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
        )
        root.keepScreenOn = true
    }

    fun endWakePulse(playing: Boolean) {
        activity.setTurnScreenOn(false)
        activity.setShowWhenLocked(false)
        activity.window.clearFlags(
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
        )
        if (!playing) root.keepScreenOn = false
    }

    fun applyDisplayLayout(value: DisplayLayoutPayload) {
        displayLayout = value.clamped()
        root.post(::applyLayoutNow)
    }

    fun setOverlayVisible(visible: Boolean) {
        controlOverlay.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) controlOverlay.clearFocus()
    }

    fun requestOverlayFocus(control: OverlayControl) {
        controls.getValue(control).requestFocus()
    }

    fun performControl(control: OverlayControl): Boolean = controls.getValue(control).performClick()

    fun setExitConfirmationVisible(visible: Boolean, message: String? = null) {
        if (message != null) exitConfirmationText.text = message
        exitConfirmation.visibility = if (visible) View.VISIBLE else View.GONE
    }

    internal fun isExitConfirmationVisible(): Boolean = exitConfirmation.visibility == View.VISIBLE
    internal fun isStateSurfaceVisible(): Boolean = stateLayer.visibility == View.VISIBLE
    internal fun isPlaybackOverlayTranslucent(): Boolean = controlOverlay.cardBackgroundColor.defaultColor ushr 24 < 0xFF
    internal fun appTitleText(): String = appTitle.text.toString()
    internal fun stateTitleText(): String = stateTitle.text.toString()
    internal fun stateSurfaceIsPureBlack(): Boolean =
        stateLayer.background is android.graphics.drawable.ColorDrawable &&
            (stateLayer.background as android.graphics.drawable.ColorDrawable).color == Color.BLACK &&
            stateCard.cardBackgroundColor.defaultColor == Color.BLACK

    private fun applyLayoutNow() {
        if (root.width <= 0 || root.height <= 0) return
        val geometry = DisplayLayoutModel.geometry(displayLayout)
        val xScale = root.width / DisplayLayoutModel.VIEWPORT_WIDTH.toFloat()
        val yScale = root.height / DisplayLayoutModel.VIEWPORT_HEIGHT.toFloat()
        val next = FrameLayout.LayoutParams(
            (geometry.width * xScale).roundToInt(),
            (geometry.height * yScale).roundToInt(),
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = (geometry.left * xScale).roundToInt()
            topMargin = (geometry.top * yScale).roundToInt()
        }
        val current = textureView.layoutParams as? FrameLayout.LayoutParams
        if (current?.width != next.width || current?.height != next.height ||
            current?.leftMargin != next.leftMargin || current?.topMargin != next.topMargin
        ) textureView.layoutParams = next
    }

    private fun buildControls() {
        PlaybackOverlayModel.ORDER.forEach { control ->
            controlBar.addView(controlButton(control), LinearLayout.LayoutParams(0, dp(72), 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            })
        }
    }

    private fun controlButton(control: OverlayControl) = ImageButton(context).apply {
        setImageDrawable(MediaIconDrawable(control))
        contentDescription = when (control) {
            OverlayControl.PLAY_PAUSE -> "Play or pause"
            OverlayControl.SEEK_BACKWARD -> "Seek backward"
            OverlayControl.SEEK_FORWARD -> "Seek forward"
            OverlayControl.EXIT -> "Exit current bridge mode"
        }
        isFocusable = true
        isFocusableInTouchMode = true
        background = focusBackground()
        setPadding(dp(17), dp(15), dp(17), dp(15))
        setOnClickListener { onControlClicked(control) }
        setOnFocusChangeListener { _, focused -> if (focused) onControlFocused(control) }
        controls[control] = this
    }

    private fun focusBackground(): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), roundedBox(GLASSES_PRIMARY_CONTAINER, GLASSES_PRIMARY, dp(3), 20))
        addState(intArrayOf(android.R.attr.state_pressed), roundedBox(0xCC1F3425.toInt(), GLASSES_PRIMARY, dp(2), 20))
        addState(intArrayOf(), roundedBox(0x99000000.toInt(), 0x805B7160.toInt(), dp(1), 20))
    }

    private fun roundedBox(fill: Int, stroke: Int, strokeWidth: Int, radiusDp: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(fill)
        if (strokeWidth > 0) setStroke(strokeWidth, stroke)
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    internal companion object {
        private const val GLASSES_PRIMARY = 0xFF78F29A.toInt()
        private const val GLASSES_PRIMARY_CONTAINER = 0xFF0D4F28.toInt()
        private const val GLASSES_ON_SURFACE = 0xFFE4F1E7.toInt()
        private const val GLASSES_ON_SURFACE_VARIANT = 0xFFAFC2B3.toInt()

        internal fun hintFor(state: GlassesState) = when (state) {
            GlassesState.READY -> "SELECT A MODE ON YOUR PHONE"
            GlassesState.WAITING_FOR_VIDEO -> "VIDEO WILL APPEAR AUTOMATICALLY"
            GlassesState.LOADING -> "VIDEO WILL APPEAR AUTOMATICALLY"
            GlassesState.ERROR -> "CHECK COMPANION DIAGNOSTICS"
            else -> "DOUBLE TAP TWICE TO EXIT"
        }
    }
}

private class MediaIconDrawable(private val control: OverlayControl) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun draw(canvas: Canvas) {
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        when (control) {
            OverlayControl.PLAY_PAUSE -> {
                triangle(canvas, width * .12f, height * .16f, width * .53f, height * .50f, width * .12f, height * .84f)
                canvas.drawRoundRect(width * .62f, height * .18f, width * .72f, height * .82f, 3f, 3f, paint)
                canvas.drawRoundRect(width * .79f, height * .18f, width * .89f, height * .82f, 3f, 3f, paint)
            }
            OverlayControl.SEEK_BACKWARD -> {
                triangle(canvas, width * .50f, height * .18f, width * .12f, height * .50f, width * .50f, height * .82f)
                triangle(canvas, width * .86f, height * .18f, width * .48f, height * .50f, width * .86f, height * .82f)
            }
            OverlayControl.SEEK_FORWARD -> {
                triangle(canvas, width * .14f, height * .18f, width * .52f, height * .50f, width * .14f, height * .82f)
                triangle(canvas, width * .50f, height * .18f, width * .88f, height * .50f, width * .50f, height * .82f)
            }
            OverlayControl.EXIT -> {
                canvas.drawRoundRect(width * .17f, height * .16f, width * .60f, height * .84f, 2f, 2f, strokePaint)
                canvas.drawLine(width * .42f, height * .50f, width * .88f, height * .50f, strokePaint)
                canvas.drawLine(width * .70f, height * .32f, width * .88f, height * .50f, strokePaint)
                canvas.drawLine(width * .70f, height * .68f, width * .88f, height * .50f, strokePaint)
            }
        }
        canvas.restore()
    }

    private fun triangle(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        canvas.drawPath(Path().apply { moveTo(x1, y1); lineTo(x2, y2); lineTo(x3, y3); close() }, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; strokePaint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        invalidateSelf()
    }
    @Deprecated("Deprecated in Android") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = 64
    override fun getIntrinsicHeight(): Int = 48
}
