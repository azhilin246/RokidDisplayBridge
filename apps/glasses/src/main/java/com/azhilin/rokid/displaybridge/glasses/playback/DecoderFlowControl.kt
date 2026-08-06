package com.azhilin.rokid.displaybridge.glasses.playback

internal enum class DecoderIngressDecision { ACCEPT, DROP, DROP_REQUEST_KEY_FRAME }

internal data class DecoderIngressStats(
    val pendingAccessUnits: Int,
    val waitingForKeyFrame: Boolean,
)

internal class DecoderIngressGate(private val maxPendingAccessUnits: Int) {
    private var pendingAccessUnits = 0
    private var waitingForKeyFrame = false

    init { require(maxPendingAccessUnits > 0) }

    @Synchronized
    fun beginStream() {
        waitingForKeyFrame = false
    }

    @Synchronized
    fun requireKeyFrame(): Boolean {
        val firstRequest = !waitingForKeyFrame
        waitingForKeyFrame = true
        return firstRequest
    }

    @Synchronized
    fun offer(keyFrame: Boolean): DecoderIngressDecision {
        if (waitingForKeyFrame) {
            if (!keyFrame) return DecoderIngressDecision.DROP
            if (pendingAccessUnits >= maxPendingAccessUnits) {
                return DecoderIngressDecision.DROP_REQUEST_KEY_FRAME
            }
            pendingAccessUnits += 1
            waitingForKeyFrame = false
            return DecoderIngressDecision.ACCEPT
        }
        if (pendingAccessUnits >= maxPendingAccessUnits) {
            waitingForKeyFrame = true
            return DecoderIngressDecision.DROP_REQUEST_KEY_FRAME
        }
        pendingAccessUnits += 1
        return DecoderIngressDecision.ACCEPT
    }

    @Synchronized
    fun complete() {
        if (pendingAccessUnits > 0) pendingAccessUnits -= 1
    }

    @Synchronized
    fun stats() = DecoderIngressStats(pendingAccessUnits, waitingForKeyFrame)
}

internal class DecoderStateGate(initial: LiveVideoDecoder.DecoderState) {
    private var state = initial

    @Synchronized
    fun transition(next: LiveVideoDecoder.DecoderState): Boolean {
        if (state == next) return false
        state = next
        return true
    }
}
