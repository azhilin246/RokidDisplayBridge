package com.azhilin.rokid.displaybridge.companion

class ModeTransition(private val cleanup: Cleanup) {
    interface Cleanup {
        fun stopProducer()
        fun stopSamples()
        fun sendLiveStop(previous: BridgeMode)
        fun releaseSource(previous: BridgeMode)
        fun releaseEncoder()
        fun clearQueues()
        fun invalidateIdentity()
    }

    var mode: BridgeMode = BridgeMode.IDLE
        private set

    @Synchronized
    fun switchTo(next: BridgeMode, start: () -> Unit) {
        val previous = mode
        if (previous != BridgeMode.IDLE) {
            cleanup.stopProducer()
            cleanup.stopSamples()
            cleanup.sendLiveStop(previous)
            cleanup.releaseSource(previous)
            cleanup.releaseEncoder()
            cleanup.clearQueues()
            cleanup.invalidateIdentity()
        }
        mode = BridgeMode.IDLE
        if (next != BridgeMode.IDLE) {
            start()
            mode = next
        }
    }

    @Synchronized
    fun stop() = switchTo(BridgeMode.IDLE) {}
}
