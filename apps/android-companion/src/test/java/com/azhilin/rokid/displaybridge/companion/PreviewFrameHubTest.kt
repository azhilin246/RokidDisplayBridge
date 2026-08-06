package com.azhilin.rokid.displaybridge.companion

import java.util.concurrent.Executor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewFrameHubTest {
    private val directExecutor = Executor { it.run() }

    @Test fun `preview is opt in bounded low rate and delivers config before frame`() {
        val hub = LatestPreviewFrameHub(minimumIntervalMs = 1_000, executor = directExecutor)
        val updates = mutableListOf<PreviewUpdate>()
        hub.subscribe(updates::add)
        assertFalse(hub.offer(byteArrayOf(1), 1, 7u, 2u, 0))

        hub.setEnabled(true)
        assertTrue(hub.publishConfig(PreviewCodecConfig(7u, 2u, 480, 640, byteArrayOf(1, 2), byteArrayOf(3))))
        val source = byteArrayOf(4, 5, 6)
        assertTrue(hub.offer(source, 10, 7u, 2u, 1_000))
        source[0] = 99
        assertFalse(hub.offer(byteArrayOf(7), 11, 7u, 2u, 1_100))

        assertEquals(2, updates.size)
        assertTrue(updates[0] is PreviewUpdate.CodecConfig)
        val frame = (updates[1] as PreviewUpdate.AccessUnit).value
        assertArrayEquals(byteArrayOf(4, 5, 6), frame.encodedAccessUnit)
        hub.setEnabled(false)
        assertFalse(hub.hasPendingFrame)
        assertNull(hub.takeLatest())
    }

    @Test fun `latest preview replaces older frame and rejects stale config identity`() {
        val queued = mutableListOf<Runnable>()
        val hub = LatestPreviewFrameHub(0, Executor(queued::add))
        hub.setEnabled(true)
        hub.publishConfig(PreviewCodecConfig(8u, 1u, 480, 640, byteArrayOf(1), byteArrayOf()))
        assertTrue(hub.offer(byteArrayOf(1), 1, 8u, 1u, 1))
        assertTrue(hub.offer(byteArrayOf(2), 2, 8u, 1u, 2))
        assertFalse(hub.offer(byteArrayOf(3), 3, 9u, 1u, 3))

        assertArrayEquals(byteArrayOf(2), hub.takeLatest()!!.encodedAccessUnit)
        assertFalse(hub.publishConfig(PreviewCodecConfig(8u, 2u, 2_000, 640, byteArrayOf(), byteArrayOf())))
        hub.clear()
        assertFalse(hub.hasPendingFrame)
    }
}
