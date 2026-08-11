package com.azhilin.rokid.displaybridge.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SecureRecordCodec(private val trafficKey: WirelessTrafficKey) {
    private var sequence = 0L

    @Synchronized
    fun encode(plaintext: ByteArray): ByteArray {
        require(plaintext.size in 1..MAX_PLAINTEXT_BYTES) { "Wireless record plaintext length is invalid" }
        check(sequence >= 0) { "Wireless record sequence exhausted" }
        val ciphertextLength = plaintext.size + GCM_TAG_BYTES
        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC)
            .putLong(sequence)
            .putInt(ciphertextLength)
            .array()
        val cipher = Cipher.getInstance(CIPHER).apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(trafficKey.key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce(sequence)))
            updateAAD(header)
        }
        val ciphertext = cipher.doFinal(plaintext)
        sequence += 1
        return header + ciphertext
    }

    private fun nonce(value: Long): ByteArray = ByteBuffer.allocate(NONCE_BYTES).order(ByteOrder.BIG_ENDIAN)
        .put(trafficKey.noncePrefix)
        .putLong(value)
        .array()

    companion object {
        internal val MAGIC = byteArrayOf('R'.code.toByte(), 'D'.code.toByte(), 'B'.code.toByte(), 'E'.code.toByte())
        internal const val HEADER_BYTES = 16
        internal const val GCM_TAG_BYTES = 16
        internal const val MAX_PLAINTEXT_BYTES = DBP_HEADER_SIZE + DBP_MAX_PAYLOAD_SIZE
        internal const val MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_BYTES + GCM_TAG_BYTES
        private const val NONCE_BYTES = 12
        private const val GCM_TAG_BITS = GCM_TAG_BYTES * 8
        private const val CIPHER = "AES/GCM/NoPadding"

        internal fun decrypt(
            trafficKey: WirelessTrafficKey,
            header: ByteArray,
            sequence: Long,
            ciphertext: ByteArray,
        ): ByteArray = try {
            Cipher.getInstance(CIPHER).run {
                val nonce = ByteBuffer.allocate(NONCE_BYTES).order(ByteOrder.BIG_ENDIAN)
                    .put(trafficKey.noncePrefix)
                    .putLong(sequence)
                    .array()
                init(Cipher.DECRYPT_MODE, SecretKeySpec(trafficKey.key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
                updateAAD(header)
                doFinal(ciphertext)
            }
        } catch (_: AEADBadTagException) {
            throw SecurityException("Wireless record authentication failed")
        }
    }
}

class SecureRecordStreamDecoder(private val trafficKey: WirelessTrafficKey) {
    private val buffer = ByteArray(SecureRecordCodec.HEADER_BYTES + SecureRecordCodec.MAX_CIPHERTEXT_BYTES)
    private var bufferedBytes = 0
    private var expectedRecordBytes = 0
    private var expectedSequence = 0L

    @Synchronized
    fun push(chunk: ByteArray): List<ByteArray> {
        require(chunk.isNotEmpty())
        val records = mutableListOf<ByteArray>()
        var offset = 0
        try {
            while (offset < chunk.size) {
                if (expectedRecordBytes == 0) {
                    val count = minOf(SecureRecordCodec.HEADER_BYTES - bufferedBytes, chunk.size - offset)
                    chunk.copyInto(buffer, bufferedBytes, offset, offset + count)
                    bufferedBytes += count
                    offset += count
                    if (bufferedBytes < SecureRecordCodec.HEADER_BYTES) continue
                    expectedRecordBytes = recordLengthFromHeader()
                }
                val count = minOf(expectedRecordBytes - bufferedBytes, chunk.size - offset)
                if (count > 0) {
                    chunk.copyInto(buffer, bufferedBytes, offset, offset + count)
                    bufferedBytes += count
                    offset += count
                }
                if (bufferedBytes == expectedRecordBytes) {
                    val header = buffer.copyOfRange(0, SecureRecordCodec.HEADER_BYTES)
                    val ciphertext = buffer.copyOfRange(SecureRecordCodec.HEADER_BYTES, expectedRecordBytes)
                    records += SecureRecordCodec.decrypt(trafficKey, header, expectedSequence, ciphertext)
                    expectedSequence += 1
                    bufferedBytes = 0
                    expectedRecordBytes = 0
                }
            }
        } catch (error: Exception) {
            bufferedBytes = 0
            expectedRecordBytes = 0
            throw error
        }
        return records
    }

    private fun recordLengthFromHeader(): Int {
        val header = ByteBuffer.wrap(buffer, 0, SecureRecordCodec.HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(4).also(header::get)
        require(magic.contentEquals(SecureRecordCodec.MAGIC)) { "Wireless record marker is invalid" }
        val sequence = header.long
        require(sequence == expectedSequence && sequence >= 0) { "Wireless record sequence is invalid" }
        val ciphertextLength = header.int
        require(ciphertextLength in SecureRecordCodec.GCM_TAG_BYTES + 1..SecureRecordCodec.MAX_CIPHERTEXT_BYTES) {
            "Wireless record length is invalid"
        }
        return SecureRecordCodec.HEADER_BYTES + ciphertextLength
    }
}
