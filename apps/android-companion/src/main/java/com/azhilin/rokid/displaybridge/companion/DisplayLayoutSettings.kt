package com.azhilin.rokid.displaybridge.companion

import android.content.Context
import com.azhilin.rokid.displaybridge.protocol.DisplayLayoutMath
import com.azhilin.rokid.displaybridge.protocol.DisplayLayoutPayload
import kotlin.math.roundToInt

class DisplayLayoutStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): DisplayLayoutPayload = DisplayLayoutPayload(
        preferences.getInt(KEY_SCALE, 1_000).coerceIn(0, UShort.MAX_VALUE.toInt()).toUShort(),
        preferences.getInt(KEY_OFFSET_X, 0).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort(),
        preferences.getInt(KEY_OFFSET_Y, 0).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort(),
    ).clamped()

    fun save(value: DisplayLayoutPayload) {
        val effective = value.clamped()
        preferences.edit().putInt(KEY_SCALE, effective.scalePermille.toInt())
            .putInt(KEY_OFFSET_X, effective.offsetXPermille.toInt())
            .putInt(KEY_OFFSET_Y, effective.offsetYPermille.toInt()).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "display_bridge_layout_v1"
        const val KEY_SCALE = "scale_permille"
        const val KEY_OFFSET_X = "offset_x_permille"
        const val KEY_OFFSET_Y = "offset_y_permille"
    }
}

data class EditorFrameGeometry(val width: Double, val height: Double, val left: Double, val top: Double)
enum class FrameResizeHandle(val horizontalSign: Int, val verticalSign: Int) {
    TOP_LEFT(-1, -1), TOP_RIGHT(1, -1), BOTTOM_LEFT(-1, 1), BOTTOM_RIGHT(1, 1)
}

object DisplayLayoutEditorModel {
    const val VIEWPORT_WIDTH = 480.0
    const val VIEWPORT_HEIGHT = 640.0

    fun geometry(value: DisplayLayoutPayload): EditorFrameGeometry {
        val effective = value.clamped()
        val scale = effective.scalePermille.toInt() / 1_000.0
        val width = VIEWPORT_WIDTH * scale
        val height = VIEWPORT_HEIGHT * scale
        return EditorFrameGeometry(
            width, height,
            DisplayLayoutMath.horizontalPosition(VIEWPORT_WIDTH, width, effective.offsetXPermille.toInt()),
            DisplayLayoutMath.verticalPosition(VIEWPORT_HEIGHT, height, effective.offsetYPermille.toInt()),
        )
    }

    fun move(value: DisplayLayoutPayload, deltaX: Double, deltaY: Double): DisplayLayoutPayload {
        val current = geometry(value)
        return value.copy(
            offsetXPermille = DisplayLayoutMath.horizontalOffsetFromPosition(
                VIEWPORT_WIDTH,
                current.width,
                current.left + deltaX,
            ).toShort(),
            offsetYPermille = DisplayLayoutMath.verticalOffsetFromPosition(
                VIEWPORT_HEIGHT,
                current.height,
                current.top + deltaY,
            ).toShort(),
        ).clamped()
    }

    fun resize(
        value: DisplayLayoutPayload,
        handle: FrameResizeHandle,
        deltaX: Double,
        deltaY: Double,
    ): DisplayLayoutPayload {
        val effective = value.clamped()
        val current = geometry(effective)
        val relativeDelta = (handle.horizontalSign * deltaX / current.width +
            handle.verticalSign * deltaY / current.height) / 2.0
        val scale = (effective.scalePermille.toInt() * (1.0 + relativeDelta)).roundToInt()
            .coerceIn(DisplayLayoutPayload.MIN_SCALE_PERMILLE, DisplayLayoutPayload.MAX_SCALE_PERMILLE)
        val resized = geometry(effective.copy(scalePermille = scale.toUShort()))
        val centerX = current.left + current.width / 2.0
        val centerY = current.top + current.height / 2.0
        return effective.copy(
            scalePermille = scale.toUShort(),
            offsetXPermille = DisplayLayoutMath.horizontalOffsetFromPosition(
                VIEWPORT_WIDTH,
                resized.width,
                centerX - resized.width / 2.0,
            ).toShort(),
            offsetYPermille = DisplayLayoutMath.verticalOffsetFromPosition(
                VIEWPORT_HEIGHT,
                resized.height,
                centerY - resized.height / 2.0,
            ).toShort(),
        ).clamped()
    }

    fun reset() = DisplayLayoutPayload()
}
