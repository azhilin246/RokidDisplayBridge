package com.azhilin.rokid.displaybridge.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

const val DBP_HEADER_SIZE = 16
const val DBP_MAX_PAYLOAD_SIZE = 65_536
const val DBP_MAJOR_VERSION: UByte = 1u
const val DBP_MINOR_VERSION: UByte = 1u

enum class MessageType(val code: UByte) {
    HELLO(0x01u),
    HELLO_ACK(0x02u),
    DISPLAY_LAYOUT(0x30u),
    DISPLAY_LAYOUT_ACK(0x31u),
    MEDIA_KEY(0x32u),
    GESTURE_SETTINGS(0x33u),
    GESTURE_SETTINGS_ACK(0x34u),
    LIVE_START(0x40u),
    LIVE_READY(0x41u),
    LIVE_CONFIG(0x42u),
    LIVE_SAMPLE(0x43u),
    LIVE_FEEDBACK(0x44u),
    LIVE_DISCONTINUITY(0x46u),
    LIVE_STOP(0x47u),
    ERROR(0x7eu),
    DIAGNOSTIC(0x7fu);

    companion object {
        fun fromCode(code: UByte): MessageType? = entries.firstOrNull { it.code == code }
    }
}

data class Frame(
    val type: MessageType,
    val flags: UByte = 0u,
    val requestId: UInt = 0u,
    val payload: ByteArray = byteArrayOf(),
) {
    override fun equals(other: Any?): Boolean = other is Frame && type == other.type && flags == other.flags &&
        requestId == other.requestId && payload.contentEquals(other.payload)

    override fun hashCode(): Int = listOf(type, flags, requestId, payload.contentHashCode()).hashCode()
}

enum class FrameErrorCode { TRUNCATED_HEADER, BAD_MAGIC, BAD_VERSION, UNKNOWN_TYPE, OVERSIZED_PAYLOAD, TRUNCATED_PAYLOAD, INVALID_FLAGS }

class FrameCodecException(val code: FrameErrorCode, message: String) : IllegalArgumentException(message)

object FrameCodec {
    private val magic = byteArrayOf('D'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte())

    fun encode(frame: Frame): ByteArray {
        require(frame.payload.size <= DBP_MAX_PAYLOAD_SIZE) { "payload exceeds DBP1 hard limit" }
        if (frame.flags != 0.toUByte()) throw FrameCodecException(FrameErrorCode.INVALID_FLAGS, "DBP1 flags are reserved")
        return ByteBuffer.allocate(DBP_HEADER_SIZE + frame.payload.size).order(ByteOrder.BIG_ENDIAN)
            .put(magic)
            .put(DBP_MAJOR_VERSION.toByte())
            .put(DBP_MINOR_VERSION.toByte())
            .put(frame.type.code.toByte())
            .put(frame.flags.toByte())
            .putInt(frame.requestId.toInt())
            .putInt(frame.payload.size)
            .put(frame.payload)
            .array()
    }

    fun decode(bytes: ByteArray): Frame {
        if (bytes.size < DBP_HEADER_SIZE) throw FrameCodecException(FrameErrorCode.TRUNCATED_HEADER, "DBP1 header is truncated")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val actualMagic = ByteArray(4).also(buffer::get)
        if (!actualMagic.contentEquals(magic)) throw FrameCodecException(FrameErrorCode.BAD_MAGIC, "frame magic is not DBP1")
        val major = buffer.get().toUByte()
        val minor = buffer.get().toUByte()
        if (major != DBP_MAJOR_VERSION || minor != DBP_MINOR_VERSION) {
            throw FrameCodecException(FrameErrorCode.BAD_VERSION, "unsupported DBP version $major.$minor")
        }
        val typeCode = buffer.get().toUByte()
        val type = MessageType.fromCode(typeCode)
            ?: throw FrameCodecException(FrameErrorCode.UNKNOWN_TYPE, "unknown DBP1 message type $typeCode")
        val flags = buffer.get().toUByte()
        if (flags != 0.toUByte()) throw FrameCodecException(FrameErrorCode.INVALID_FLAGS, "DBP1 flags are reserved")
        val requestId = buffer.int.toUInt()
        val payloadLength = buffer.int.toUInt()
        if (payloadLength > DBP_MAX_PAYLOAD_SIZE.toUInt()) {
            throw FrameCodecException(FrameErrorCode.OVERSIZED_PAYLOAD, "DBP1 payload exceeds hard limit")
        }
        if (payloadLength.toInt() != buffer.remaining()) {
            throw FrameCodecException(FrameErrorCode.TRUNCATED_PAYLOAD, "DBP1 payload length mismatch")
        }
        return Frame(type, flags, requestId, ByteArray(buffer.remaining()).also(buffer::get))
    }
}

class FrameStreamDecoder {
    private val buffer = ByteArray(DBP_HEADER_SIZE + DBP_MAX_PAYLOAD_SIZE)
    private var bufferedBytes = 0
    private var expectedFrameBytes = 0

    @Synchronized
    fun push(chunk: ByteArray): List<Frame> {
        require(chunk.isNotEmpty())
        val result = mutableListOf<Frame>()
        var chunkOffset = 0
        try {
            while (chunkOffset < chunk.size) {
                if (expectedFrameBytes == 0) {
                    val headerBytes = minOf(DBP_HEADER_SIZE - bufferedBytes, chunk.size - chunkOffset)
                    chunk.copyInto(buffer, bufferedBytes, chunkOffset, chunkOffset + headerBytes)
                    bufferedBytes += headerBytes
                    chunkOffset += headerBytes
                    if (bufferedBytes < DBP_HEADER_SIZE) continue
                    expectedFrameBytes = frameLengthFromHeader()
                }

                val bodyBytes = minOf(expectedFrameBytes - bufferedBytes, chunk.size - chunkOffset)
                if (bodyBytes > 0) {
                    chunk.copyInto(buffer, bufferedBytes, chunkOffset, chunkOffset + bodyBytes)
                    bufferedBytes += bodyBytes
                    chunkOffset += bodyBytes
                }
                if (bufferedBytes == expectedFrameBytes) {
                    result += FrameCodec.decode(buffer.copyOf(expectedFrameBytes))
                    bufferedBytes = 0
                    expectedFrameBytes = 0
                }
            }
        } catch (error: Exception) {
            bufferedBytes = 0
            expectedFrameBytes = 0
            throw error
        }
        return result
    }

    private fun frameLengthFromHeader(): Int {
        if (!buffer.copyOfRange(0, 4).contentEquals(byteArrayOf(0x44, 0x42, 0x50, 0x31))) {
            throw FrameCodecException(FrameErrorCode.BAD_MAGIC, "stream is not aligned to DBP1")
        }
        val payloadLength = ByteBuffer.wrap(buffer, 12, 4).order(ByteOrder.BIG_ENDIAN).int.toUInt()
        if (payloadLength > DBP_MAX_PAYLOAD_SIZE.toUInt()) {
            throw FrameCodecException(FrameErrorCode.OVERSIZED_PAYLOAD, "DBP1 stream payload exceeds hard limit")
        }
        return DBP_HEADER_SIZE + payloadLength.toInt()
    }
}
