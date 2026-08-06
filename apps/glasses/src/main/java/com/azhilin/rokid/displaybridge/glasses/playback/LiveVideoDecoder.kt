package com.azhilin.rokid.displaybridge.glasses.playback

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.TextureView
import com.azhilin.rokid.displaybridge.protocol.AccessUnitReassembler
import com.azhilin.rokid.displaybridge.protocol.FeedbackFlags
import com.azhilin.rokid.displaybridge.protocol.LiveConfigPayload
import com.azhilin.rokid.displaybridge.protocol.LiveDiscontinuityPayload
import com.azhilin.rokid.displaybridge.protocol.LiveFeedbackPayload
import com.azhilin.rokid.displaybridge.protocol.LivePayloads
import com.azhilin.rokid.displaybridge.protocol.LiveReadyPayload
import com.azhilin.rokid.displaybridge.protocol.LiveSamplePayload
import com.azhilin.rokid.displaybridge.protocol.LiveStartPayload
import com.azhilin.rokid.displaybridge.protocol.LiveStopPayload
import com.azhilin.rokid.displaybridge.protocol.PacketDisposition
import com.azhilin.rokid.displaybridge.protocol.PayloadException
import com.azhilin.rokid.displaybridge.protocol.ReassemblyResult
import com.azhilin.rokid.displaybridge.protocol.SampleFlags
import com.azhilin.rokid.displaybridge.protocol.SessionGate
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class LiveVideoDecoder(
    textureView: TextureView,
    private val listener: Listener,
) : TextureView.SurfaceTextureListener, Closeable {
    interface Listener {
        fun onReady(payload: LiveReadyPayload)
        fun onFirstFrame(sessionId: ULong, generation: UInt)
        fun onFeedback(payload: LiveFeedbackPayload)
        fun onDiagnostic(sessionId: ULong, generation: UInt, detail: String)
        fun onState(state: DecoderState, detail: String? = null)
        fun onError(message: String)
    }

    enum class DecoderState { IDLE, LOADING, PLAYING, ERROR }

    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "rdb-live-decoder") }
    private val active = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val gate = SessionGate()
    private val reassembler = AccessUnitReassembler()
    private val ingress = DecoderIngressGate(MAX_QUEUED_ACCESS_UNITS)
    private val stateGate = DecoderStateGate(DecoderState.IDLE)
    private val pending = ArrayDeque<ReassemblyResult.Complete>()
    private val ingressEpoch = AtomicLong(0)
    private val terminalError = AtomicBoolean(false)
    private val firstFrameNotified = AtomicBoolean(false)
    private val readyAnnouncements = LiveReadyAnnouncementGate()
    private val decodedUnits = AtomicLong(0)
    private val droppedFrames = AtomicLong(0)
    private val lastQueuedPtsUs = AtomicLong(0)
    private val lastPresentedPtsUs = AtomicLong(0)
    @Volatile private var surface: Surface? = null
    @Volatile private var session: LiveStartPayload? = null
    @Volatile private var config: LiveConfigPayload? = null
    @Volatile private var decoder: MediaCodec? = null

    init {
        textureView.surfaceTextureListener = this
        if (textureView.isAvailable) surface = textureView.surfaceTexture?.let(::Surface)
    }

    fun start(value: LiveStartPayload) {
        stopInternal()
        session = value
        config = null
        decodedUnits.set(0); droppedFrames.set(0)
        lastQueuedPtsUs.set(0); lastPresentedPtsUs.set(0)
        gate.replace(value.sessionId, value.generation)
        reassembler.replaceSession(value.sessionId, value.generation)
        ingress.beginStream()
        ingressEpoch.incrementAndGet()
        terminalError.set(false)
        firstFrameNotified.set(false)
        readyAnnouncements.begin(value.sessionId, value.generation)
        active.set(true)
        announceReadyOnce()
    }

    fun configure(value: LiveConfigPayload) {
        if (gate.classify(value.sessionId, value.generation) == PacketDisposition.STALE) return
        publishState(DecoderState.LOADING, "Starting video decoder")
        submit {
            if (gate.classify(value.sessionId, value.generation) == PacketDisposition.STALE) return@submit
            config = value
            configureDecoder(value)
        }
    }

    fun accept(value: LiveSamplePayload) {
        if (gate.classify(value.sessionId, value.generation) == PacketDisposition.STALE) return
        try {
            when (val result = reassembler.accept(value, SystemClock.elapsedRealtime())) {
                ReassemblyResult.Pending, ReassemblyResult.Stale -> Unit
                is ReassemblyResult.Complete -> {
                    if (firstFrameNotified.compareAndSet(false, true)) {
                        listener.onFirstFrame(value.sessionId, value.generation)
                    }
                    lastQueuedPtsUs.accumulateAndGet(result.presentationTimeUs.toLong(), ::maxOf)
                    val keyFrame = result.flags.toUInt() and SampleFlags.KEY_FRAME.toUInt() != 0u
                    when (ingress.offer(keyFrame)) {
                        DecoderIngressDecision.ACCEPT -> {
                            val acceptedEpoch = ingressEpoch.get()
                            submit {
                                try {
                                    if (acceptedEpoch != ingressEpoch.get() ||
                                        gate.classify(value.sessionId, value.generation) == PacketDisposition.STALE
                                    ) return@submit
                                    if (decoder == null) queuePending(result) else queueVideo(result)
                                } finally {
                                    ingress.complete()
                                }
                            }
                        }
                        DecoderIngressDecision.DROP -> droppedFrames.incrementAndGet()
                        DecoderIngressDecision.DROP_REQUEST_KEY_FRAME -> {
                            droppedFrames.incrementAndGet()
                            ingressEpoch.incrementAndGet()
                            val stats = ingress.stats()
                            listener.onDiagnostic(value.sessionId, value.generation,
                                "decoder ingress pressure pending=${stats.pendingAccessUnits}; dropping until keyframe")
                            sendFeedback(REQUEST_RECOVERY_FLAGS)
                        }
                    }
                }
            }
        } catch (error: PayloadException) {
            reassembler.clear()
            ingressEpoch.incrementAndGet()
            listener.onDiagnostic(value.sessionId, value.generation, "reassembly recovery: ${error.message}")
            if (ingress.requireKeyFrame()) sendFeedback(REQUEST_RECOVERY_FLAGS)
        }
    }

    fun discontinuity(value: LiveDiscontinuityPayload) {
        if (gate.advanceGeneration(value.sessionId, value.generation) == PacketDisposition.STALE) return
        reassembler.replaceSession(value.sessionId, value.generation)
        config = null
        ingress.beginStream()
        ingressEpoch.incrementAndGet()
        submit {
            pending.clear()
            releaseDecoder()
            publishState(DecoderState.LOADING, "generation ${value.generation}")
        }
    }

    fun stop(value: LiveStopPayload?) {
        val current = session
        if (value != null && current != null && value.sessionId != current.sessionId) return
        stopInternal()
        publishState(DecoderState.IDLE)
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        surface?.release()
        surface = Surface(surfaceTexture)
        listener.onDiagnostic(session?.sessionId ?: 0u, session?.generation ?: 0u, "TextureView available ${width}x$height")
        announceReadyOnce()
        val current = config
        if (surfaceRecoveryAction(active.get(), current != null) == SurfaceRecoveryAction.RECREATE_DECODER_REQUEST_KEYFRAME) {
            submit {
                configureDecoder(requireNotNull(current))
                sendFeedback(REQUEST_RECOVERY_FLAGS)
            }
        }
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        surface?.release(); surface = null
        if (surfaceRecoveryAction(active.get(), config != null) != SurfaceRecoveryAction.NONE) submit {
            releaseDecoder()
            publishState(DecoderState.LOADING, "Video surface recreated; requesting keyframe")
        }
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        active.set(false)
        readyAnnouncements.reset()
        session?.let { gate.end(it.sessionId) }
        session = null; config = null; reassembler.clear()
        ingress.beginStream(); ingressEpoch.incrementAndGet()
        runCatching {
            worker.submit { pending.clear(); releaseDecoder() }.get(CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
        worker.shutdownNow()
        surface?.release(); surface = null
    }

    private fun configureDecoder(value: LiveConfigPayload) {
        releaseDecoder()
        val target = surface?.takeIf { it.isValid } ?: return
        val info = selectHardwareAvcDecoder() ?: error("No hardware AVC decoder is available")
        val capabilities = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val supportsLowLatency = Build.VERSION.SDK_INT >= 30 &&
            capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
        var lowLatency = supportsLowLatency
        fun decoderFormat(enableLowLatency: Boolean) =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, value.width.toInt(), value.height.toInt()).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(value.csd0))
                value.csd1.takeIf { it.isNotEmpty() }?.let { setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
                setInteger(MediaFormat.KEY_FRAME_RATE, value.frameRate.toInt())
                if (enableLowLatency) setFeatureEnabled(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency, true)
            }
        var codec = MediaCodec.createByCodecName(info.name)
        try {
            codec.configure(decoderFormat(lowLatency), target, null, 0)
        } catch (tunedError: Exception) {
            if (!lowLatency) throw tunedError
            Log.w(TAG, "advertised low-latency decoder feature was rejected; retrying without it", tunedError)
            runCatching { codec.release() }
            codec = MediaCodec.createByCodecName(info.name)
            lowLatency = false
            codec.configure(decoderFormat(false), target, null, 0)
        }
        codec.start()
        decoder = codec
        listener.onDiagnostic(value.sessionId, value.generation,
            "decoder=${info.name} ${value.width}x${value.height} fps=${value.frameRate} lowLatency=$lowLatency " +
                "csd=${value.csd0.size}/${value.csd1.size}")
        while (pending.isNotEmpty()) queueVideo(pending.removeFirst())
    }

    private fun queuePending(unit: ReassemblyResult.Complete) {
        if (pending.size >= MAX_PENDING_ACCESS_UNITS) {
            pending.clear()
            droppedFrames.incrementAndGet()
            sendFeedback(REQUEST_RECOVERY_FLAGS)
            if (unit.flags.toUInt() and SampleFlags.KEY_FRAME.toUInt() == 0u) return
        }
        pending.addLast(unit)
    }

    private fun queueVideo(unit: ReassemblyResult.Complete) {
        val codec = decoder ?: return queuePending(unit)
        val deadline = SystemClock.elapsedRealtime() + INPUT_DEADLINE_MS
        var index = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
        while (index < 0 && SystemClock.elapsedRealtime() < deadline) {
            drain(codec, waitForFirstOutput = true)
            index = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
        }
        if (index < 0) {
            droppedFrames.incrementAndGet()
            sendFeedback(REQUEST_RECOVERY_FLAGS)
            return
        }
        val input = requireNotNull(codec.getInputBuffer(index)).apply { clear() }
        if (input.remaining() < unit.bytes.size) throw IllegalStateException("access unit exceeds decoder input")
        input.put(unit.bytes)
        codec.queueInputBuffer(index, 0, unit.bytes.size, unit.presentationTimeUs.toLong(),
            if (unit.flags.toUInt() and SampleFlags.END_OF_STREAM.toUInt() != 0u) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
        drain(codec, waitForFirstOutput = true)
    }

    private fun drain(codec: MediaCodec, waitForFirstOutput: Boolean = false) {
        val info = MediaCodec.BufferInfo()
        var timeoutUs = if (waitForFirstOutput) OUTPUT_WAIT_US else 0L
        while (true) {
            val index = codec.dequeueOutputBuffer(info, timeoutUs)
            timeoutUs = 0L
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) continue
            if (index < 0) return
            val pts = info.presentationTimeUs.coerceAtLeast(0)
            val decoded = decodedUnits.incrementAndGet()
            // Ingress is already bounded before MediaCodec. Comparing decoder output with the
            // newest ingress PTS made the normal four-unit pipeline look stale and caused a
            // self-sustaining drop/keyframe/bitrate-collapse loop. Drain every accepted unit;
            // genuine overload is recovered by DecoderIngressGate before decode.
            codec.releaseOutputBuffer(index, true)
            lastPresentedPtsUs.set(pts)
            publishState(DecoderState.PLAYING)
            if (decoded % FEEDBACK_EVERY == 0L) sendFeedback()
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }

    private fun announceReadyOnce() {
        val current = session ?: return
        // LIVE_READY acknowledges protocol capacity, not render-surface availability.
        // Config and access units are already bounded while TextureView is unavailable,
        // and surface recovery rebuilds the decoder and requests a keyframe. Decoupling
        // readiness lets the phone create the shared encoder/source display immediately.
        if (!active.get() || !readyAnnouncements.take(current.sessionId, current.generation)) return
        listener.onReady(LiveReadyPayload(current.sessionId, current.generation,
            LivePayloads.MAX_ACCESS_UNIT_BYTES.toUInt(), LivePayloads.MAX_FRAGMENTS.toUShort(),
            LivePayloads.TARGET_BUFFER_MS.toUInt()))
    }

    private fun sendFeedback(flags: UByte = 0u) {
        val current = session ?: return
        val depth = ((lastQueuedPtsUs.get() - lastPresentedPtsUs.get()).coerceAtLeast(0) / 1_000).coerceAtMost(60_000)
        listener.onFeedback(LiveFeedbackPayload(current.sessionId, config?.generation ?: current.generation, depth.toUInt(),
            decodedUnits.get().coerceAtMost(UInt.MAX_VALUE.toLong()).toUInt(),
            droppedFrames.get().coerceAtMost(UInt.MAX_VALUE.toLong()).toUInt(),
            lastPresentedPtsUs.get().coerceAtLeast(0).toULong(), flags))
    }

    private fun stopInternal() {
        active.set(false)
        readyAnnouncements.reset()
        session?.let { gate.end(it.sessionId) }
        session = null; config = null
        reassembler.clear(); ingress.beginStream(); ingressEpoch.incrementAndGet()
        submit { pending.clear(); releaseDecoder() }
    }

    private fun releaseDecoder() {
        val current = decoder
        decoder = null
        runCatching { current?.stop() }
        runCatching { current?.release() }
    }

    private fun submit(block: () -> Unit) {
        if (closed.get()) return
        runCatching { worker.execute { runCatching(block).onFailure(::fail) } }
    }

    private fun fail(error: Throwable) {
        if (!terminalError.compareAndSet(false, true)) return
        Log.e(TAG, "decoder error", error)
        val detail = error.message ?: error.javaClass.simpleName
        active.set(false)
        session?.let { gate.end(it.sessionId) }
        session = null; config = null
        reassembler.clear(); ingress.beginStream(); ingressEpoch.incrementAndGet()
        pending.clear()
        releaseDecoder()
        publishState(DecoderState.ERROR, detail)
        listener.onError(detail)
    }

    private fun publishState(state: DecoderState, detail: String? = null) {
        if (stateGate.transition(state)) listener.onState(state, detail)
    }

    companion object {
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val OUTPUT_WAIT_US = 5_000L
        private const val INPUT_DEADLINE_MS = 50L
        private const val CLOSE_TIMEOUT_MS = 1_000L
        internal const val MAX_QUEUED_ACCESS_UNITS = 4
        internal const val MAX_PENDING_ACCESS_UNITS = 8
        private const val FEEDBACK_EVERY = 30L
        private const val TAG = "RdbLiveDecoder"
        private val REQUEST_RECOVERY_FLAGS =
            (FeedbackFlags.REQUEST_KEY_FRAME.toUInt() or FeedbackFlags.MISSING_REFERENCE.toUInt()).toUByte()

        fun surfaceRecoveryAction(active: Boolean, hasConfig: Boolean): SurfaceRecoveryAction = when {
            !active -> SurfaceRecoveryAction.NONE
            hasConfig -> SurfaceRecoveryAction.RECREATE_DECODER_REQUEST_KEYFRAME
            else -> SurfaceRecoveryAction.WAIT_FOR_CONFIG
        }

        fun selectHardwareAvcDecoder(): MediaCodecInfo? = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { info ->
            if (info.isEncoder || MediaFormat.MIMETYPE_VIDEO_AVC !in info.supportedTypes) return@firstOrNull false
            if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else
                !info.name.startsWith("OMX.google", true) && !info.name.startsWith("c2.android", true)
        }
    }
}

enum class SurfaceRecoveryAction { NONE, WAIT_FOR_CONFIG, RECREATE_DECODER_REQUEST_KEYFRAME }

internal class LiveReadyAnnouncementGate {
    private var sessionId: ULong = 0u
    private var generation: UInt = 0u
    private var announced = false

    @Synchronized
    fun begin(sessionId: ULong, generation: UInt) {
        this.sessionId = sessionId
        this.generation = generation
        announced = false
    }

    @Synchronized
    fun take(sessionId: ULong, generation: UInt): Boolean {
        if (announced || sessionId != this.sessionId || generation != this.generation) return false
        announced = true
        return true
    }

    @Synchronized
    fun reset() {
        sessionId = 0u
        generation = 0u
        announced = false
    }
}
