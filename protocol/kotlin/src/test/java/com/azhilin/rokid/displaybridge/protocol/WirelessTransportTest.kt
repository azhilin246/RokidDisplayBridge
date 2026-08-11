package com.azhilin.rokid.displaybridge.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class WirelessTransportTest {
    @Test fun recoveryBackoffIsBounded() {
        assertEquals(1_500L, WirelessRecoveryPolicy.delayMs(0))
        assertEquals(3_000L, WirelessRecoveryPolicy.delayMs(1))
        assertEquals(5_000L, WirelessRecoveryPolicy.delayMs(2))
        assertEquals(8_000L, WirelessRecoveryPolicy.delayMs(3))
        assertEquals(8_000L, WirelessRecoveryPolicy.delayMs(100))
    }

    @Test(expected = IllegalArgumentException::class)
    fun recoveryBackoffRejectsNegativeAttempts() {
        WirelessRecoveryPolicy.delayMs(-1)
    }

    @Test fun pairingAndReconnectAuthenticationRoundTrip() {
        val phoneId = ByteArray(16) { 1 }
        val glassesId = ByteArray(16) { 2 }
        val server = ServerSocket(0)
        val phonePairing = AtomicReference<CompletedWirelessPairing>()
        val phoneError = AtomicReference<Throwable>()
        val phoneThread = Thread {
            runCatching {
                server.accept().use { socket ->
                    val pending = WirelessHandshakeSessions.beginPhonePairing(socket.getInputStream(), socket.getOutputStream(), phoneId)
                    phonePairing.set(WirelessHandshakeSessions.finishPhonePairing(pending, socket.getInputStream(), socket.getOutputStream()))
                }
            }.onFailure(phoneError::set)
        }.apply { start() }

        val glassesPairing = Socket("127.0.0.1", server.localPort).use { socket ->
            val pending = WirelessHandshakeSessions.beginGlassesPairing(socket.getInputStream(), socket.getOutputStream(), glassesId)
            WirelessHandshakeSessions.finishGlassesPairing(pending, socket.getInputStream(), socket.getOutputStream())
        }
        phoneThread.join(5_000)
        server.close()
        phoneError.get()?.let { throw it }
        assertArrayEquals(glassesId, phonePairing.get().peer.installationId)
        assertArrayEquals(phoneId, glassesPairing.peer.installationId)
        assertArrayEquals(phonePairing.get().peer.peerKey, glassesPairing.peer.peerKey)
        assertArrayEquals(phonePairing.get().sessionKeys.phoneToGlasses.key, glassesPairing.sessionKeys.phoneToGlasses.key)

        val authServer = ServerSocket(0)
        val authError = AtomicReference<Throwable>()
        val authThread = Thread {
            runCatching {
                authServer.accept().use { socket ->
                    WirelessHandshakeSessions.authenticatePhone(socket.getInputStream(), socket.getOutputStream(), phoneId, phonePairing.get().peer)
                }
            }.onFailure(authError::set)
        }.apply { start() }
        Socket("127.0.0.1", authServer.localPort).use { socket ->
            WirelessHandshakeSessions.authenticateGlasses(socket.getInputStream(), socket.getOutputStream(), glassesId, glassesPairing.peer)
        }
        authThread.join(5_000)
        authServer.close()
        authError.get()?.let { throw it }
    }

    @Test fun socketTransportCarriesDbpFramesBothWays() {
        val sessionKeys = WirelessSecurity.deriveSessionKeys(ByteArray(32) { 9 }, ByteArray(32) { 4 })
        val server = ServerSocket(0)
        val accepted = AtomicReference<Socket>()
        val ready = CountDownLatch(1)
        val acceptThread = Thread { accepted.set(server.accept()); ready.countDown() }.apply { start() }
        val clientSocket = Socket("127.0.0.1", server.localPort)
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        val received = CountDownLatch(2)
        val clientFrames = mutableListOf<Frame>()
        val serverFrames = mutableListOf<Frame>()
        fun listener(target: MutableList<Frame>) = object : DbpTransport.Listener {
            override fun onFrame(frame: Frame) { synchronized(target) { target += frame }; received.countDown() }
            override fun onDisconnected(message: String) = Unit
            override fun onError(message: String) = Unit
        }
        val client = SocketDbpTransport(
            clientSocket,
            listener(clientFrames),
            outboundKey = sessionKeys.glassesToPhone,
            inboundKey = sessionKeys.phoneToGlasses,
            writeStallMs = 2_000,
        )
        val remote = SocketDbpTransport(
            accepted.get(),
            listener(serverFrames),
            outboundKey = sessionKeys.phoneToGlasses,
            inboundKey = sessionKeys.glassesToPhone,
            writeStallMs = 2_000,
        )
        client.start(); remote.start()
        client.send(Frame(MessageType.DIAGNOSTIC, payload = byteArrayOf(1, 2, 3)))
        remote.send(Frame(MessageType.HELLO, requestId = 7u))
        assertTrue(received.await(2, TimeUnit.SECONDS))
        assertEquals(MessageType.HELLO, clientFrames.single().type)
        assertEquals(MessageType.DIAGNOSTIC, serverFrames.single().type)
        client.close(); remote.close(); server.close(); acceptThread.join(1_000)
    }

    @Test fun secureRecordsHandleFragmentationAndRejectTamperingOrReplay() {
        val key = WirelessSecurity.deriveSessionKeys(ByteArray(32) { 3 }, ByteArray(32) { 7 }).phoneToGlasses
        val encoder = SecureRecordCodec(key)
        val first = encoder.encode(FrameCodec.encode(Frame(MessageType.HELLO, requestId = 4u)))
        val second = encoder.encode(FrameCodec.encode(Frame(MessageType.DIAGNOSTIC, payload = byteArrayOf(1, 2))))
        val decoder = SecureRecordStreamDecoder(key)
        assertTrue(decoder.push(first.copyOfRange(0, 7)).isEmpty())
        assertEquals(MessageType.HELLO, FrameCodec.decode(decoder.push(first.copyOfRange(7, first.size)).single()).type)
        assertEquals(MessageType.DIAGNOSTIC, FrameCodec.decode(decoder.push(second).single()).type)

        val tampered = SecureRecordCodec(key).encode(FrameCodec.encode(Frame(MessageType.HELLO))).also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        assertThrows(SecurityException::class.java) { SecureRecordStreamDecoder(key).push(tampered) }
        assertThrows(IllegalArgumentException::class.java) { SecureRecordStreamDecoder(key).push(second) }
    }
}
