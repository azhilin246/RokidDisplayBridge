package com.azhilin.rokid.displaybridge.protocol

import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPair

class PendingWirelessPairing internal constructor(
    internal val phoneHello: WirelessPeerHello,
    internal val glassesHello: WirelessPeerHello,
    internal val pairingKey: ByteArray,
    internal val transcript: ByteArray,
)

data class CompletedWirelessPairing(
    val peer: PairedPeer,
    val sessionKeys: WirelessSessionKeys,
)

object WirelessHandshakeSessions {
    fun beginPhonePairing(
        input: InputStream,
        output: OutputStream,
        phoneInstallationId: ByteArray,
    ): PendingWirelessPairing {
        val client = WirelessHandshakeCodec.read(input) as? WirelessHandshakeMessage.PairClientHello
            ?: reject(output, 1u, "expected glasses pairing hello")
        val keys = WirelessSecurity.generateEphemeralKeyPair()
        val phone = WirelessPeerHello(phoneInstallationId, WirelessSecurity.randomNonce(), keys.public.encoded)
        WirelessHandshakeCodec.write(output, WirelessHandshakeMessage.PairServerHello(phone))
        return pending(keys, phone, client.value)
    }

    fun beginGlassesPairing(
        input: InputStream,
        output: OutputStream,
        glassesInstallationId: ByteArray,
    ): PendingWirelessPairing {
        val keys = WirelessSecurity.generateEphemeralKeyPair()
        val glasses = WirelessPeerHello(glassesInstallationId, WirelessSecurity.randomNonce(), keys.public.encoded)
        WirelessHandshakeCodec.write(output, WirelessHandshakeMessage.PairClientHello(glasses))
        val server = WirelessHandshakeCodec.read(input) as? WirelessHandshakeMessage.PairServerHello
            ?: error("expected phone pairing hello")
        return pending(keys, server.value, glasses)
    }

    fun finishPhonePairing(
        pending: PendingWirelessPairing,
        input: InputStream,
        output: OutputStream,
    ): CompletedWirelessPairing {
        val confirm = WirelessHandshakeCodec.read(input) as? WirelessHandshakeMessage.PairConfirm
            ?: reject(output, 2u, "expected glasses pairing confirmation")
        val expected = WirelessSecurity.pairingConfirmation(pending.pairingKey, pending.transcript, "glasses")
        if (!WirelessSecurity.matches(expected, confirm.tag)) reject(output, 3u, "glasses pairing confirmation failed")
        WirelessHandshakeCodec.write(output, WirelessHandshakeMessage.PairAccept(
            WirelessSecurity.pairingConfirmation(pending.pairingKey, pending.transcript, "phone"),
        ))
        return CompletedWirelessPairing(
            peer = PairedPeer(
                pending.glassesHello.installationId,
                WirelessSecurity.derivePeerKey(pending.pairingKey, pending.transcript),
            ),
            sessionKeys = WirelessSecurity.deriveSessionKeys(pending.pairingKey, pending.transcript),
        )
    }

    fun finishGlassesPairing(
        pending: PendingWirelessPairing,
        input: InputStream,
        output: OutputStream,
    ): CompletedWirelessPairing {
        WirelessHandshakeCodec.write(output, WirelessHandshakeMessage.PairConfirm(
            WirelessSecurity.pairingConfirmation(pending.pairingKey, pending.transcript, "glasses"),
        ))
        val accepted = WirelessHandshakeCodec.read(input) as? WirelessHandshakeMessage.PairAccept
            ?: error("expected phone pairing confirmation")
        val expected = WirelessSecurity.pairingConfirmation(pending.pairingKey, pending.transcript, "phone")
        require(WirelessSecurity.matches(expected, accepted.tag)) { "phone pairing confirmation failed" }
        return CompletedWirelessPairing(
            peer = PairedPeer(
                pending.phoneHello.installationId,
                WirelessSecurity.derivePeerKey(pending.pairingKey, pending.transcript),
            ),
            sessionKeys = WirelessSecurity.deriveSessionKeys(pending.pairingKey, pending.transcript),
        )
    }

    fun authenticatePhone(
        input: InputStream,
        output: OutputStream,
        phoneInstallationId: ByteArray,
        peer: PairedPeer,
    ): WirelessSessionKeys {
        val client = WirelessHandshakeCodec.read(input) as? WirelessHandshakeMessage.AuthClientHello
            ?: reject(output, 4u, "expected glasses auth hello")
        if (!WirelessSecurity.matches(peer.installationId, client.installationId)) reject(output, 5u, "unknown wireless peer")
        val phoneNonce = WirelessSecurity.randomNonce()
        val transcript = WirelessSecurity.authTranscript(phoneInstallationId, peer.installationId, phoneNonce, client.nonce)
        WirelessHandshakeCodec.write(output, WirelessHandshakeMessage.AuthServerProof(
            phoneInstallationId,
            phoneNonce,
            WirelessSecurity.authProof(peer.peerKey, transcript, "phone"),
        ))
        val clientProof = WirelessHandshakeCodec.read(input) as? WirelessHandshakeMessage.AuthClientProof
            ?: reject(output, 6u, "expected glasses auth proof")
        if (!WirelessSecurity.matches(
                WirelessSecurity.authProof(peer.peerKey, transcript, "glasses"),
                clientProof.tag,
            )) reject(output, 7u, "glasses auth proof failed")
        WirelessHandshakeCodec.write(output, WirelessHandshakeMessage.AuthAccept(
            WirelessSecurity.authProof(peer.peerKey, transcript, "accepted"),
        ))
        return WirelessSecurity.deriveSessionKeys(peer.peerKey, transcript)
    }

    fun authenticateGlasses(
        input: InputStream,
        output: OutputStream,
        glassesInstallationId: ByteArray,
        peer: PairedPeer,
    ): WirelessSessionKeys {
        val glassesNonce = WirelessSecurity.randomNonce()
        WirelessHandshakeCodec.write(output, WirelessHandshakeMessage.AuthClientHello(glassesInstallationId, glassesNonce))
        val server = WirelessHandshakeCodec.read(input) as? WirelessHandshakeMessage.AuthServerProof
            ?: error("expected phone auth proof")
        require(WirelessSecurity.matches(peer.installationId, server.installationId)) { "unknown wireless peer" }
        val transcript = WirelessSecurity.authTranscript(peer.installationId, glassesInstallationId, server.nonce, glassesNonce)
        require(WirelessSecurity.matches(
            WirelessSecurity.authProof(peer.peerKey, transcript, "phone"),
            server.tag,
        )) { "phone auth proof failed" }
        WirelessHandshakeCodec.write(output, WirelessHandshakeMessage.AuthClientProof(
            WirelessSecurity.authProof(peer.peerKey, transcript, "glasses"),
        ))
        val accepted = WirelessHandshakeCodec.read(input) as? WirelessHandshakeMessage.AuthAccept
            ?: error("expected auth acceptance")
        require(WirelessSecurity.matches(
            WirelessSecurity.authProof(peer.peerKey, transcript, "accepted"),
            accepted.tag,
        )) { "auth acceptance failed" }
        return WirelessSecurity.deriveSessionKeys(peer.peerKey, transcript)
    }

    private fun pending(
        localKeys: KeyPair,
        phone: WirelessPeerHello,
        glasses: WirelessPeerHello,
    ): PendingWirelessPairing {
        val remoteKey = if (localKeys.public.encoded.contentEquals(phone.publicKey)) glasses.publicKey else phone.publicKey
        val transcript = WirelessSecurity.pairingTranscript(phone, glasses)
        val pairingKey = WirelessSecurity.derivePairingKey(
            WirelessSecurity.sharedSecret(localKeys.private, remoteKey),
            transcript,
        )
        return PendingWirelessPairing(
            phone,
            glasses,
            pairingKey,
            transcript,
        )
    }

    private fun reject(output: OutputStream, reason: UByte, message: String): Nothing {
        runCatching { WirelessHandshakeCodec.write(output, WirelessHandshakeMessage.Reject(reason)) }
        error(message)
    }
}
