package com.azhilin.rokid.displaybridge.companion.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

data class EncoderConfig(
    val codecName: String,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val bitrate: Int,
    val bitrateMode: Int,
    val latencyFrames: Int,
    val realtimeTuning: Boolean,
    val profile: Int,
    val level: Int,
    val csd0: ByteArray,
    val csd1: ByteArray,
)

data class EncoderStats(val outputFrames: Long, val outputBytes: Long)
data class AvcEncoderSelection(
    val info: MediaCodecInfo,
    val profile: Int,
    val level: Int,
    val bitrateMode: Int,
)

class SurfaceToAvcStream(
    private val width: Int = WIDTH,
    private val height: Int = HEIGHT,
    private val frameRate: Int = FRAME_RATE,
    initialBitrate: Int = BITRATE,
    private val listener: Listener,
) : Closeable {
    interface Listener {
        fun onConfig(config: EncoderConfig)
        fun onAccessUnit(bytes: ByteArray, presentationTimeUs: Long, keyFrame: Boolean, endOfStream: Boolean)
        fun onStats(stats: EncoderStats)
        fun onError(message: String)
    }

    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val frames = AtomicLong(0)
    private val bytes = AtomicLong(0)
    private val targetBitrate = AtomicInteger(initialBitrate)
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var worker: Thread? = null
    private var configuredProfile = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
    private var configuredLevel = MediaCodecInfo.CodecProfileLevel.AVCLevel31
    private var configuredBitrateMode = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
    private var realtimeTuningApplied = false
    val codecName: String get() = codec?.name ?: "not started"

    fun start(): Surface {
        check(!closed.get()) { "encoder is closed" }
        check(running.compareAndSet(false, true)) { "encoder already started" }
        val selection = selectHardwareAvcEncoder(width, height, frameRate) ?: run {
            running.set(false)
            error("No hardware AVC encoder with Surface input is available")
        }
        var configured = MediaCodec.createByCodecName(selection.info.name)
        configuredProfile = selection.profile
        configuredLevel = selection.level
        configuredBitrateMode = selection.bitrateMode
        try {
            realtimeTuningApplied = true
            try {
                configured.configure(encoderFormat(selection, realtime = true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (tunedError: Exception) {
                Log.w(TAG, "realtime encoder tuning rejected; retrying conservative format", tunedError)
                runCatching { configured.release() }
                configured = MediaCodec.createByCodecName(selection.info.name)
                realtimeTuningApplied = false
                configuredBitrateMode = -1
                configured.configure(encoderFormat(selection, realtime = false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            val surface = configured.createInputSurface()
            configured.start()
            codec = configured
            inputSurface = surface
            worker = Thread(::drainLoop, "rdb-avc-encoder").apply { start() }
            Log.i(TAG, "started codec=${selection.info.name} profile=${selection.profile} level=${selection.level} " +
                "size=${width}x$height fps=$frameRate bitrate=${targetBitrate.get()} mode=$configuredBitrateMode " +
                "realtime=$realtimeTuningApplied")
            return surface
        } catch (error: Exception) {
            running.set(false)
            runCatching { configured.release() }
            throw error
        }
    }

    fun requestKeyFrame() {
        runCatching { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    fun updateBitrate(bitrate: Int): Boolean {
        val effective = bitrate.coerceIn(100_000, 8_000_000)
        if (effective == targetBitrate.get()) return false
        return runCatching {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, effective) })
                ?: error("encoder is not running")
            targetBitrate.set(effective)
            Log.i(TAG, "runtime bitrate updated to $effective")
            true
        }.getOrElse {
            Log.w(TAG, "runtime bitrate update to $effective failed", it)
            false
        }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                val current = codec ?: break
                when (val index = current.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> publishConfig(current.outputFormat)
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (index >= 0) {
                        val codecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (!codecConfig && info.size > 0) {
                            val output = requireNotNull(current.getOutputBuffer(index)).duplicate().apply {
                                position(info.offset); limit(info.offset + info.size)
                            }
                            val data = ByteArray(info.size).also(output::get)
                            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            listener.onAccessUnit(data, info.presentationTimeUs.coerceAtLeast(0), key, end)
                            val count = frames.incrementAndGet()
                            bytes.addAndGet(data.size.toLong())
                            if (count <= 3 || count % 30L == 0L) listener.onStats(EncoderStats(count, bytes.get()))
                        }
                        current.releaseOutputBuffer(index, false)
                        if (end) break
                    }
                }
            }
        } catch (error: Exception) {
            if (running.get()) listener.onError(error.message ?: error.javaClass.simpleName)
        } finally {
            running.set(false)
        }
    }

    private fun publishConfig(format: MediaFormat) {
        listener.onConfig(EncoderConfig(
            codecName = codecName,
            width = format.integerOr(MediaFormat.KEY_WIDTH, width),
            height = format.integerOr(MediaFormat.KEY_HEIGHT, height),
            frameRate = format.integerOr(MediaFormat.KEY_FRAME_RATE, frameRate),
            bitrate = format.integerOr(MediaFormat.KEY_BIT_RATE, targetBitrate.get()),
            bitrateMode = format.integerOr(MediaFormat.KEY_BITRATE_MODE, configuredBitrateMode),
            latencyFrames = format.integerOr(MediaFormat.KEY_LATENCY, 0),
            realtimeTuning = realtimeTuningApplied,
            profile = format.integerOr(MediaFormat.KEY_PROFILE, configuredProfile),
            level = format.integerOr(MediaFormat.KEY_LEVEL, configuredLevel),
            csd0 = format.bytes("csd-0"),
            csd1 = format.bytes("csd-1"),
        ))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        val drainWorker = worker
        drainWorker?.interrupt()
        if (drainWorker != Thread.currentThread()) runCatching { drainWorker?.join(1_000) }
        worker = null
        val current = codec
        codec = null
        runCatching { current?.signalEndOfInputStream() }
        runCatching { current?.stop() }
        runCatching { inputSurface?.release() }
        inputSurface = null
        runCatching { current?.release() }
    }

    private fun encoderFormat(selection: AvcEncoderSelection, realtime: Boolean) =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate.get())
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE, selection.profile)
            setInteger(MediaFormat.KEY_LEVEL, selection.level)
            if (realtime) applyRealtimeSettings(this, frameRate, selection.bitrateMode)
        }

    private fun MediaFormat.bytes(key: String): ByteArray = getByteBuffer(key)?.duplicate()?.let { buffer ->
        ByteArray(buffer.remaining()).also(buffer::get)
    } ?: byteArrayOf()

    companion object {
        const val WIDTH = 480
        const val HEIGHT = 640
        const val FRAME_RATE = 30
        const val BITRATE = 1_200_000
        private const val TAG = "RdbAvcEncoder"

        internal fun applyRealtimeSettings(format: MediaFormat, frameRate: Int, bitrateMode: Int) {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            format.setInteger(MediaFormat.KEY_LATENCY, 0)
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, bitrateMode)
            if (Build.VERSION.SDK_INT >= 29) {
                format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, frameRate.toFloat())
            }
        }

        fun selectHardwareAvcEncoder(width: Int = WIDTH, height: Int = HEIGHT, frameRate: Int = FRAME_RATE): AvcEncoderSelection? {
            val preferredProfiles = intArrayOf(
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
                MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
            )
            return MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstNotNullOfOrNull { info ->
                if (!info.isEncoder || MediaFormat.MIMETYPE_VIDEO_AVC !in info.supportedTypes) return@firstNotNullOfOrNull null
                val capabilities = runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC) }.getOrNull()
                    ?: return@firstNotNullOfOrNull null
                val videoCapabilities = capabilities.videoCapabilities ?: return@firstNotNullOfOrNull null
                val hardware = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else
                    !info.name.startsWith("OMX.google", true) && !info.name.startsWith("c2.android", true)
                if (!hardware || MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in capabilities.colorFormats ||
                    !runCatching { videoCapabilities.areSizeAndRateSupported(width, height, frameRate.toDouble()) }
                        .getOrDefault(false)) return@firstNotNullOfOrNull null
                val profile = preferredProfiles.firstOrNull { candidate ->
                    capabilities.profileLevels.any { it.profile == candidate && it.level >= MediaCodecInfo.CodecProfileLevel.AVCLevel31 }
                } ?: return@firstNotNullOfOrNull null
                val encoderCapabilities = capabilities.encoderCapabilities ?: return@firstNotNullOfOrNull null
                val bitrateMode = when {
                    encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) ->
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) ->
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                    else -> return@firstNotNullOfOrNull null
                }
                AvcEncoderSelection(info, profile, MediaCodecInfo.CodecProfileLevel.AVCLevel31, bitrateMode)
            }
        }
    }
}

private fun MediaFormat.integerOr(key: String, fallback: Int): Int = if (containsKey(key)) getInteger(key) else fallback
