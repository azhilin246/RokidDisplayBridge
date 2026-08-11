package com.azhilin.rokid.displaybridge.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class WirelessTrafficKey(val key: ByteArray, val noncePrefix: ByteArray) {
    init {
        require(key.size == WirelessSecurity.TRAFFIC_KEY_BYTES)
        require(noncePrefix.size == WirelessSecurity.NONCE_PREFIX_BYTES)
    }
}

data class WirelessSessionKeys(
    val phoneToGlasses: WirelessTrafficKey,
    val glassesToPhone: WirelessTrafficKey,
)

data class WirelessPeerHello(
    val installationId: ByteArray,
    val nonce: ByteArray,
    val publicKey: ByteArray,
) {
    init {
        require(installationId.size == WirelessSecurity.ID_BYTES)
        require(nonce.size == WirelessSecurity.NONCE_BYTES)
        require(publicKey.isNotEmpty() && publicKey.size <= WirelessHandshakeCodec.MAX_PUBLIC_KEY_BYTES)
    }
}

sealed interface WirelessHandshakeMessage {
    data class PairClientHello(val value: WirelessPeerHello) : WirelessHandshakeMessage
    data class PairServerHello(val value: WirelessPeerHello) : WirelessHandshakeMessage
    data class PairConfirm(val tag: ByteArray) : WirelessHandshakeMessage
    data class PairAccept(val tag: ByteArray) : WirelessHandshakeMessage
    data class AuthClientHello(val installationId: ByteArray, val nonce: ByteArray) : WirelessHandshakeMessage
    data class AuthServerProof(
        val installationId: ByteArray,
        val nonce: ByteArray,
        val tag: ByteArray,
    ) : WirelessHandshakeMessage
    data class AuthClientProof(val tag: ByteArray) : WirelessHandshakeMessage
    data class AuthAccept(val tag: ByteArray) : WirelessHandshakeMessage
    data class Reject(val reason: UByte) : WirelessHandshakeMessage
}

object WirelessSecurity {
    const val ID_BYTES = 16
    const val NONCE_BYTES = 16
    const val TAG_BYTES = 32
    const val TRAFFIC_KEY_BYTES = 32
    const val NONCE_PREFIX_BYTES = 4
    private const val CURVE = "secp256r1"
    private const val HMAC = "HmacSHA256"
    private val random = SecureRandom()

    fun randomInstallationId(): ByteArray = randomBytes(ID_BYTES)
    fun randomNonce(): ByteArray = randomBytes(NONCE_BYTES)
    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    fun generateEphemeralKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec(CURVE), random)
    }.generateKeyPair()

    fun decodePublicKey(encoded: ByteArray): PublicKey {
        require(encoded.isNotEmpty() && encoded.size <= WirelessHandshakeCodec.MAX_PUBLIC_KEY_BYTES)
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
    }

    fun sharedSecret(privateKey: PrivateKey, peerPublicKey: ByteArray): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(decodePublicKey(peerPublicKey), true)
            generateSecret()
        }

    fun pairingTranscript(phone: WirelessPeerHello, glasses: WirelessPeerHello): ByteArray = sha256(
        "RDBW-PAIR-1".encodeToByteArray(),
        phone.installationId,
        glasses.installationId,
        phone.nonce,
        glasses.nonce,
        phone.publicKey,
        glasses.publicKey,
    )

    fun derivePairingKey(sharedSecret: ByteArray, transcript: ByteArray): ByteArray =
        hkdf(sharedSecret, transcript, "RDBW pairing key")

    fun derivePeerKey(pairingKey: ByteArray, transcript: ByteArray): ByteArray =
        hkdf(pairingKey, transcript, "RDBW stored peer key")

    fun pairingConfirmation(pairingKey: ByteArray, transcript: ByteArray, role: String): ByteArray =
        hmac(pairingKey, "RDBW pair confirm $role".encodeToByteArray(), transcript)

    fun deriveSessionKeys(secret: ByteArray, transcript: ByteArray): WirelessSessionKeys = WirelessSessionKeys(
        phoneToGlasses = trafficKey(secret, transcript, "phone-to-glasses"),
        glassesToPhone = trafficKey(secret, transcript, "glasses-to-phone"),
    )

    fun authTranscript(
        phoneId: ByteArray,
        glassesId: ByteArray,
        phoneNonce: ByteArray,
        glassesNonce: ByteArray,
    ): ByteArray {
        require(phoneId.size == ID_BYTES && glassesId.size == ID_BYTES)
        require(phoneNonce.size == NONCE_BYTES && glassesNonce.size == NONCE_BYTES)
        return sha256("RDBW-AUTH-1".encodeToByteArray(), phoneId, glassesId, phoneNonce, glassesNonce)
    }

    fun authProof(peerKey: ByteArray, transcript: ByteArray, role: String): ByteArray =
        hmac(peerKey, "RDBW auth proof $role".encodeToByteArray(), transcript)

    fun matches(expected: ByteArray, actual: ByteArray): Boolean =
        expected.size == actual.size && MessageDigest.isEqual(expected, actual)

    private fun hkdf(input: ByteArray, salt: ByteArray, label: String): ByteArray {
        val extracted = hmac(salt, input)
        return hmac(extracted, label.encodeToByteArray(), byteArrayOf(1))
    }

    private fun trafficKey(secret: ByteArray, transcript: ByteArray, direction: String): WirelessTrafficKey =
        WirelessTrafficKey(
            key = hkdf(secret, transcript, "RDBW traffic key $direction"),
            noncePrefix = hkdf(secret, transcript, "RDBW nonce prefix $direction").copyOf(NONCE_PREFIX_BYTES),
        )

    private fun sha256(vararg pieces: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
        pieces.forEach(::update)
        digest()
    }

    private fun hmac(key: ByteArray, vararg pieces: ByteArray): ByteArray = Mac.getInstance(HMAC).run {
        init(SecretKeySpec(key, HMAC))
        pieces.forEach(::update)
        doFinal()
    }
}

object WirelessHandshakeCodec {
    const val MAX_PUBLIC_KEY_BYTES = 256
    const val MAX_MESSAGE_BYTES = 512
    private val magic = byteArrayOf('R'.code.toByte(), 'D'.code.toByte(), 'B'.code.toByte(), 'W'.code.toByte())
    private const val VERSION: UByte = 2u

    fun write(output: OutputStream, message: WirelessHandshakeMessage) {
        val payload = encodePayload(message)
        require(payload.size <= MAX_MESSAGE_BYTES)
        DataOutputStream(output).apply {
            write(magic)
            writeByte(VERSION.toInt())
            writeByte(typeOf(message))
            writeShort(payload.size)
            write(payload)
            flush()
        }
    }

    fun read(input: InputStream): WirelessHandshakeMessage {
        val stream = DataInputStream(input)
        val actualMagic = ByteArray(magic.size).also(stream::readFully)
        require(actualMagic.contentEquals(magic)) { "wireless handshake magic mismatch" }
        require(stream.readUnsignedByte() == VERSION.toInt()) { "unsupported wireless handshake version" }
        val type = stream.readUnsignedByte()
        val length = stream.readUnsignedShort()
        require(length <= MAX_MESSAGE_BYTES) { "wireless handshake payload too large" }
        val payload = ByteArray(length).also(stream::readFully)
        return decodePayload(type, DataInputStream(ByteArrayInputStream(payload)))
    }

    fun encode(message: WirelessHandshakeMessage): ByteArray = ByteArrayOutputStream().also { write(it, message) }.toByteArray()
    fun decode(bytes: ByteArray): WirelessHandshakeMessage = read(ByteArrayInputStream(bytes))

    private fun encodePayload(message: WirelessHandshakeMessage): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            when (message) {
                is WirelessHandshakeMessage.PairClientHello -> output.writeHello(message.value)
                is WirelessHandshakeMessage.PairServerHello -> output.writeHello(message.value)
                is WirelessHandshakeMessage.PairConfirm -> output.writeTag(message.tag)
                is WirelessHandshakeMessage.PairAccept -> output.writeTag(message.tag)
                is WirelessHandshakeMessage.AuthClientHello -> {
                    output.writeFixed(message.installationId, WirelessSecurity.ID_BYTES)
                    output.writeFixed(message.nonce, WirelessSecurity.NONCE_BYTES)
                }
                is WirelessHandshakeMessage.AuthServerProof -> {
                    output.writeFixed(message.installationId, WirelessSecurity.ID_BYTES)
                    output.writeFixed(message.nonce, WirelessSecurity.NONCE_BYTES)
                    output.writeTag(message.tag)
                }
                is WirelessHandshakeMessage.AuthClientProof -> output.writeTag(message.tag)
                is WirelessHandshakeMessage.AuthAccept -> output.writeTag(message.tag)
                is WirelessHandshakeMessage.Reject -> output.writeByte(message.reason.toInt())
            }
        }
    }.toByteArray()

    private fun decodePayload(type: Int, input: DataInputStream): WirelessHandshakeMessage {
        val message = when (type) {
            1 -> WirelessHandshakeMessage.PairClientHello(input.readHello())
            2 -> WirelessHandshakeMessage.PairServerHello(input.readHello())
            3 -> WirelessHandshakeMessage.PairConfirm(input.readTag())
            4 -> WirelessHandshakeMessage.PairAccept(input.readTag())
            5 -> WirelessHandshakeMessage.AuthClientHello(input.readFixed(WirelessSecurity.ID_BYTES), input.readFixed(WirelessSecurity.NONCE_BYTES))
            6 -> WirelessHandshakeMessage.AuthServerProof(
                input.readFixed(WirelessSecurity.ID_BYTES),
                input.readFixed(WirelessSecurity.NONCE_BYTES),
                input.readTag(),
            )
            7 -> WirelessHandshakeMessage.AuthClientProof(input.readTag())
            8 -> WirelessHandshakeMessage.AuthAccept(input.readTag())
            0x7f -> WirelessHandshakeMessage.Reject(input.readUnsignedByte().toUByte())
            else -> error("unknown wireless handshake message type $type")
        }
        require(input.available() == 0) { "trailing wireless handshake bytes" }
        return message
    }

    private fun typeOf(message: WirelessHandshakeMessage): Int = when (message) {
        is WirelessHandshakeMessage.PairClientHello -> 1
        is WirelessHandshakeMessage.PairServerHello -> 2
        is WirelessHandshakeMessage.PairConfirm -> 3
        is WirelessHandshakeMessage.PairAccept -> 4
        is WirelessHandshakeMessage.AuthClientHello -> 5
        is WirelessHandshakeMessage.AuthServerProof -> 6
        is WirelessHandshakeMessage.AuthClientProof -> 7
        is WirelessHandshakeMessage.AuthAccept -> 8
        is WirelessHandshakeMessage.Reject -> 0x7f
    }

    private fun DataOutputStream.writeHello(value: WirelessPeerHello) {
        writeFixed(value.installationId, WirelessSecurity.ID_BYTES)
        writeFixed(value.nonce, WirelessSecurity.NONCE_BYTES)
        writeShort(value.publicKey.size)
        write(value.publicKey)
    }

    private fun DataInputStream.readHello(): WirelessPeerHello {
        val id = readFixed(WirelessSecurity.ID_BYTES)
        val nonce = readFixed(WirelessSecurity.NONCE_BYTES)
        val keyLength = readUnsignedShort()
        require(keyLength in 1..MAX_PUBLIC_KEY_BYTES) { "invalid wireless public key length" }
        return WirelessPeerHello(id, nonce, ByteArray(keyLength).also(::readFully))
    }

    private fun DataOutputStream.writeTag(tag: ByteArray) = writeFixed(tag, WirelessSecurity.TAG_BYTES)
    private fun DataInputStream.readTag(): ByteArray = readFixed(WirelessSecurity.TAG_BYTES)
    private fun DataOutputStream.writeFixed(value: ByteArray, size: Int) { require(value.size == size); write(value) }
    private fun DataInputStream.readFixed(size: Int): ByteArray = ByteArray(size).also(::readFully)
}
