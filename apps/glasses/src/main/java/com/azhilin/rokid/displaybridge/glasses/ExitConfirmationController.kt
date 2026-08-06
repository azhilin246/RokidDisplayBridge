package com.azhilin.rokid.displaybridge.glasses

internal enum class ExitConfirmationAction { ARM, CONFIRM_EXIT }

internal class ExitConfirmationController(
    private val timeoutMs: Long = 3_000L,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var armedUntilMs: Long? = null

    init { require(timeoutMs > 0L) { "timeoutMs must be positive" } }

    fun onDoubleTap(nowMs: Long = clockMs()): ExitConfirmationAction =
        if (armedUntilMs?.let { nowMs < it } == true) {
            armedUntilMs = null
            ExitConfirmationAction.CONFIRM_EXIT
        } else {
            armedUntilMs = nowMs.saturatingAdd(timeoutMs)
            ExitConfirmationAction.ARM
        }

    fun disarm() { armedUntilMs = null }
}

private fun Long.saturatingAdd(other: Long): Long =
    if (this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other
