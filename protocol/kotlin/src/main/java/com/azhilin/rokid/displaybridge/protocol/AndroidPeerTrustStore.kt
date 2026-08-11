package com.azhilin.rokid.displaybridge.protocol

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class PairedPeer(val installationId: ByteArray, val peerKey: ByteArray) {
    init {
        require(installationId.size == WirelessSecurity.ID_BYTES)
        require(peerKey.size == WirelessSecurity.TAG_BYTES)
    }

    override fun equals(other: Any?): Boolean = other is PairedPeer &&
        installationId.contentEquals(other.installationId) && peerKey.contentEquals(other.peerKey)
    override fun hashCode(): Int = 31 * installationId.contentHashCode() + peerKey.contentHashCode()
}

class AndroidPeerTrustStore(
    context: Context,
    private val namespace: String,
    private val keyProvider: (() -> SecretKey)? = null,
) {
    private val preferences = context.applicationContext.getSharedPreferences("rdb_wireless_trust_$namespace", Context.MODE_PRIVATE)
    private val keyAlias = "rdb-wireless-trust-$namespace-v1"
    private val aad = "RDBW-TRUST-1:$namespace".encodeToByteArray()

    @Synchronized
    fun localInstallationId(): ByteArray {
        preferences.getString(KEY_LOCAL_ID, null)?.let { encoded ->
            runCatching { Base64.decode(encoded, Base64.NO_WRAP) }
                .getOrNull()?.takeIf { it.size == WirelessSecurity.ID_BYTES }?.let { return it }
        }
        val id = WirelessSecurity.randomInstallationId()
        check(preferences.edit().putString(KEY_LOCAL_ID, Base64.encodeToString(id, Base64.NO_WRAP)).commit())
        return id
    }

    @Synchronized
    fun load(): PairedPeer? {
        val encoded = preferences.getString(KEY_PEER, null) ?: return null
        return runCatching { decodePeer(decrypt(Base64.decode(encoded, Base64.NO_WRAP))) }.getOrNull()
    }

    @Synchronized
    fun save(peer: PairedPeer, replace: Boolean = false): Boolean {
        if (!replace && load() != null) return false
        val encrypted = encrypt(encodePeer(peer))
        return preferences.edit().putString(KEY_PEER, Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()
    }

    @Synchronized
    fun forget(): Boolean = preferences.edit().remove(KEY_PEER).commit()

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plain)
        return ByteBuffer.allocate(1 + cipher.iv.size + ciphertext.size)
            .put(cipher.iv.size.toByte()).put(cipher.iv).put(ciphertext).array()
    }

    private fun decrypt(sealed: ByteArray): ByteArray {
        require(sealed.size > 1)
        val buffer = ByteBuffer.wrap(sealed)
        val ivLength = buffer.get().toInt() and 0xff
        require(ivLength in 12..16 && buffer.remaining() > ivLength)
        val iv = ByteArray(ivLength).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        return Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            updateAAD(aad)
            doFinal(ciphertext)
        }
    }

    private fun secretKey(): SecretKey = keyProvider?.invoke() ?: androidKey()

    private fun androidKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            generateKey()
        }
    }

    private fun encodePeer(peer: PairedPeer): ByteArray = ByteBuffer.allocate(1 + WirelessSecurity.ID_BYTES + WirelessSecurity.TAG_BYTES)
        .put(STORE_VERSION).put(peer.installationId).put(peer.peerKey).array()

    private fun decodePeer(bytes: ByteArray): PairedPeer {
        require(bytes.size == 1 + WirelessSecurity.ID_BYTES + WirelessSecurity.TAG_BYTES)
        val buffer = ByteBuffer.wrap(bytes)
        require(buffer.get() == STORE_VERSION)
        return PairedPeer(ByteArray(WirelessSecurity.ID_BYTES).also(buffer::get), ByteArray(WirelessSecurity.TAG_BYTES).also(buffer::get))
    }

    private companion object {
        const val KEY_LOCAL_ID = "local_installation_id"
        const val KEY_PEER = "paired_peer"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val STORE_VERSION: Byte = 1
    }
}
