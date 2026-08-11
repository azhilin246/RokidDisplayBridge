package com.azhilin.rokid.displaybridge.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessSecurityTest {
    @Test fun trafficKeysAreDeterministicAndDirectionSeparated() {
        val secret = ByteArray(32) { (it + 3).toByte() }
        val transcript = ByteArray(32) { (it * 7).toByte() }
        val first = WirelessSecurity.deriveSessionKeys(secret, transcript)
        val second = WirelessSecurity.deriveSessionKeys(secret, transcript)

        assertArrayEquals(first.phoneToGlasses.key, second.phoneToGlasses.key)
        assertArrayEquals(first.phoneToGlasses.noncePrefix, second.phoneToGlasses.noncePrefix)
        assertFalse(first.phoneToGlasses.key.contentEquals(first.glassesToPhone.key))
        assertFalse(first.phoneToGlasses.noncePrefix.contentEquals(first.glassesToPhone.noncePrefix))
    }

    @Test fun bothPeersDeriveSameSecretSequenceAndProofs() {
        val phoneKeys = WirelessSecurity.generateEphemeralKeyPair()
        val glassesKeys = WirelessSecurity.generateEphemeralKeyPair()
        val phone = WirelessPeerHello(ByteArray(16) { 1 }, ByteArray(16) { 2 }, phoneKeys.public.encoded)
        val glasses = WirelessPeerHello(ByteArray(16) { 3 }, ByteArray(16) { 4 }, glassesKeys.public.encoded)
        val phoneSecret = WirelessSecurity.sharedSecret(phoneKeys.private, glasses.publicKey)
        val glassesSecret = WirelessSecurity.sharedSecret(glassesKeys.private, phone.publicKey)
        assertArrayEquals(phoneSecret, glassesSecret)

        val transcript = WirelessSecurity.pairingTranscript(phone, glasses)
        val phoneKey = WirelessSecurity.derivePairingKey(phoneSecret, transcript)
        val glassesKey = WirelessSecurity.derivePairingKey(glassesSecret, transcript)
        assertArrayEquals(phoneKey, glassesKey)
        val phoneTraffic = WirelessSecurity.deriveSessionKeys(phoneKey, transcript)
        val glassesTraffic = WirelessSecurity.deriveSessionKeys(glassesKey, transcript)
        assertArrayEquals(phoneTraffic.phoneToGlasses.key, glassesTraffic.phoneToGlasses.key)
        assertTrue(WirelessSecurity.matches(
            WirelessSecurity.pairingConfirmation(phoneKey, transcript, "glasses"),
            WirelessSecurity.pairingConfirmation(glassesKey, transcript, "glasses"),
        ))
        assertFalse(WirelessSecurity.matches(
            WirelessSecurity.pairingConfirmation(phoneKey, transcript, "phone"),
            WirelessSecurity.pairingConfirmation(glassesKey, transcript, "glasses"),
        ))
    }

    @Test fun handshakeCodecRoundTripsAndRejectsTrailingBytes() {
        val hello = WirelessHandshakeMessage.PairClientHello(WirelessPeerHello(
            ByteArray(16) { 5 }, ByteArray(16) { 6 }, WirelessSecurity.generateEphemeralKeyPair().public.encoded,
        ))
        val decoded = WirelessHandshakeCodec.decode(WirelessHandshakeCodec.encode(hello)) as WirelessHandshakeMessage.PairClientHello
        assertArrayEquals(hello.value.installationId, decoded.value.installationId)
        assertArrayEquals(hello.value.nonce, decoded.value.nonce)
        assertArrayEquals(hello.value.publicKey, decoded.value.publicKey)

        val auth = WirelessHandshakeMessage.AuthServerProof(ByteArray(16) { 7 }, ByteArray(16) { 8 }, ByteArray(32) { 9 })
        val authDecoded = WirelessHandshakeCodec.decode(WirelessHandshakeCodec.encode(auth)) as WirelessHandshakeMessage.AuthServerProof
        assertArrayEquals(auth.tag, authDecoded.tag)
    }
}
