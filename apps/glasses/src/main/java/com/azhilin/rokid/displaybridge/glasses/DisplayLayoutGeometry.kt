package com.azhilin.rokid.displaybridge.glasses

import com.azhilin.rokid.displaybridge.protocol.DisplayLayoutMath
import com.azhilin.rokid.displaybridge.protocol.DisplayLayoutPayload
import kotlin.math.roundToInt

data class DisplayLayoutGeometry(val width: Int, val height: Int, val left: Int, val top: Int)

object DisplayLayoutModel {
    const val VIEWPORT_WIDTH = 480
    const val VIEWPORT_HEIGHT = 640

    fun geometry(layout: DisplayLayoutPayload): DisplayLayoutGeometry {
        val value = layout.clamped()
        val width = (VIEWPORT_WIDTH * value.scalePermille.toInt() / 1_000.0).roundToInt()
        val height = (VIEWPORT_HEIGHT * value.scalePermille.toInt() / 1_000.0).roundToInt()
        return DisplayLayoutGeometry(
            width,
            height,
            DisplayLayoutMath.horizontalPosition(
                VIEWPORT_WIDTH.toDouble(),
                width.toDouble(),
                value.offsetXPermille.toInt(),
            ).roundToInt(),
            DisplayLayoutMath.verticalPosition(
                VIEWPORT_HEIGHT.toDouble(),
                height.toDouble(),
                value.offsetYPermille.toInt(),
            ).roundToInt(),
        )
    }
}
