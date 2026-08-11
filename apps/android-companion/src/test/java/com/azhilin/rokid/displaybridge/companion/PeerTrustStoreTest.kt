package com.azhilin.rokid.displaybridge.companion

import androidx.test.core.app.ApplicationProvider
import com.azhilin.rokid.displaybridge.protocol.AndroidPeerTrustStore
import com.azhilin.rokid.displaybridge.protocol.PairedPeer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
class PeerTrustStoreTest {
    @Test fun storeEncryptsSinglePeerRequiresExplicitReplaceAndForgets() {
        val key = SecretKeySpec(ByteArray(32) { 9 }, "AES")
        val store = AndroidPeerTrustStore(
            ApplicationProvider.getApplicationContext(),
            "test-${UUID.randomUUID()}",
        ) { key }
        val local = store.localInstallationId()
        assertEquals(16, local.size)
        assertArrayEquals(local, store.localInstallationId())

        val first = PairedPeer(ByteArray(16) { 1 }, ByteArray(32) { 2 })
        val second = PairedPeer(ByteArray(16) { 3 }, ByteArray(32) { 4 })
        assertTrue(store.save(first))
        assertEquals(first, store.load())
        assertFalse(store.save(second))
        assertEquals(first, store.load())
        assertTrue(store.save(second, replace = true))
        assertEquals(second, store.load())
        assertTrue(store.forget())
        assertNull(store.load())
    }
}
