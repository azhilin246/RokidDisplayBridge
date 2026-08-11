package com.azhilin.rokid.displaybridge.protocol

object WirelessRecoveryPolicy {
    private val retryDelaysMs = longArrayOf(1_500L, 3_000L, 5_000L, 8_000L)

    fun delayMs(attempt: Int): Long {
        require(attempt >= 0) { "attempt must be non-negative" }
        return retryDelaysMs[minOf(attempt, retryDelaysMs.lastIndex)]
    }
}
