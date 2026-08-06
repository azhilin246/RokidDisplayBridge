package com.azhilin.rokid.displaybridge.glasses.ui

import com.azhilin.rokid.displaybridge.protocol.MediaCommand

internal enum class OverlayControl(val mediaCommand: MediaCommand?) {
    PLAY_PAUSE(MediaCommand.PLAY_PAUSE),
    SEEK_BACKWARD(MediaCommand.SEEK_BACKWARD),
    SEEK_FORWARD(MediaCommand.SEEK_FORWARD),
    EXIT(null),
}

internal class PlaybackOverlayModel {
    var isVisible: Boolean = false
        private set
    var focused: OverlayControl = OverlayControl.PLAY_PAUSE
        private set

    fun show() {
        isVisible = true
        focused = OverlayControl.PLAY_PAUSE
    }

    fun hide() { isVisible = false }

    fun focus(control: OverlayControl) {
        if (isVisible) focused = control
    }

    fun previous(): OverlayControl = move(-1)
    fun next(): OverlayControl = move(1)

    private fun move(delta: Int): OverlayControl {
        if (!isVisible) return focused
        val order = ORDER
        focused = order[Math.floorMod(order.indexOf(focused) + delta, order.size)]
        return focused
    }

    companion object {
        val ORDER = listOf(
            OverlayControl.PLAY_PAUSE,
            OverlayControl.SEEK_BACKWARD,
            OverlayControl.SEEK_FORWARD,
            OverlayControl.EXIT,
        )
    }
}
