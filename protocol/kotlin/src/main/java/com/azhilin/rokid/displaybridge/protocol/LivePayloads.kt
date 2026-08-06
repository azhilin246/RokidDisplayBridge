package com.azhilin.rokid.displaybridge.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import kotlin.math.roundToInt

class PayloadException(message: String) : IllegalArgumentException(message)

enum class LiveSourceKind(val code: UByte) {
    PRESENTATION_DISPLAY(0u), SCREEN_CAPTURE(1u);

    companion object {
        fun fromCode(code: UByte): LiveSourceKind = entries.firstOrNull { it.code == code }
            ?: throw PayloadException("unknown live source kind $code")
    }
}

object SampleFlags {
    const val KEY_FRAME: UByte = 1u
    const val END_OF_STREAM: UByte = 2u
    const val ALLOWED: UInt = 3u
}

object FeedbackFlags {
    const val REQUEST_KEY_FRAME: UByte = 1u
    const val MISSING_REFERENCE: UByte = 2u
    const val ALLOWED: UInt = 3u
}

data class HelloPayload(
    val maxPayloadBytes: UInt = DBP_MAX_PAYLOAD_SIZE.toUInt(),
    val maxAccessUnitBytes: UInt = LivePayloads.MAX_ACCESS_UNIT_BYTES.toUInt(),
    val maxFragments: UShort = LivePayloads.MAX_FRAGMENTS.toUShort(),
    val capabilities: ULong = LivePayloads.VIDEO_CAPABILITY,
)

enum class MediaCommand(val code: UByte) {
    PLAY_PAUSE(0u), SEEK_BACKWARD(1u), SEEK_FORWARD(2u),
    PLAYLIST_PREVIOUS(3u), PLAYLIST_NEXT(4u),
    FAVORITE_PREVIOUS(5u), FAVORITE_NEXT(6u);

    companion object {
        fun fromCode(code: UByte): MediaCommand = entries.firstOrNull { it.code == code }
            ?: throw PayloadException("unknown media command $code")
    }
}

enum class SwipeGestureAction(val code: UByte) {
    NONE(0u), SEEK(1u), PLAYLIST(2u), FAVORITES(3u);

    companion object {
        fun fromCode(code: UByte): SwipeGestureAction = entries.firstOrNull { it.code == code }
            ?: throw PayloadException("unknown swipe gesture action $code")
    }
}

enum class TapGestureAction(val code: UByte) {
    NONE(0u), PLAY_PAUSE(1u), SHOW_OVERLAY(2u);

    companion object {
        fun fromCode(code: UByte): TapGestureAction = entries.firstOrNull { it.code == code }
            ?: throw PayloadException("unknown tap gesture action $code")
    }
}

enum class DoubleTapGestureAction(val code: UByte) {
    SHOW_OVERLAY(0u), MAIN_MENU(1u), EXIT_APP(2u);

    companion object {
        fun fromCode(code: UByte): DoubleTapGestureAction = entries.firstOrNull { it.code == code }
            ?: throw PayloadException("unknown double tap gesture action $code")
    }
}

data class GestureSettingsPayload(
    val swipeAction: SwipeGestureAction = SwipeGestureAction.SEEK,
    val tapAction: TapGestureAction = TapGestureAction.PLAY_PAUSE,
    val doubleTapAction: DoubleTapGestureAction = DoubleTapGestureAction.SHOW_OVERLAY,
    val reverseSwipeDirections: Boolean = false,
    val confirmExitWithSecondDoubleTap: Boolean = true,
)

data class MediaKeyPayload(
    val sessionId: ULong,
    val generation: UInt,
    val command: MediaCommand,
)

data class DisplayLayoutPayload(
    val scalePermille: UShort = 1_000u,
    val offsetXPermille: Short = 0,
    val offsetYPermille: Short = 0,
) {
    fun clamped(): DisplayLayoutPayload = copy(
        scalePermille = scalePermille.toInt().coerceIn(MIN_SCALE_PERMILLE, MAX_SCALE_PERMILLE).toUShort(),
        offsetXPermille = offsetXPermille.toInt().coerceIn(MIN_OFFSET_PERMILLE, MAX_OFFSET_PERMILLE).toShort(),
        offsetYPermille = offsetYPermille.toInt().coerceIn(MIN_OFFSET_PERMILLE, MAX_OFFSET_PERMILLE).toShort(),
    )

    companion object {
        const val MIN_SCALE_PERMILLE = 250
        const val MAX_SCALE_PERMILLE = 1_000
        const val MIN_OFFSET_PERMILLE = -1_000
        const val MAX_OFFSET_PERMILLE = 1_000
    }
}

object DisplayLayoutMath {
    fun horizontalPosition(container: Double, content: Double, offsetPermille: Int): Double {
        require(container > 0.0 && content >= 0.0)
        val centeredPosition = (container - content) / 2.0
        val maximumDisplacement = (container - content).coerceAtLeast(0.0) / 2.0
        val offset = offsetPermille.coerceIn(
            DisplayLayoutPayload.MIN_OFFSET_PERMILLE,
            DisplayLayoutPayload.MAX_OFFSET_PERMILLE,
        )
        return centeredPosition + maximumDisplacement * offset / 1_000.0
    }

    fun verticalPosition(container: Double, content: Double, offsetPermille: Int): Double {
        return position(container, content, offsetPermille)
    }

    fun horizontalOffsetFromPosition(container: Double, content: Double, position: Double): Int {
        require(container > 0.0 && content >= 0.0 && position.isFinite())
        val centeredPosition = (container - content) / 2.0
        val maximumDisplacement = (container - content).coerceAtLeast(0.0) / 2.0
        if (maximumDisplacement == 0.0) return 0
        return ((position - centeredPosition) / maximumDisplacement * 1_000.0).roundToInt().coerceIn(
            DisplayLayoutPayload.MIN_OFFSET_PERMILLE,
            DisplayLayoutPayload.MAX_OFFSET_PERMILLE,
        )
    }

    fun verticalOffsetFromPosition(container: Double, content: Double, position: Double): Int {
        return offsetFromPosition(container, content, position)
    }

    private fun position(container: Double, content: Double, offsetPermille: Int): Double {
        require(container > 0.0 && content >= 0.0)
        val offset = offsetPermille.coerceIn(
            DisplayLayoutPayload.MIN_OFFSET_PERMILLE,
            DisplayLayoutPayload.MAX_OFFSET_PERMILLE,
        )
        val centeredPosition = (container - content) / 2.0
        val maximumDisplacement = container / 2.0 + content * MAXIMUM_CLIPPED_FRACTION / 2.0
        return centeredPosition + maximumDisplacement * offset / 1_000.0
    }

    private fun offsetFromPosition(container: Double, content: Double, position: Double): Int {
        require(container > 0.0 && content >= 0.0)
        val centeredPosition = (container - content) / 2.0
        val maximumDisplacement = container / 2.0 + content * MAXIMUM_CLIPPED_FRACTION / 2.0
        if (maximumDisplacement == 0.0) return 0
        return ((position - centeredPosition) / maximumDisplacement * 1_000.0).roundToInt().coerceIn(
            DisplayLayoutPayload.MIN_OFFSET_PERMILLE,
            DisplayLayoutPayload.MAX_OFFSET_PERMILLE,
        )
    }

    const val MINIMUM_VISIBLE_FRACTION = 0.1
    private const val MAXIMUM_CLIPPED_FRACTION = 1.0 - 2.0 * MINIMUM_VISIBLE_FRACTION
}

data class LiveStartPayload(val sessionId: ULong, val generation: UInt, val sourceKind: LiveSourceKind, val title: String)
data class LiveReadyPayload(
    val sessionId: ULong,
    val generation: UInt,
    val maxAccessUnitBytes: UInt,
    val maxFragments: UShort,
    val targetBufferMs: UInt,
)

data class LiveConfigPayload(
    val sessionId: ULong,
    val generation: UInt,
    val width: UShort,
    val height: UShort,
    val frameRate: UShort,
    val bitrate: UInt,
    val profile: UInt,
    val level: UInt,
    val csd0: ByteArray,
    val csd1: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is LiveConfigPayload && sessionId == other.sessionId &&
        generation == other.generation && width == other.width && height == other.height && frameRate == other.frameRate &&
        bitrate == other.bitrate && profile == other.profile && level == other.level && csd0.contentEquals(other.csd0) &&
        csd1.contentEquals(other.csd1)

    override fun hashCode(): Int = listOf(sessionId, generation, width, height, frameRate, bitrate, profile, level,
        csd0.contentHashCode(), csd1.contentHashCode()).hashCode()
}

data class LiveSamplePayload(
    val sessionId: ULong,
    val generation: UInt,
    val flags: UByte,
    val accessUnitId: UInt,
    val presentationTimeUs: ULong,
    val fragmentIndex: UShort,
    val fragmentCount: UShort,
    val totalAccessUnitBytes: UInt,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is LiveSamplePayload && sessionId == other.sessionId &&
        generation == other.generation && flags == other.flags && accessUnitId == other.accessUnitId &&
        presentationTimeUs == other.presentationTimeUs && fragmentIndex == other.fragmentIndex &&
        fragmentCount == other.fragmentCount && totalAccessUnitBytes == other.totalAccessUnitBytes && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = listOf(sessionId, generation, flags, accessUnitId, presentationTimeUs, fragmentIndex,
        fragmentCount, totalAccessUnitBytes, bytes.contentHashCode()).hashCode()
}

data class LiveFeedbackPayload(
    val sessionId: ULong,
    val generation: UInt,
    val queueDepthMs: UInt,
    val decodedAccessUnits: UInt,
    val droppedVideoFrames: UInt,
    val lastPresentationTimeUs: ULong,
    val flags: UByte,
)

data class LiveDiscontinuityPayload(val sessionId: ULong, val generation: UInt)
enum class StopReason(val code: UByte) { COMPLETED(0u), USER(1u), REPLACED(2u), DETACHED(3u), ERROR(4u) }
data class LiveStopPayload(val sessionId: ULong, val reason: StopReason, val detail: String)
data class DiagnosticPayload(val sessionId: ULong, val generation: UInt, val detail: String)
enum class ErrorCode(val code: UShort) {
    INCOMPATIBLE_VERSION(1u), MALFORMED_FRAME(2u), INVALID_STATE(3u), CODEC_UNSUPPORTED(4u), RESOURCE_EXHAUSTED(5u), INTERNAL(6u);

    companion object {
        fun fromCode(code: UShort): ErrorCode = entries.firstOrNull { it.code == code }
            ?: throw PayloadException("unknown error code $code")
    }
}
data class ErrorPayload(val code: ErrorCode, val detail: String)

object LivePayloads {
    const val VIDEO_CAPABILITY: ULong = 1uL
    const val MAX_ACCESS_UNIT_BYTES = 512 * 1024
    const val MAX_FRAGMENTS = 64
    const val SAMPLE_PREFIX_SIZE = 33
    const val MAX_TEXT_BYTES = 2_048
    const val MAX_CSD_BYTES = 16_384
    const val TARGET_BUFFER_MS = 33
    private const val GESTURE_FLAG_REVERSE_SWIPE = 1
    private const val GESTURE_FLAG_CONFIRM_EXIT = 2
    private const val GESTURE_FLAGS_ALLOWED = GESTURE_FLAG_REVERSE_SWIPE or GESTURE_FLAG_CONFIRM_EXIT

    fun encodeGestureSettings(value: GestureSettingsPayload): ByteArray {
        val flags = (if (value.reverseSwipeDirections) GESTURE_FLAG_REVERSE_SWIPE else 0) or
            (if (value.confirmExitWithSecondDoubleTap) GESTURE_FLAG_CONFIRM_EXIT else 0)
        return writer(4).put(value.swipeAction.code.toByte()).put(value.tapAction.code.toByte())
            .put(value.doubleTapAction.code.toByte()).put(flags.toByte()).array()
    }

    fun decodeGestureSettings(bytes: ByteArray): GestureSettingsPayload = reader(bytes, 4).let {
        val swipe = SwipeGestureAction.fromCode(it.get().toUByte())
        val tap = TapGestureAction.fromCode(it.get().toUByte())
        val doubleTap = DoubleTapGestureAction.fromCode(it.get().toUByte())
        val flags = it.get().toUByte().toInt()
        if (flags and GESTURE_FLAGS_ALLOWED.inv() != 0) throw PayloadException("unknown gesture settings flags $flags")
        GestureSettingsPayload(
            swipeAction = swipe,
            tapAction = tap,
            doubleTapAction = doubleTap,
            reverseSwipeDirections = flags and GESTURE_FLAG_REVERSE_SWIPE != 0,
            confirmExitWithSecondDoubleTap = flags and GESTURE_FLAG_CONFIRM_EXIT != 0,
        )
    }

    fun encodeMediaKey(value: MediaKeyPayload): ByteArray {
        if (value.sessionId == 0uL || value.generation == 0u) throw PayloadException("invalid MEDIA_KEY identity")
        return writer(13).putLong(value.sessionId.toLong()).putInt(value.generation.toInt())
            .put(value.command.code.toByte()).array()
    }

    fun decodeMediaKey(bytes: ByteArray): MediaKeyPayload = reader(bytes, 13).let {
        MediaKeyPayload(it.long.toULong(), it.int.toUInt(), MediaCommand.fromCode(it.get().toUByte())).also { value ->
            if (value.sessionId == 0uL || value.generation == 0u) throw PayloadException("invalid MEDIA_KEY identity")
        }
    }

    fun encodeDisplayLayout(value: DisplayLayoutPayload): ByteArray {
        val effective = value.clamped()
        return writer(6).putShort(effective.scalePermille.toShort()).putShort(effective.offsetXPermille)
            .putShort(effective.offsetYPermille).array()
    }

    fun decodeDisplayLayout(bytes: ByteArray): DisplayLayoutPayload = reader(bytes, 6).let {
        DisplayLayoutPayload(it.short.toUShort(), it.short, it.short).clamped()
    }

    fun encodeHello(value: HelloPayload): ByteArray {
        validateHello(value)
        return writer(18).putInt(value.maxPayloadBytes.toInt()).putInt(value.maxAccessUnitBytes.toInt())
            .putShort(value.maxFragments.toShort()).putLong(value.capabilities.toLong()).array()
    }

    fun decodeHello(bytes: ByteArray): HelloPayload = reader(bytes, 18).let {
        HelloPayload(it.int.toUInt(), it.int.toUInt(), it.short.toUShort(), it.long.toULong()).also(::validateHello)
    }

    fun encodeStart(value: LiveStartPayload): ByteArray {
        require(value.sessionId != 0uL && value.generation != 0u)
        val title = value.title.encodeToByteArray()
        require(title.size in 1..MAX_TEXT_BYTES)
        return writer(15 + title.size).putLong(value.sessionId.toLong()).putInt(value.generation.toInt())
            .put(value.sourceKind.code.toByte()).putShort(title.size.toShort()).put(title).array()
    }

    fun decodeStart(bytes: ByteArray): LiveStartPayload {
        if (bytes.size < 15) throw PayloadException("LIVE_START is truncated")
        val b = reader(bytes)
        val session = b.long.toULong()
        val generation = b.int.toUInt()
        val source = LiveSourceKind.fromCode(b.get().toUByte())
        val length = b.short.toUShort().toInt()
        if (session == 0uL || generation == 0u || length !in 1..MAX_TEXT_BYTES || b.remaining() != length) {
            throw PayloadException("LIVE_START values are invalid")
        }
        return LiveStartPayload(session, generation, source, ByteArray(length).also(b::get).decodeStrict())
    }

    fun encodeReady(value: LiveReadyPayload): ByteArray {
        validateReady(value)
        return writer(22).putLong(value.sessionId.toLong()).putInt(value.generation.toInt())
            .putInt(value.maxAccessUnitBytes.toInt()).putShort(value.maxFragments.toShort())
            .putInt(value.targetBufferMs.toInt()).array()
    }

    fun decodeReady(bytes: ByteArray): LiveReadyPayload = reader(bytes, 22).let {
        LiveReadyPayload(it.long.toULong(), it.int.toUInt(), it.int.toUInt(), it.short.toUShort(), it.int.toUInt())
            .also(::validateReady)
    }

    fun encodeConfig(value: LiveConfigPayload): ByteArray {
        validateConfig(value)
        return writer(34 + value.csd0.size + value.csd1.size)
            .putLong(value.sessionId.toLong()).putInt(value.generation.toInt())
            .putShort(value.width.toShort()).putShort(value.height.toShort()).putShort(value.frameRate.toShort())
            .putInt(value.bitrate.toInt()).putInt(value.profile.toInt()).putInt(value.level.toInt())
            .putShort(value.csd0.size.toShort()).putShort(value.csd1.size.toShort()).put(value.csd0).put(value.csd1).array()
    }

    fun decodeConfig(bytes: ByteArray): LiveConfigPayload {
        if (bytes.size < 34) throw PayloadException("LIVE_CONFIG is truncated")
        val b = reader(bytes)
        val value = LiveConfigPayload(
            b.long.toULong(), b.int.toUInt(), b.short.toUShort(), b.short.toUShort(), b.short.toUShort(),
            b.int.toUInt(), b.int.toUInt(), b.int.toUInt(),
            ByteArray(b.short.toUShort().toInt()), ByteArray(b.short.toUShort().toInt()),
        )
        if (b.remaining() != value.csd0.size + value.csd1.size) throw PayloadException("LIVE_CONFIG CSD lengths mismatch")
        b.get(value.csd0); b.get(value.csd1)
        validateConfig(value)
        return value
    }

    fun encodeSample(value: LiveSamplePayload): ByteArray {
        validateSample(value)
        return writer(SAMPLE_PREFIX_SIZE + value.bytes.size).putLong(value.sessionId.toLong())
            .putInt(value.generation.toInt()).put(value.flags.toByte()).putInt(value.accessUnitId.toInt())
            .putLong(value.presentationTimeUs.toLong()).putShort(value.fragmentIndex.toShort())
            .putShort(value.fragmentCount.toShort()).putInt(value.totalAccessUnitBytes.toInt()).put(value.bytes).array()
    }

    fun decodeSample(bytes: ByteArray): LiveSamplePayload {
        if (bytes.size < SAMPLE_PREFIX_SIZE) throw PayloadException("LIVE_SAMPLE is truncated")
        val b = reader(bytes)
        return LiveSamplePayload(
            b.long.toULong(), b.int.toUInt(), b.get().toUByte(), b.int.toUInt(), b.long.toULong(),
            b.short.toUShort(), b.short.toUShort(), b.int.toUInt(), ByteArray(b.remaining()).also(b::get),
        ).also(::validateSample)
    }

    fun encodeFeedback(value: LiveFeedbackPayload): ByteArray {
        validateFeedback(value)
        return writer(33).putLong(value.sessionId.toLong()).putInt(value.generation.toInt())
            .putInt(value.queueDepthMs.toInt()).putInt(value.decodedAccessUnits.toInt())
            .putInt(value.droppedVideoFrames.toInt()).putLong(value.lastPresentationTimeUs.toLong())
            .put(value.flags.toByte()).array()
    }

    fun decodeFeedback(bytes: ByteArray): LiveFeedbackPayload = reader(bytes, 33).let {
        LiveFeedbackPayload(it.long.toULong(), it.int.toUInt(), it.int.toUInt(), it.int.toUInt(), it.int.toUInt(),
            it.long.toULong(), it.get().toUByte()).also(::validateFeedback)
    }

    fun encodeDiscontinuity(value: LiveDiscontinuityPayload): ByteArray {
        require(value.sessionId != 0uL && value.generation != 0u)
        return writer(12).putLong(value.sessionId.toLong()).putInt(value.generation.toInt()).array()
    }

    fun decodeDiscontinuity(bytes: ByteArray): LiveDiscontinuityPayload = reader(bytes, 12).let {
        LiveDiscontinuityPayload(it.long.toULong(), it.int.toUInt()).also { value ->
            if (value.sessionId == 0uL || value.generation == 0u) throw PayloadException("invalid discontinuity")
        }
    }

    fun encodeStop(value: LiveStopPayload): ByteArray {
        require(value.sessionId != 0uL)
        val text = value.detail.encodeToByteArray()
        require(text.size <= MAX_TEXT_BYTES)
        return writer(11 + text.size).putLong(value.sessionId.toLong()).put(value.reason.code.toByte())
            .putShort(text.size.toShort()).put(text).array()
    }

    fun decodeStop(bytes: ByteArray): LiveStopPayload {
        if (bytes.size < 11) throw PayloadException("LIVE_STOP is truncated")
        val b = reader(bytes)
        val session = b.long.toULong()
        val code = b.get().toUByte()
        val reason = StopReason.entries.firstOrNull { it.code == code } ?: throw PayloadException("unknown stop reason")
        val length = b.short.toUShort().toInt()
        if (session == 0uL || length > MAX_TEXT_BYTES || b.remaining() != length) throw PayloadException("invalid LIVE_STOP")
        return LiveStopPayload(session, reason, ByteArray(length).also(b::get).decodeStrict())
    }

    fun encodeDiagnostic(value: DiagnosticPayload): ByteArray {
        val text = value.detail.encodeToByteArray()
        require(text.size <= MAX_TEXT_BYTES)
        return writer(14 + text.size).putLong(value.sessionId.toLong()).putInt(value.generation.toInt())
            .putShort(text.size.toShort()).put(text).array()
    }

    fun decodeDiagnostic(bytes: ByteArray): DiagnosticPayload {
        if (bytes.size < 14) throw PayloadException("DIAGNOSTIC is truncated")
        val b = reader(bytes)
        val session = b.long.toULong(); val generation = b.int.toUInt(); val length = b.short.toUShort().toInt()
        if (length > MAX_TEXT_BYTES || b.remaining() != length) throw PayloadException("invalid DIAGNOSTIC")
        return DiagnosticPayload(session, generation, ByteArray(length).also(b::get).decodeStrict())
    }

    fun encodeError(value: ErrorPayload): ByteArray {
        val text = value.detail.encodeToByteArray()
        require(text.size <= MAX_TEXT_BYTES)
        return writer(4 + text.size).putShort(value.code.code.toShort()).putShort(text.size.toShort()).put(text).array()
    }

    fun decodeError(bytes: ByteArray): ErrorPayload {
        if (bytes.size < 4) throw PayloadException("ERROR is truncated")
        val b = reader(bytes)
        val code = ErrorCode.fromCode(b.short.toUShort())
        val length = b.short.toUShort().toInt()
        if (length > MAX_TEXT_BYTES || b.remaining() != length) throw PayloadException("invalid ERROR payload")
        return ErrorPayload(code, ByteArray(length).also(b::get).decodeStrict())
    }

    fun fragment(
        sessionId: ULong,
        generation: UInt,
        flags: UByte,
        accessUnitId: UInt,
        presentationTimeUs: ULong,
        bytes: ByteArray,
        maxPayloadBytes: Int,
    ): List<LiveSamplePayload> {
        require(bytes.size in 1..MAX_ACCESS_UNIT_BYTES)
        val chunkSize = maxPayloadBytes - SAMPLE_PREFIX_SIZE
        require(chunkSize > 0)
        val count = (bytes.size + chunkSize - 1) / chunkSize
        require(count in 1..MAX_FRAGMENTS)
        return (0 until count).map { index ->
            val start = index * chunkSize
            val end = minOf(bytes.size, start + chunkSize)
            LiveSamplePayload(sessionId, generation, flags, accessUnitId, presentationTimeUs, index.toUShort(),
                count.toUShort(), bytes.size.toUInt(), bytes.copyOfRange(start, end))
        }
    }

    private fun validateHello(value: HelloPayload) {
        if (value.maxPayloadBytes !in (SAMPLE_PREFIX_SIZE + 1).toUInt()..DBP_MAX_PAYLOAD_SIZE.toUInt() ||
            value.maxAccessUnitBytes !in 1u..MAX_ACCESS_UNIT_BYTES.toUInt() ||
            value.maxFragments.toInt() !in 1..MAX_FRAGMENTS || value.capabilities and VIDEO_CAPABILITY == 0uL
        ) throw PayloadException("HELLO values are invalid")
    }

    private fun validateReady(value: LiveReadyPayload) {
        if (value.sessionId == 0uL || value.generation == 0u || value.maxAccessUnitBytes !in 1u..MAX_ACCESS_UNIT_BYTES.toUInt() ||
            value.maxFragments.toInt() !in 1..MAX_FRAGMENTS || value.targetBufferMs !in 1u..5_000u
        ) throw PayloadException("LIVE_READY values are invalid")
    }

    private fun validateConfig(value: LiveConfigPayload) {
        if (value.sessionId == 0uL || value.generation == 0u || value.width.toInt() !in 2..480 ||
            value.height.toInt() !in 2..640 || value.frameRate.toInt() !in 1..30 || value.bitrate !in 100_000u..8_000_000u ||
            value.csd0.isEmpty() || value.csd0.size + value.csd1.size > MAX_CSD_BYTES
        ) throw PayloadException("LIVE_CONFIG values are invalid")
    }

    private fun validateSample(value: LiveSamplePayload) {
        if (value.sessionId == 0uL || value.generation == 0u || value.accessUnitId == 0u ||
            value.flags.toUInt() and SampleFlags.ALLOWED.inv() != 0u || value.fragmentCount.toInt() !in 1..MAX_FRAGMENTS ||
            value.fragmentIndex >= value.fragmentCount || value.totalAccessUnitBytes !in 1u..MAX_ACCESS_UNIT_BYTES.toUInt() ||
            value.bytes.isEmpty() || value.bytes.size > value.totalAccessUnitBytes.toInt()
        ) throw PayloadException("LIVE_SAMPLE values are invalid")
    }

    private fun validateFeedback(value: LiveFeedbackPayload) {
        if (value.sessionId == 0uL || value.generation == 0u || value.queueDepthMs > 60_000u ||
            value.flags.toUInt() and FeedbackFlags.ALLOWED.inv() != 0u
        ) throw PayloadException("LIVE_FEEDBACK values are invalid")
    }

    private fun writer(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
    private fun reader(bytes: ByteArray, exact: Int? = null): ByteBuffer {
        if (exact != null && bytes.size != exact) throw PayloadException("expected $exact bytes, got ${bytes.size}")
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    }

    private fun ByteArray.decodeStrict(): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(this)).toString()
}

sealed interface ReassemblyResult {
    data object Pending : ReassemblyResult
    data object Stale : ReassemblyResult
    data class Complete(val flags: UByte, val accessUnitId: UInt, val presentationTimeUs: ULong, val bytes: ByteArray) : ReassemblyResult
}

class AccessUnitReassembler(
    private val maxAccessUnitBytes: Int = LivePayloads.MAX_ACCESS_UNIT_BYTES,
    private val maxPendingUnits: Int = 4,
    private val deadlineMs: Long = 1_000L,
) {
    private data class PendingUnit(
        val flags: UByte,
        val ptsUs: ULong,
        val count: Int,
        val totalBytes: Int,
        val expiresAtMs: Long,
        val fragments: MutableList<ByteArray> = mutableListOf(),
        var receivedBytes: Int = 0,
    )

    private var sessionId: ULong = 0u
    private var generation: UInt = 0u
    private val pending = LinkedHashMap<UInt, PendingUnit>()

    init {
        require(maxAccessUnitBytes in 1..LivePayloads.MAX_ACCESS_UNIT_BYTES && maxPendingUnits > 0 && deadlineMs > 0)
    }

    @Synchronized
    fun replaceSession(sessionId: ULong, generation: UInt) {
        require(sessionId != 0uL && generation != 0u)
        this.sessionId = sessionId
        this.generation = generation
        pending.clear()
    }

    @Synchronized
    fun accept(fragment: LiveSamplePayload, nowMs: Long): ReassemblyResult {
        pending.entries.removeIf { it.value.expiresAtMs <= nowMs }
        if (fragment.sessionId != sessionId || fragment.generation != generation) return ReassemblyResult.Stale
        if (fragment.totalAccessUnitBytes.toInt() > maxAccessUnitBytes) throw PayloadException("access unit exceeds negotiated bound")
        val unit = pending[fragment.accessUnitId] ?: run {
            if (fragment.fragmentIndex != 0.toUShort()) throw PayloadException("first fragment is not zero")
            if (pending.size >= maxPendingUnits) throw PayloadException("too many access units pending")
            PendingUnit(fragment.flags, fragment.presentationTimeUs, fragment.fragmentCount.toInt(),
                fragment.totalAccessUnitBytes.toInt(), nowMs + deadlineMs).also { pending[fragment.accessUnitId] = it }
        }
        if (unit.flags != fragment.flags || unit.ptsUs != fragment.presentationTimeUs || unit.count != fragment.fragmentCount.toInt() ||
            unit.totalBytes != fragment.totalAccessUnitBytes.toInt() || fragment.fragmentIndex.toInt() != unit.fragments.size
        ) throw PayloadException("inconsistent or out-of-order fragment")
        unit.fragments += fragment.bytes
        unit.receivedBytes += fragment.bytes.size
        if (unit.receivedBytes > unit.totalBytes) throw PayloadException("fragment bytes exceed declaration")
        if (unit.fragments.size != unit.count) return ReassemblyResult.Pending
        pending.remove(fragment.accessUnitId)
        if (unit.receivedBytes != unit.totalBytes) throw PayloadException("reassembled size mismatch")
        val bytes = ByteArray(unit.totalBytes)
        var offset = 0
        unit.fragments.forEach { part -> part.copyInto(bytes, offset); offset += part.size }
        return ReassemblyResult.Complete(unit.flags, fragment.accessUnitId, unit.ptsUs, bytes)
    }

    @Synchronized fun clear() = pending.clear()
}
