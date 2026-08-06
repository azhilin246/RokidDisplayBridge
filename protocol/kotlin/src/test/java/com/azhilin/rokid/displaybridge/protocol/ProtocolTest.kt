package com.azhilin.rokid.displaybridge.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test fun `DBP1 frame round trips and stream decoder handles chunks`() {
        assertEquals(1u.toUByte(), DBP_MINOR_VERSION)
        val frame = Frame(MessageType.DIAGNOSTIC, requestId = 7u, payload = byteArrayOf(1, 2, 3))
        val encoded = FrameCodec.encode(frame)
        assertArrayEquals(byteArrayOf(0x44, 0x42, 0x50, 0x31), encoded.copyOfRange(0, 4))
        assertEquals(frame, FrameCodec.decode(encoded))
        val stream = FrameStreamDecoder()
        assertTrue(stream.push(encoded.copyOfRange(0, 8)).isEmpty())
        assertEquals(listOf(frame), stream.push(encoded.copyOfRange(8, encoded.size)))
    }

    @Test fun `stream decoder accepts coalesced maximum frames across arbitrary chunks`() {
        val first = Frame(MessageType.LIVE_SAMPLE, requestId = 1u, payload = ByteArray(DBP_MAX_PAYLOAD_SIZE) { 0x31 })
        val second = Frame(MessageType.LIVE_SAMPLE, requestId = 2u, payload = ByteArray(DBP_MAX_PAYLOAD_SIZE) { 0x52 })
        val bytes = FrameCodec.encode(first) + FrameCodec.encode(second)
        val stream = FrameStreamDecoder()

        assertTrue(stream.push(bytes.copyOfRange(0, DBP_MAX_PAYLOAD_SIZE)).isEmpty())
        assertEquals(listOf(first), stream.push(bytes.copyOfRange(DBP_MAX_PAYLOAD_SIZE, DBP_MAX_PAYLOAD_SIZE * 2)))
        assertEquals(listOf(second), stream.push(bytes.copyOfRange(DBP_MAX_PAYLOAD_SIZE * 2, bytes.size)))
    }

    @Test fun `frame rejects foreign magic and reserved flags`() {
        val bytes = FrameCodec.encode(Frame(MessageType.HELLO)).also { it[0] = 'R'.code.toByte() }
        assertEquals(FrameErrorCode.BAD_MAGIC, assertThrows(FrameCodecException::class.java) { FrameCodec.decode(bytes) }.code)
        assertThrows(FrameCodecException::class.java) { FrameCodec.encode(Frame(MessageType.HELLO, flags = 1u)) }
    }

    @Test fun `all live payloads round trip`() {
        val hello = HelloPayload()
        assertEquals(hello, LivePayloads.decodeHello(LivePayloads.encodeHello(hello)))
        val start = LiveStartPayload(42u, 3u, LiveSourceKind.PRESENTATION_DISPLAY, "External display")
        assertEquals(start, LivePayloads.decodeStart(LivePayloads.encodeStart(start)))
        val ready = LiveReadyPayload(42u, 3u, 100_000u, 16u, 200u)
        assertEquals(ready, LivePayloads.decodeReady(LivePayloads.encodeReady(ready)))
        val config = LiveConfigPayload(42u, 3u, 480u, 640u, 30u, 2_000_000u, 8u, 256u,
            byteArrayOf(1, 2, 3), byteArrayOf(4, 5))
        assertEquals(config, LivePayloads.decodeConfig(LivePayloads.encodeConfig(config)))
        val feedback = LiveFeedbackPayload(42u, 3u, 50u, 10u, 2u, 33_000u, FeedbackFlags.REQUEST_KEY_FRAME)
        assertEquals(feedback, LivePayloads.decodeFeedback(LivePayloads.encodeFeedback(feedback)))
        val diagnostic = DiagnosticPayload(42u, 3u, "decoder ready")
        assertEquals(diagnostic, LivePayloads.decodeDiagnostic(LivePayloads.encodeDiagnostic(diagnostic)))
        val stop = LiveStopPayload(42u, StopReason.USER, "done")
        assertEquals(stop, LivePayloads.decodeStop(LivePayloads.encodeStop(stop)))
        val media = MediaKeyPayload(42u, 3u, MediaCommand.SEEK_FORWARD)
        assertEquals(media, LivePayloads.decodeMediaKey(LivePayloads.encodeMediaKey(media)))
        val gestures = GestureSettingsPayload(
            SwipeGestureAction.FAVORITES,
            TapGestureAction.SHOW_OVERLAY,
            DoubleTapGestureAction.EXIT_APP,
            reverseSwipeDirections = true,
            confirmExitWithSecondDoubleTap = false,
        )
        assertEquals(gestures, LivePayloads.decodeGestureSettings(LivePayloads.encodeGestureSettings(gestures)))
        val layout = DisplayLayoutPayload(750u, -250, 500)
        assertEquals(layout, LivePayloads.decodeDisplayLayout(LivePayloads.encodeDisplayLayout(layout)))
    }

    @Test fun `gesture settings reject unknown enum flags and lengths`() {
        assertThrows(PayloadException::class.java) {
            LivePayloads.decodeGestureSettings(byteArrayOf(9, 0, 0, 0))
        }
        assertThrows(PayloadException::class.java) {
            LivePayloads.decodeGestureSettings(byteArrayOf(0, 0, 0, 4))
        }
        assertThrows(PayloadException::class.java) {
            LivePayloads.decodeGestureSettings(byteArrayOf(0, 0, 0))
        }
    }

    @Test fun `media commands require active identity and layouts are bounded`() {
        assertThrows(PayloadException::class.java) {
            LivePayloads.encodeMediaKey(MediaKeyPayload(0u, 1u, MediaCommand.PLAY_PAUSE))
        }
        val raw = java.nio.ByteBuffer.allocate(6).order(java.nio.ByteOrder.BIG_ENDIAN)
            .putShort(2_000).putShort(Short.MIN_VALUE).putShort(Short.MAX_VALUE).array()
        assertEquals(DisplayLayoutPayload(1_000u, -1_000, 1_000), LivePayloads.decodeDisplayLayout(raw))
        assertEquals(6, LivePayloads.encodeDisplayLayout(DisplayLayoutPayload(100u, -2_000, 2_000)).size)
    }

    @Test fun `layout stays inside horizontally and retains ten percent vertically`() {
        assertEquals(-576.0, DisplayLayoutMath.verticalPosition(640.0, 640.0, -1_000), 0.0001)
        assertEquals(576.0, DisplayLayoutMath.verticalPosition(640.0, 640.0, 1_000), 0.0001)
        assertEquals(-288.0, DisplayLayoutMath.verticalPosition(640.0, 320.0, -1_000), 0.0001)
        assertEquals(0.0, DisplayLayoutMath.horizontalPosition(480.0, 480.0, -1_000), 0.0001)
        assertEquals(0.0, DisplayLayoutMath.horizontalPosition(480.0, 240.0, -1_000), 0.0001)
        assertEquals(120.0, DisplayLayoutMath.horizontalPosition(480.0, 240.0, 0), 0.0001)
        assertEquals(240.0, DisplayLayoutMath.horizontalPosition(480.0, 240.0, 1_000), 0.0001)
        assertEquals(-556, DisplayLayoutMath.verticalOffsetFromPosition(640.0, 640.0, -320.0))
        assertEquals(1_000, DisplayLayoutMath.verticalOffsetFromPosition(640.0, 640.0, 10_000.0))
        assertEquals(0, DisplayLayoutMath.horizontalOffsetFromPosition(480.0, 480.0, -10_000.0))
        assertEquals(-1_000, DisplayLayoutMath.horizontalOffsetFromPosition(480.0, 240.0, -10_000.0))
        assertEquals(0, DisplayLayoutMath.horizontalOffsetFromPosition(480.0, 240.0, 120.0))
        assertEquals(1_000, DisplayLayoutMath.horizontalOffsetFromPosition(480.0, 240.0, 10_000.0))
    }

    @Test fun `fragmentation and reassembly are bounded and exact`() {
        val source = ByteArray(200_000) { (it % 251).toByte() }
        val fragments = LivePayloads.fragment(9u, 2u, SampleFlags.KEY_FRAME, 1u, 123u, source, 8_192)
        assertTrue(fragments.size in 2..LivePayloads.MAX_FRAGMENTS)
        val reassembler = AccessUnitReassembler().apply { replaceSession(9u, 2u) }
        fragments.dropLast(1).forEach { assertEquals(ReassemblyResult.Pending, reassembler.accept(it, 0)) }
        val complete = reassembler.accept(fragments.last(), 0) as ReassemblyResult.Complete
        assertArrayEquals(source, complete.bytes)
        assertEquals(SampleFlags.KEY_FRAME, complete.flags)
    }

    @Test fun `stale session is silent and out of order is rejected`() {
        val fragments = LivePayloads.fragment(7u, 1u, 0u, 1u, 0u, ByteArray(100), 80)
        val reassembler = AccessUnitReassembler().apply { replaceSession(8u, 1u) }
        assertEquals(ReassemblyResult.Stale, reassembler.accept(fragments.first(), 0))
        reassembler.replaceSession(7u, 1u)
        assertThrows(PayloadException::class.java) { reassembler.accept(fragments[1], 0) }
    }

    @Test fun `queue prioritizes control and stays bounded`() {
        assertEquals(8, LiveFrameQueue.DEFAULT_MAX_VIDEO_FRAMES)
        val queue = LiveFrameQueue(maxControlFrames = 2, maxVideoFrames = 2)
        val video = { id: UInt, key: Boolean ->
            QueuedFrame(listOf(Frame(MessageType.LIVE_SAMPLE, requestId = id)), FramePriority.VIDEO, key)
        }
        assertTrue(queue.offer(video(1u, false)).accepted)
        assertTrue(queue.offer(video(2u, true)).accepted)
        val pressure = queue.offer(video(3u, false))
        assertFalse(pressure.accepted)
        assertTrue(pressure.keyFrameRequired)
        assertEquals(3, queue.stats().droppedVideoFrames)
        val control = QueuedFrame(listOf(Frame(MessageType.LIVE_STOP)), FramePriority.CONTROL)
        assertTrue(queue.offer(control).accepted)
        assertEquals(MessageType.LIVE_STOP, queue.poll()!!.frames.first().type)
        queue.clear()
        assertEquals(0, queue.stats().videoDepth)
    }

    @Test fun `pressure rejects predictive chain until a replacement keyframe`() {
        val queue = LiveFrameQueue(maxControlFrames = 1, maxVideoFrames = 2)
        val video = { id: UInt, key: Boolean ->
            QueuedFrame(listOf(Frame(MessageType.LIVE_SAMPLE, requestId = id)), FramePriority.VIDEO, key)
        }
        assertTrue(queue.offer(video(1u, false)).accepted)
        assertTrue(queue.offer(video(2u, false)).accepted)
        assertEquals(QueueOfferResult(accepted = false, keyFrameRequired = true), queue.offer(video(3u, false)))
        assertEquals(0, queue.stats().videoDepth)
        assertEquals(3, queue.stats().droppedVideoFrames)
        assertEquals(QueueOfferResult(accepted = false, keyFrameRequired = false), queue.offer(video(4u, false)))
        assertEquals(QueueOfferResult(accepted = true, keyFrameRequired = false), queue.offer(video(5u, true)))
        assertEquals(1, queue.stats().videoDepth)
        assertTrue(queue.offer(video(6u, false)).accepted)
    }

    @Test fun `new keyframe replaces a full obsolete queue without requesting recovery`() {
        val queue = LiveFrameQueue(maxVideoFrames = 2)
        val predictive = QueuedFrame(listOf(Frame(MessageType.LIVE_SAMPLE)), FramePriority.VIDEO, false)
        val key = QueuedFrame(listOf(Frame(MessageType.LIVE_SAMPLE)), FramePriority.VIDEO, true)
        assertTrue(queue.offer(predictive).accepted)
        assertTrue(queue.offer(predictive).accepted)
        assertEquals(QueueOfferResult(accepted = true, keyFrameRequired = false), queue.offer(key))
        assertEquals(1, queue.stats().videoDepth)
        assertEquals(2, queue.stats().droppedVideoFrames)
    }

    @Test fun `session gate rejects old generations and ended session`() {
        val gate = SessionGate()
        gate.replace(100u, 2u)
        assertEquals(PacketDisposition.ACCEPT, gate.classify(100u, 2u))
        assertEquals(PacketDisposition.STALE, gate.classify(100u, 1u))
        assertEquals(PacketDisposition.STALE, gate.advanceGeneration(100u, 2u))
        assertEquals(PacketDisposition.ACCEPT, gate.advanceGeneration(100u, 3u))
        gate.end(100u)
        assertEquals(PacketDisposition.STALE, gate.classify(100u, 3u))
    }
}
