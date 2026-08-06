package com.azhilin.rokid.displaybridge.companion.media

data class BitrateAdjustment(val bitrate: Int, val reason: String)

class AdaptiveVideoBitrate(
    initialBitrate: Int = SurfaceToAvcStream.BITRATE,
    private val minBitrate: Int = MIN_BITRATE,
    private val maxBitrate: Int = MAX_BITRATE,
) {
    var currentBitrate: Int = initialBitrate.coerceIn(minBitrate, maxBitrate)
        private set
    private var lastAdjustmentMs: Long? = null
    private var lastRemoteDroppedFrames = 0L
    private var consecutiveHighQueueFeedback = 0

    init { require(minBitrate in 100_000 until maxBitrate) }

    @Synchronized
    fun onQueuePressure(nowMs: Long): BitrateAdjustment? = reduce(nowMs, "phone USB queue pressure")

    @Synchronized
    fun onDecoderFeedback(queueDepthMs: Int, droppedFrames: Long, nowMs: Long): BitrateAdjustment? {
        val newlyDropped = droppedFrames > lastRemoteDroppedFrames
        lastRemoteDroppedFrames = maxOf(lastRemoteDroppedFrames, droppedFrames)
        consecutiveHighQueueFeedback = if (queueDepthMs > REDUCE_DEPTH_MS) {
            consecutiveHighQueueFeedback + 1
        } else {
            0
        }
        if (consecutiveHighQueueFeedback >= HIGH_QUEUE_FEEDBACK_REQUIRED) {
            consecutiveHighQueueFeedback = 0
            return reduce(nowMs, if (newlyDropped) {
                "sustained glasses queue ${queueDepthMs}ms with decoder drops"
            } else {
                "sustained glasses queue ${queueDepthMs}ms"
            })
        }
        val last = lastAdjustmentMs
        if (last == null) {
            lastAdjustmentMs = nowMs
            return null
        }
        if (queueDepthMs <= INCREASE_DEPTH_MS && nowMs - last >= INCREASE_COOLDOWN_MS &&
            currentBitrate < maxBitrate
        ) {
            currentBitrate = (currentBitrate + INCREASE_STEP).coerceAtMost(maxBitrate)
            lastAdjustmentMs = nowMs
            return BitrateAdjustment(currentBitrate, "stable low decoder queue")
        }
        return null
    }

    @Synchronized
    fun resetRemoteDrops() {
        lastRemoteDroppedFrames = 0L
        consecutiveHighQueueFeedback = 0
    }

    private fun reduce(nowMs: Long, reason: String): BitrateAdjustment? {
        val last = lastAdjustmentMs
        if (last != null && nowMs - last < REDUCE_COOLDOWN_MS) return null
        val reduced = (currentBitrate * 85 / 100).coerceAtLeast(minBitrate)
        if (reduced == currentBitrate) return null
        currentBitrate = reduced
        lastAdjustmentMs = nowMs
        return BitrateAdjustment(currentBitrate, reason)
    }

    companion object {
        const val MIN_BITRATE = 800_000
        const val MAX_BITRATE = 1_600_000
        private const val REDUCE_DEPTH_MS = 300
        private const val INCREASE_DEPTH_MS = 67
        private const val HIGH_QUEUE_FEEDBACK_REQUIRED = 2
        private const val REDUCE_COOLDOWN_MS = 3_000L
        private const val INCREASE_COOLDOWN_MS = 8_000L
        private const val INCREASE_STEP = 100_000
    }
}
