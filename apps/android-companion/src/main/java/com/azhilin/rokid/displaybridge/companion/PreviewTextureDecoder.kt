package com.azhilin.rokid.displaybridge.companion

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Decodes the bounded IDR-only preview stream without participating in live USB delivery. */
class PreviewTextureDecoder(private val textureView: TextureView) : Closeable {
    private val closed = AtomicBoolean(false)
    private val thread = HandlerThread("RdbPreviewDecoder").apply { start() }
    private val handler = Handler(thread.looper)
    private var outputSurface: Surface? = null
    private var codec: MediaCodec? = null
    private var config: PreviewCodecConfig? = null
    private var configuredSession: ULong = 0u
    private var configuredGeneration: UInt = 0u

    val updateListener: (PreviewUpdate) -> Unit = { update ->
        if (!closed.get()) handler.post { accept(update) }
    }

    init {
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                handler.post {
                    outputSurface?.release()
                    outputSurface = Surface(surfaceTexture)
                    configureIfReady()
                }
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                handler.post {
                    releaseCodec()
                    outputSurface?.release()
                    outputSurface = null
                }
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        if (textureView.isAvailable) {
            textureView.surfaceTexture?.let { available ->
                handler.post {
                    outputSurface = Surface(available)
                    configureIfReady()
                }
            }
        }
    }

    private fun accept(update: PreviewUpdate) {
        if (closed.get()) return
        when (update) {
            is PreviewUpdate.CodecConfig -> {
                config = update.value
                configureIfReady()
            }
            is PreviewUpdate.AccessUnit -> queue(update.value)
        }
    }

    private fun configureIfReady() {
        val next = config ?: return
        val surface = outputSurface ?: return
        if (codec != null && configuredSession == next.sessionId && configuredGeneration == next.generation) return
        releaseCodec()
        runCatching {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, next.width, next.height).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(next.csd0))
                if (next.csd1.isNotEmpty()) setByteBuffer("csd-1", ByteBuffer.wrap(next.csd1))
            }
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { decoder ->
                decoder.configure(format, surface, null, 0)
                decoder.start()
                codec = decoder
                configuredSession = next.sessionId
                configuredGeneration = next.generation
            }
        }.onFailure { error ->
            Log.w(TAG, "Preview decoder configuration failed", error)
            releaseCodec()
        }
    }

    private fun queue(frame: PreviewFrame) {
        if (frame.sessionId != configuredSession || frame.generation != configuredGeneration) return
        val decoder = codec ?: return
        runCatching {
            val inputIndex = decoder.dequeueInputBuffer(0)
            if (inputIndex < 0) return@runCatching
            val input = requireNotNull(decoder.getInputBuffer(inputIndex)) { "Preview decoder returned no input buffer" }
            require(frame.encodedAccessUnit.size <= input.capacity()) { "Preview keyframe exceeds decoder input buffer" }
            input.clear()
            input.put(frame.encodedAccessUnit)
            decoder.queueInputBuffer(
                inputIndex,
                0,
                frame.encodedAccessUnit.size,
                frame.presentationTimeUs.coerceAtLeast(0),
                MediaCodec.BUFFER_FLAG_KEY_FRAME,
            )
            drain(decoder)
        }.onFailure { error ->
            Log.w(TAG, "Preview frame decode failed", error)
            releaseCodec()
            configureIfReady()
        }
    }

    private fun drain(decoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var timeoutUs = FIRST_OUTPUT_TIMEOUT_US
        while (true) {
            when (val outputIndex = decoder.dequeueOutputBuffer(info, timeoutUs)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> if (outputIndex >= 0) decoder.releaseOutputBuffer(outputIndex, true) else return
            }
            timeoutUs = 0
        }
    }

    private fun releaseCodec() {
        val current = codec
        codec = null
        configuredSession = 0u
        configuredGeneration = 0u
        runCatching { current?.stop() }
        runCatching { current?.release() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        textureView.surfaceTextureListener = null
        handler.post {
            releaseCodec()
            outputSurface?.release()
            outputSurface = null
            config = null
        }
        thread.quitSafely()
    }

    companion object {
        private const val TAG = "RdbPreviewDecoder"
        private const val FIRST_OUTPUT_TIMEOUT_US = 20_000L
    }
}
