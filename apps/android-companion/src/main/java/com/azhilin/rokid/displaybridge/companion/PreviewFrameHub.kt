package com.azhilin.rokid.displaybridge.companion

import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class PreviewFrame(
    val encodedAccessUnit: ByteArray,
    val presentationTimeUs: Long,
    val sessionId: ULong,
    val generation: UInt,
)

data class PreviewCodecConfig(
    val sessionId: ULong,
    val generation: UInt,
    val width: Int,
    val height: Int,
    val csd0: ByteArray,
    val csd1: ByteArray,
)

sealed interface PreviewUpdate {
    data class CodecConfig(val value: PreviewCodecConfig) : PreviewUpdate
    data class AccessUnit(val value: PreviewFrame) : PreviewUpdate
}

class LatestPreviewFrameHub(
    private val minimumIntervalMs: Long = 1_000L,
    private val executor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RdbFramePreview").apply { isDaemon = true }
    },
) {
    private val enabled = AtomicBoolean(false)
    private val config = AtomicReference<PreviewCodecConfig?>(null)
    private val configPending = AtomicBoolean(false)
    private val pending = AtomicReference<PreviewFrame?>(null)
    private val subscriber = AtomicReference<((PreviewUpdate) -> Unit)?>(null)
    private val deliveryScheduled = AtomicBoolean(false)
    private val lastAcceptedAtMs = AtomicLong(Long.MIN_VALUE)

    val isEnabled: Boolean get() = enabled.get()
    val hasPendingFrame: Boolean get() = pending.get() != null

    fun setEnabled(value: Boolean) {
        enabled.set(value)
        if (!value) clear()
    }

    fun offer(
        encodedAccessUnit: ByteArray,
        presentationTimeUs: Long,
        sessionId: ULong,
        generation: UInt,
        nowMs: Long,
    ): Boolean {
        if (!enabled.get() || encodedAccessUnit.isEmpty() || sessionId == 0uL) return false
        val currentConfig = config.get() ?: return false
        if (currentConfig.sessionId != sessionId || currentConfig.generation != generation) return false
        if (!acceptTimestamp(nowMs)) return false
        pending.set(PreviewFrame(encodedAccessUnit.copyOf(), presentationTimeUs, sessionId, generation))
        scheduleDeliveryIfNeeded()
        return true
    }

    fun publishConfig(value: PreviewCodecConfig): Boolean {
        if (!enabled.get() || value.sessionId == 0uL || value.width !in 1..MAX_DIMENSION ||
            value.height !in 1..MAX_DIMENSION || value.csd0.size > MAX_CSD_BYTES || value.csd1.size > MAX_CSD_BYTES
        ) return false
        config.set(value.defensiveCopy())
        configPending.set(true)
        pending.set(null)
        lastAcceptedAtMs.set(Long.MIN_VALUE)
        scheduleDeliveryIfNeeded()
        return true
    }

    fun takeLatest(): PreviewFrame? = pending.getAndSet(null)?.defensiveCopy()

    fun subscribe(listener: (PreviewUpdate) -> Unit): Closeable {
        subscriber.set(listener)
        if (config.get() != null) configPending.set(true)
        scheduleDeliveryIfNeeded()
        return Closeable {
            subscriber.compareAndSet(listener, null)
            clear()
        }
    }

    fun clear() {
        config.set(null)
        configPending.set(false)
        pending.set(null)
        lastAcceptedAtMs.set(Long.MIN_VALUE)
    }

    private fun acceptTimestamp(nowMs: Long): Boolean {
        while (true) {
            val previous = lastAcceptedAtMs.get()
            if (previous != Long.MIN_VALUE && nowMs >= previous && nowMs - previous < minimumIntervalMs) return false
            if (lastAcceptedAtMs.compareAndSet(previous, nowMs)) return true
        }
    }

    private fun scheduleDeliveryIfNeeded() {
        if (subscriber.get() == null || (!configPending.get() && pending.get() == null) ||
            !deliveryScheduled.compareAndSet(false, true)) return
        executor.execute {
            try {
                while (enabled.get()) {
                    val listener = subscriber.get() ?: break
                    if (configPending.compareAndSet(true, false)) {
                        config.get()?.let { listener(PreviewUpdate.CodecConfig(it.defensiveCopy())) }
                        continue
                    }
                    val frame = pending.getAndSet(null) ?: break
                    listener(PreviewUpdate.AccessUnit(frame.defensiveCopy()))
                }
            } finally {
                deliveryScheduled.set(false)
                if (enabled.get() && subscriber.get() != null && (configPending.get() || pending.get() != null)) {
                    scheduleDeliveryIfNeeded()
                }
            }
        }
    }

    private fun PreviewFrame.defensiveCopy() = copy(encodedAccessUnit = encodedAccessUnit.copyOf())

    private fun PreviewCodecConfig.defensiveCopy() = copy(csd0 = csd0.copyOf(), csd1 = csd1.copyOf())

    companion object {
        const val MAX_DIMENSION = 640
        const val MAX_CSD_BYTES = 16 * 1024
    }
}

object BridgePreviewFrames {
    private val hub = LatestPreviewFrameHub()
    private val activationListener = AtomicReference<((Boolean) -> Unit)?>(null)

    val isEnabled: Boolean get() = hub.isEnabled
    fun setEnabled(value: Boolean) {
        val changed = hub.isEnabled != value
        hub.setEnabled(value)
        if (changed) activationListener.get()?.invoke(value)
    }
    fun setActivationListener(listener: ((Boolean) -> Unit)?) { activationListener.set(listener) }
    fun publishConfig(value: PreviewCodecConfig): Boolean = hub.publishConfig(value)
    fun offer(bytes: ByteArray, presentationTimeUs: Long, sessionId: ULong, generation: UInt, nowMs: Long): Boolean =
        hub.offer(bytes, presentationTimeUs, sessionId, generation, nowMs)
    fun takeLatest(): PreviewFrame? = hub.takeLatest()
    fun subscribe(listener: (PreviewUpdate) -> Unit): Closeable = hub.subscribe(listener)
    fun clear() = hub.clear()
}
