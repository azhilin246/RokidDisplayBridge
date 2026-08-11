package com.azhilin.rokid.displaybridge.companion.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.azhilin.rokid.displaybridge.companion.BridgeIdentity
import com.azhilin.rokid.displaybridge.protocol.Frame
import com.azhilin.rokid.displaybridge.protocol.FrameCodec
import com.azhilin.rokid.displaybridge.protocol.FrameStreamDecoder
import com.azhilin.rokid.displaybridge.protocol.DbpTransport
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class AndroidAoaHost(private val context: Context, private val listener: Listener) : Closeable {
    interface Listener {
        fun onState(message: String)
        fun onPermissionRequired(stage: UsbPermissionStage, deviceId: Int)
        fun onConnection(connection: AndroidAoaConnection)
        fun onDisconnected(message: String)
        fun onError(message: String)
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val lock = Any()
    private var registered = false
    private var pendingDeviceId: Int? = null
    private var pendingPermissionStage: UsbPermissionStage? = null
    private var deniedDeviceId: Int? = null
    @Volatile private var autoConnectEnabled = false
    @Volatile private var awaitingAoaReenumeration = false
    @Volatile private var connection: AndroidAoaConnection? = null
    var frameListener: ((Frame) -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION -> {
                    @Suppress("DEPRECATION")
                    val reported = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice
                    val expectedId = synchronized(lock) { pendingDeviceId }
                    val devices = usbManager.deviceList.values.toList()
                    val device = reported ?: devices.firstOrNull { it.deviceId == expectedId }
                        ?: runCatching { selectTargetDevice(devices) }.getOrNull()
                    if (device == null) {
                        synchronized(lock) { pendingDeviceId = null; pendingPermissionStage = null }
                        listener.onError("USB permission result arrived after detach")
                        return
                    }
                    if (expectedId != null && device.deviceId != expectedId) {
                        Log.w(TAG, "ignoring stale USB permission result deviceId=${device.deviceId} expected=$expectedId")
                        return
                    }
                    val granted = usbManager.hasPermission(device) ||
                        intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val stage = synchronized(lock) {
                        val value = pendingPermissionStage ?: usbPermissionStage(device.vendorId, device.productId)
                        if (pendingDeviceId == device.deviceId) {
                            pendingDeviceId = null
                            pendingPermissionStage = null
                        }
                        value
                    }
                    if (granted) safelyRequestOrConnect(device)
                    else {
                        synchronized(lock) { deniedDeviceId = device.deviceId }
                        if (stage == UsbPermissionStage.AOA_DATA) awaitingAoaReenumeration = false
                        listener.onError("USB permission denied for ${stage.diagnosticName}")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (shouldConnectOnAttach(autoConnectEnabled, awaitingAoaReenumeration)) connectAttachedDevice()
                    else listener.onState("Rokid USB device attached; open the companion to connect")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    @Suppress("DEPRECATION")
                    val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice
                    val closed = synchronized(lock) {
                        if (device == null || pendingDeviceId == device.deviceId) {
                            pendingDeviceId = null
                            pendingPermissionStage = null
                        }
                        if (device == null || deniedDeviceId == device.deviceId) deniedDeviceId = null
                        connection?.takeIf { device == null || it.deviceId == device.deviceId }?.also { connection = null }
                    }
                    if (closed != null) { closed.close(); listener.onDisconnected("USB device detached") }
                }
            }
        }
    }

    fun connect() {
        registerReceiver()
        synchronized(lock) { deniedDeviceId = null }
        connectAttachedDevice()
    }

    fun disconnect() {
        autoConnectEnabled = false
        awaitingAoaReenumeration = false
        val active = synchronized(lock) {
            pendingDeviceId = null
            pendingPermissionStage = null
            deniedDeviceId = null
            connection.also { connection = null }
        }
        active?.close()
    }

    fun setAutoConnectEnabled(enabled: Boolean, connectImmediately: Boolean = true) {
        autoConnectEnabled = enabled
        registerReceiver()
        if (enabled && connectImmediately) connectAttachedDevice()
    }

    val hasActiveConnectionAttempt: Boolean
        get() = synchronized(lock) { pendingDeviceId != null || awaitingAoaReenumeration || connection?.isOpen == true }

    private fun connectAttachedDevice() {
        val selected = runCatching { selectTargetDevice(usbManager.deviceList.values.toList()) }
            .getOrElse { listener.onError(it.message ?: "Could not select USB device"); return }
        if (selected == null) listener.onState("Waiting for Rokid USB device") else safelyRequestOrConnect(selected)
    }

    private fun safelyRequestOrConnect(device: UsbDevice) {
        runCatching { requestOrConnect(device) }
            .onFailure { listener.onError(it.message ?: "Could not open Rokid USB device") }
    }

    private fun selectTargetDevice(devices: List<UsbDevice>): UsbDevice? {
        val index = selectTargetUsbDevice(devices.map {
            UsbDeviceIdentity(it.vendorId, it.productId, runCatching { it.manufacturerName }.getOrNull(),
                runCatching { it.productName }.getOrNull())
        })
        return index?.let(devices::get)
    }

    private fun requestOrConnect(device: UsbDevice) {
        val hasPermission = usbManager.hasPermission(device)
        val stage = usbPermissionStage(device.vendorId, device.productId)
        synchronized(lock) {
            if (connection?.takeIf { it.deviceId == device.deviceId && it.isOpen } != null) return
            if (!hasPermission && deniedDeviceId == device.deviceId) {
                listener.onState("USB permission was denied for ${stage.diagnosticName}; tap Connect to retry")
                return
            }
            if (pendingDeviceId == device.deviceId && !hasPermission) return
            pendingDeviceId = device.deviceId
            pendingPermissionStage = stage
        }
        if (!hasPermission) {
            Log.i(TAG, "requesting USB permission stage=${stage.name} deviceId=${device.deviceId} " +
                "vidpid=%04x:%04x".format(device.vendorId, device.productId))
            listener.onPermissionRequired(stage, device.deviceId)
            val pendingIntent = PendingIntent.getBroadcast(context, device.deviceId,
                Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
            usbManager.requestPermission(device, pendingIntent)
            return
        }
        if (device.vendorId == AOA_VID && device.productId in AOA_PIDS) openAccessory(device) else startAccessoryMode(device)
    }

    private fun startAccessoryMode(device: UsbDevice) {
        listener.onState("Switching Rokid glasses to bridge AOA identity")
        val handle = usbManager.openDevice(device) ?: throw IOException("cannot open USB device")
        try {
            val protocol = ByteArray(2)
            val count = handle.controlTransfer(0xc0, 51, 0, 0, protocol, 2, CONTROL_TIMEOUT_MS)
            if (count != 2 || ByteBuffer.wrap(protocol).order(ByteOrder.LITTLE_ENDIAN).short.toUShort().toInt() == 0) {
                throw IOException("device did not report Android Open Accessory support")
            }
            BridgeIdentity.strings.forEachIndexed { index, value ->
                val bytes = value.encodeToByteArray() + 0
                val sent = handle.controlTransfer(0x40, 52, 0, index, bytes, bytes.size, CONTROL_TIMEOUT_MS)
                if (sent != bytes.size) throw IOException("short bridge AOA identity write at index $index")
            }
            if (handle.controlTransfer(0x40, 53, 0, 0, null, 0, CONTROL_TIMEOUT_MS) < 0) {
                throw IOException("AOA start request failed")
            }
            awaitingAoaReenumeration = true
        } finally { handle.close() }
        listener.onState("Bridge AOA requested; waiting for re-enumeration")
    }

    private fun openAccessory(device: UsbDevice) {
        listener.onState("Opening bridge AOA bulk endpoints")
        val endpoints = findBulkEndpoints(device) ?: throw IOException("AOA device has no bulk IN/OUT endpoints")
        val handle = usbManager.openDevice(device) ?: throw IOException("cannot open AOA device")
        if (!handle.claimInterface(endpoints.usbInterface, true)) { handle.close(); throw IOException("cannot claim AOA interface") }
        lateinit var candidate: AndroidAoaConnection
        candidate = AndroidAoaConnection(device.deviceId, handle, endpoints.usbInterface, endpoints.input, endpoints.output,
            isAttached = { usbManager.deviceList.values.any { it.deviceId == device.deviceId } },
            listener = object : AndroidAoaConnection.Listener {
                override fun onFrame(frame: Frame) { frameListener?.invoke(frame) }
                override fun onDisconnected(message: String) {
                    val current = synchronized(lock) { if (connection === candidate) { connection = null; true } else false }
                    if (current) listener.onDisconnected(message)
                }
            })
        awaitingAoaReenumeration = false
        val previous = synchronized(lock) {
            val old = connection
            connection = candidate
            pendingDeviceId = null
            pendingPermissionStage = null
            old
        }
        previous?.close()
        candidate.start()
        listener.onConnection(candidate)
        Log.i(TAG, "bridge AOA connection opened deviceId=${device.deviceId}")
    }

    private fun findBulkEndpoints(device: UsbDevice): EndpointSet? {
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            var input: UsbEndpoint? = null
            var output: UsbEndpoint? = null
            for (j in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(j)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (endpoint.direction == UsbConstants.USB_DIR_IN) input = endpoint else output = endpoint
            }
            if (input != null && output != null) return EndpointSet(usbInterface, input, output)
        }
        return null
    }

    private fun registerReceiver() {
        if (registered) return
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION); addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }

    override fun close() {
        disconnect()
        if (registered) { context.unregisterReceiver(receiver); registered = false }
    }

    private data class EndpointSet(val usbInterface: UsbInterface, val input: UsbEndpoint, val output: UsbEndpoint)

    companion object {
        const val ACTION_USB_PERMISSION = "com.azhilin.rokid.displaybridge.companion.action.USB_PERMISSION"
        private const val CONTROL_TIMEOUT_MS = 2_000
        private const val TAG = "RdbAoaHost"
    }
}

enum class UsbPermissionStage(val diagnosticName: String) {
    DEVICE_TO_AOA("device-to-AOA stage"),
    AOA_DATA("AOA data stage"),
}

fun usbPermissionStage(vendorId: Int, productId: Int): UsbPermissionStage =
    if (vendorId == AOA_VID && productId in AOA_PIDS) UsbPermissionStage.AOA_DATA else UsbPermissionStage.DEVICE_TO_AOA

fun shouldConnectOnAttach(autoConnectEnabled: Boolean, awaitingAoaReenumeration: Boolean): Boolean =
    autoConnectEnabled || awaitingAoaReenumeration

data class UsbDeviceIdentity(val vendorId: Int, val productId: Int, val manufacturerName: String?, val productName: String?)

fun selectTargetUsbDevice(devices: List<UsbDeviceIdentity>): Int? {
    if (devices.isEmpty()) return null
    val preferred = devices.withIndex().filter { (_, d) -> d.isRokidOrAoa() }
    return when {
        preferred.size == 1 -> preferred.single().index
        preferred.size > 1 -> throw IllegalArgumentException("Multiple Rokid USB devices are attached")
        devices.size == 1 -> 0
        else -> throw IllegalArgumentException("Multiple USB devices are attached; no Rokid glasses found")
    }
}

private fun UsbDeviceIdentity.isRokidOrAoa(): Boolean = vendorId == AOA_VID && (productId in AOA_PIDS ||
    manufacturerName.orEmpty().contains("rokid", true) || productName.orEmpty().contains("rokid", true) ||
    productName.orEmpty().contains("rg-glasses", true))

private const val AOA_VID = 0x18d1
private val AOA_PIDS = 0x2d00..0x2d05

class AndroidAoaConnection(
    val deviceId: Int,
    private val handle: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val input: UsbEndpoint,
    private val output: UsbEndpoint,
    private val isAttached: () -> Boolean,
    private val listener: Listener,
) : DbpTransport {
    interface Listener { fun onFrame(frame: Frame); fun onDisconnected(message: String) }
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val notified = AtomicBoolean(false)
    private val decoder = FrameStreamDecoder()
    private val writeLock = Any()
    private var worker: Thread? = null
    override val isOpen get() = running.get() && !closed.get()
    override val transportName: String = "USB/AOA"

    fun start() { if (running.compareAndSet(false, true)) worker = Thread(::readLoop, "rdb-phone-usb-reader").apply { start() } }

    override fun send(frame: Frame, completion: ((Boolean) -> Unit)?) {
        val result = runCatching {
            val bytes = FrameCodec.encode(frame)
            synchronized(writeLock) {
                AoaBulkWriter().write(bytes, running::get) { buffer, offset, length ->
                    handle.bulkTransfer(output, buffer, offset, length, 5_000)
                }
            }
        }
        completion?.invoke(result.isSuccess)
        result.exceptionOrNull()?.let { error ->
            disconnect(error.message ?: "AOA bulk write failed")
            throw error
        }
    }

    private fun readLoop() {
        val bytes = ByteArray(64 * 1024)
        try {
            while (running.get()) {
                val count = handle.bulkTransfer(input, bytes, bytes.size, 1_000)
                if (count > 0) decoder.push(bytes.copyOf(count)).forEach(listener::onFrame)
                else if (!isAttached()) throw IOException("AOA USB device detached")
            }
        } catch (error: Exception) { disconnect(error.message ?: "AOA connection ended") }
    }

    override fun close() { running.set(false); worker?.interrupt(); worker = null; closeResources() }
    private fun disconnect(message: String) {
        val active = running.getAndSet(false); worker?.interrupt(); closeResources()
        if (active && notified.compareAndSet(false, true)) listener.onDisconnected(message)
    }
    private fun closeResources() { if (closed.compareAndSet(false, true)) { runCatching { handle.releaseInterface(usbInterface) }; handle.close() } }
}

class AoaBulkWriter(private val maxChunkSize: Int = 16 * 1024, private val maxAttemptsPerChunk: Int = 2) {
    init { require(maxChunkSize > 0 && maxAttemptsPerChunk > 0) }

    fun write(bytes: ByteArray, isOpen: () -> Boolean, transfer: (ByteArray, Int, Int) -> Int) {
        var offset = 0
        while (offset < bytes.size) {
            if (!isOpen()) throw IOException("AOA connection is closed")
            val requested = minOf(maxChunkSize, bytes.size - offset)
            var attempts = 0
            while (true) {
                val written = transfer(bytes, offset, requested)
                if (written in 1..requested) { offset += written; break }
                if (written > requested) throw IOException("AOA bulk write returned invalid length $written")
                attempts += 1
                if (attempts >= maxAttemptsPerChunk) throw IOException("AOA bulk write failed at offset $offset")
            }
        }
    }
}
