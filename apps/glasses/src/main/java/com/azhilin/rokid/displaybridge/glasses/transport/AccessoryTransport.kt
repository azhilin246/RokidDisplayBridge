package com.azhilin.rokid.displaybridge.glasses.transport

import android.hardware.usb.UsbAccessory
import android.os.ParcelFileDescriptor
import android.util.Log
import com.azhilin.rokid.displaybridge.protocol.Frame
import com.azhilin.rokid.displaybridge.protocol.FrameCodec
import com.azhilin.rokid.displaybridge.protocol.FrameStreamDecoder
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AccessoryTransport(
    val accessory: UsbAccessory,
    private val descriptor: ParcelFileDescriptor,
    private val listener: Listener,
) : Closeable {
    interface Listener {
        fun onFrame(frame: Frame)
        fun onDisconnected(message: String)
        fun onError(message: String)
    }

    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val input = FileInputStream(descriptor.fileDescriptor)
    private val output = FileOutputStream(descriptor.fileDescriptor)
    private val decoder = FrameStreamDecoder()
    private val writes = Executors.newSingleThreadExecutor { task -> Thread(task, "rdb-glasses-usb-writer") }
    private var reader: Thread? = null

    fun start() {
        if (running.compareAndSet(false, true)) reader = Thread(::readLoop, "rdb-glasses-usb-reader").apply { start() }
    }

    fun matches(other: UsbAccessory) = accessory == other

    fun send(frame: Frame) {
        if (!running.get()) return
        writes.execute {
            if (!running.get()) return@execute
            runCatching {
                output.write(FrameCodec.encode(frame))
                output.flush()
            }.onFailure { disconnect(it.message ?: "DBP1 accessory write failed") }
        }
    }

    private fun readLoop() {
        val buffer = ByteArray(64 * 1024)
        try {
            while (running.get()) {
                val count = input.read(buffer)
                if (count < 0) throw IOException("USB accessory reached end of stream")
                if (count > 0) decoder.push(buffer.copyOf(count)).forEach(listener::onFrame)
            }
        } catch (error: Exception) {
            if (running.get()) listener.onError(error.message ?: error.javaClass.simpleName)
        } finally {
            disconnect("Bridge accessory disconnected")
        }
    }

    override fun close() = disconnect("Bridge accessory closed")

    private fun disconnect(message: String) {
        val active = running.getAndSet(false)
        reader?.interrupt(); reader = null
        if (closed.compareAndSet(false, true)) {
            writes.shutdownNow()
            runCatching { input.close() }; runCatching { output.close() }; runCatching { descriptor.close() }
        }
        if (active) {
            Log.i(TAG, message)
            listener.onDisconnected(message)
        }
    }

    companion object { private const val TAG = "RdbAccessory" }
}
