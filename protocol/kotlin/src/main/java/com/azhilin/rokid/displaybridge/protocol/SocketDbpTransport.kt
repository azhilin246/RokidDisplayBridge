package com.azhilin.rokid.displaybridge.protocol

import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SocketDbpTransport(
    private val socket: Socket,
    private val listener: DbpTransport.Listener,
    outboundKey: WirelessTrafficKey,
    inboundKey: WirelessTrafficKey,
    override val transportName: String = "Wi-Fi Direct/TCP",
    private val writeStallMs: Long = DEFAULT_WRITE_STALL_MS,
) : DbpTransport {
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val notified = AtomicBoolean(false)
    private val encoder = SecureRecordCodec(outboundKey)
    private val decoder = SecureRecordStreamDecoder(inboundKey)
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()
    private val writes = Executors.newSingleThreadExecutor { task -> Thread(task, "rdb-wireless-writer") }
    private val watchdog = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "rdb-wireless-watchdog") }
    private var reader: Thread? = null

    init {
        require(writeStallMs > 0)
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.soTimeout = READ_POLL_MS
        socket.sendBufferSize = minOf(socket.sendBufferSize, SOCKET_BUFFER_BYTES)
        socket.receiveBufferSize = minOf(socket.receiveBufferSize, SOCKET_BUFFER_BYTES)
    }

    override val isOpen: Boolean get() = running.get() && !closed.get() && socket.isConnected

    fun start() {
        if (running.compareAndSet(false, true)) {
            reader = Thread(::readLoop, "rdb-wireless-reader").apply { start() }
        }
    }

    override fun send(frame: Frame, completion: ((Boolean) -> Unit)?) {
        if (!isOpen) {
            completion?.invoke(false)
            return
        }
        runCatching {
            writes.execute {
                if (!isOpen) {
                    completion?.invoke(false)
                    return@execute
                }
                var timeout: ScheduledFuture<*>? = null
                val result = runCatching {
                    timeout = watchdog.schedule(
                        { disconnect("Wireless TCP write stalled") },
                        writeStallMs,
                        TimeUnit.MILLISECONDS,
                    )
                    output.write(encoder.encode(FrameCodec.encode(frame)))
                    output.flush()
                }
                timeout?.cancel(false)
                completion?.invoke(result.isSuccess)
                result.exceptionOrNull()?.let { disconnect(it.message ?: "Wireless TCP write failed") }
            }
        }.onFailure {
            completion?.invoke(false)
            disconnect(it.message ?: "Wireless TCP write scheduling failed")
        }
    }

    private fun readLoop() {
        val bytes = ByteArray(64 * 1024)
        try {
            while (running.get()) {
                try {
                    val count = input.read(bytes)
                    if (count < 0) throw IOException("Wireless TCP peer closed")
                    if (count > 0) decoder.push(bytes.copyOf(count)).map(FrameCodec::decode).forEach(listener::onFrame)
                } catch (_: SocketTimeoutException) {
                    // Bounded poll permits close and detach checks without treating idle DBP1 as failure.
                }
            }
        } catch (error: Exception) {
            if (running.get()) listener.onError(error.message ?: error.javaClass.simpleName)
        } finally {
            disconnect("Wireless TCP disconnected")
        }
    }

    override fun close() = disconnect("Wireless TCP closed")

    private fun disconnect(message: String) {
        val active = running.getAndSet(false)
        reader?.interrupt()
        reader = null
        if (closed.compareAndSet(false, true)) {
            runCatching { socket.close() }
            writes.shutdownNow()
            watchdog.shutdownNow()
        }
        if (active && notified.compareAndSet(false, true)) listener.onDisconnected(message)
    }

    companion object {
        const val DEFAULT_WRITE_STALL_MS = 5_000L
        private const val READ_POLL_MS = 1_000
        private const val SOCKET_BUFFER_BYTES = 128 * 1024
    }
}
