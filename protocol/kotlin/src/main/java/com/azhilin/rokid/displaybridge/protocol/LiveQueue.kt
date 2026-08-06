package com.azhilin.rokid.displaybridge.protocol

import java.util.ArrayDeque

enum class FramePriority { CONTROL, VIDEO }
data class QueuedFrame(val frames: List<Frame>, val priority: FramePriority, val keyFrame: Boolean = false) {
    init { require(frames.isNotEmpty()) }
}
data class QueueOfferResult(val accepted: Boolean, val keyFrameRequired: Boolean = false)
data class QueueStats(
    val controlDepth: Int,
    val videoDepth: Int,
    val droppedVideoFrames: Long,
    val waitingForKeyFrame: Boolean = false,
)

class LiveFrameQueue(private val maxControlFrames: Int = 32, private val maxVideoFrames: Int = DEFAULT_MAX_VIDEO_FRAMES) {
    private val control = ArrayDeque<QueuedFrame>()
    private val video = ArrayDeque<QueuedFrame>()
    private var dropped = 0L
    private var waitingForKeyFrame = false

    init { require(maxControlFrames > 0 && maxVideoFrames > 0) }

    @Synchronized
    fun offer(value: QueuedFrame): QueueOfferResult {
        val result = when (value.priority) {
            FramePriority.CONTROL -> QueueOfferResult(
                accepted = if (control.size >= maxControlFrames) false else control.add(value),
            )
            FramePriority.VIDEO -> offerVideo(value)
        }
        if (result.accepted) (this as java.lang.Object).notifyAll()
        return result
    }

    @Synchronized fun poll(): QueuedFrame? = control.pollFirst() ?: video.pollFirst()

    @Synchronized
    fun take(running: () -> Boolean): QueuedFrame? {
        while (running()) {
            poll()?.let { return it }
            (this as java.lang.Object).wait(250L)
        }
        return null
    }

    @Synchronized fun clear() {
        control.clear(); video.clear(); waitingForKeyFrame = false
        (this as java.lang.Object).notifyAll()
    }
    @Synchronized fun clearVideo() {
        video.clear(); waitingForKeyFrame = false
        (this as java.lang.Object).notifyAll()
    }
    @Synchronized fun wake() = (this as java.lang.Object).notifyAll()
    @Synchronized fun stats() = QueueStats(control.size, video.size, dropped, waitingForKeyFrame)

    private fun offerVideo(value: QueuedFrame): QueueOfferResult {
        if (waitingForKeyFrame) {
            if (!value.keyFrame) {
                dropped += 1
                return QueueOfferResult(accepted = false)
            }
            dropped += video.size.toLong()
            video.clear()
            video.addLast(value)
            waitingForKeyFrame = false
            return QueueOfferResult(accepted = true)
        }
        if (video.size < maxVideoFrames) {
            video.addLast(value)
            return QueueOfferResult(accepted = true)
        }
        if (value.keyFrame) {
            dropped += video.size.toLong()
            video.clear()
            video.addLast(value)
            return QueueOfferResult(accepted = true)
        }
        dropped += video.size.toLong() + 1L
        video.clear()
        waitingForKeyFrame = true
        return QueueOfferResult(accepted = false, keyFrameRequired = true)
    }

    companion object {
        const val DEFAULT_MAX_VIDEO_FRAMES = 8
    }
}
