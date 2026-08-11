package com.azhilin.rokid.displaybridge.glasses.input

import com.azhilin.rokid.displaybridge.protocol.GestureSettingsPayload
import com.azhilin.rokid.displaybridge.protocol.MediaCommand
import com.azhilin.rokid.displaybridge.protocol.SwipeGestureAction
import com.azhilin.rokid.displaybridge.protocol.TapGestureAction

internal enum class InputMode { MAIN, PLAYER_HIDDEN, OVERLAY_VISIBLE }
internal enum class PlayerInput { SINGLE_TAP, DOUBLE_TAP, PREVIOUS, NEXT }
internal enum class FocusMove { PREVIOUS, NEXT }

internal sealed interface PlayerInputAction {
    data object None : PlayerInputAction
    data class SendMediaCommand(val command: MediaCommand) : PlayerInputAction
    data object ShowOverlay : PlayerInputAction
    data object HideOverlay : PlayerInputAction
    data object ActivateFocused : PlayerInputAction
    data class MoveFocus(val direction: FocusMove) : PlayerInputAction
    data class ExitApplication(val requireConfirmation: Boolean) : PlayerInputAction
}

internal object PlayerInputRouter {
    fun route(
        mode: InputMode,
        input: PlayerInput,
        settings: GestureSettingsPayload = GestureSettingsPayload(),
    ): PlayerInputAction {
        if (input == PlayerInput.DOUBLE_TAP) return PlayerInputAction.ExitApplication(false)
        return when (mode) {
        InputMode.MAIN -> when (input) {
            PlayerInput.DOUBLE_TAP -> PlayerInputAction.ExitApplication(false)
            else -> PlayerInputAction.None
        }
        InputMode.PLAYER_HIDDEN -> when (input) {
            PlayerInput.SINGLE_TAP -> when (settings.tapAction) {
                TapGestureAction.NONE -> PlayerInputAction.None
                TapGestureAction.PLAY_PAUSE -> PlayerInputAction.SendMediaCommand(MediaCommand.PLAY_PAUSE)
                TapGestureAction.SHOW_OVERLAY -> PlayerInputAction.ShowOverlay
            }
            PlayerInput.DOUBLE_TAP -> PlayerInputAction.ExitApplication(false)
            PlayerInput.PREVIOUS, PlayerInput.NEXT -> swipeAction(input, settings)
        }
        InputMode.OVERLAY_VISIBLE -> when (input) {
            PlayerInput.SINGLE_TAP -> PlayerInputAction.ActivateFocused
            PlayerInput.DOUBLE_TAP -> PlayerInputAction.ExitApplication(false)
            PlayerInput.PREVIOUS -> PlayerInputAction.MoveFocus(FocusMove.PREVIOUS)
            PlayerInput.NEXT -> PlayerInputAction.MoveFocus(FocusMove.NEXT)
        }
        }
    }

    private fun swipeAction(input: PlayerInput, settings: GestureSettingsPayload): PlayerInputAction {
        val previous = (input == PlayerInput.PREVIOUS) xor settings.reverseSwipeDirections
        val command = when (settings.swipeAction) {
            SwipeGestureAction.NONE -> return PlayerInputAction.None
            SwipeGestureAction.SEEK -> if (previous) MediaCommand.SEEK_BACKWARD else MediaCommand.SEEK_FORWARD
            SwipeGestureAction.PLAYLIST -> if (previous) MediaCommand.PLAYLIST_PREVIOUS else MediaCommand.PLAYLIST_NEXT
            SwipeGestureAction.FAVORITES -> if (previous) MediaCommand.FAVORITE_PREVIOUS else MediaCommand.FAVORITE_NEXT
        }
        return PlayerInputAction.SendMediaCommand(command)
    }
}

internal class SwipeActionGate(private val quietGapMs: Long = 350L) {
    private var lastDirectionEventAtMs = Long.MIN_VALUE

    fun accept(eventTimeMs: Long): Boolean {
        val elapsed = eventTimeMs - lastDirectionEventAtMs
        val accepted = lastDirectionEventAtMs == Long.MIN_VALUE || elapsed > quietGapMs
        // Rejected repeats extend the latch, so one continuous firmware stream can
        // never re-arm merely because the finger remains on the temple/ring surface.
        lastDirectionEventAtMs = eventTimeMs
        return accepted
    }

    fun reset() {
        lastDirectionEventAtMs = Long.MIN_VALUE
    }
}
