package com.azhilin.rokid.displaybridge.glasses.wireless

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Handler
import androidx.core.content.ContextCompat
import com.azhilin.rokid.displaybridge.protocol.AndroidPeerTrustStore
import com.azhilin.rokid.displaybridge.protocol.DbpTransport
import com.azhilin.rokid.displaybridge.protocol.SocketDbpTransport
import com.azhilin.rokid.displaybridge.protocol.WirelessHandshakeSessions
import java.io.Closeable
import java.net.BindException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class GlassesWirelessClient(
    context: Context,
    private val trustStore: AndroidPeerTrustStore,
    private val listener: Listener,
) : Closeable {
    interface Listener : DbpTransport.Listener {
        fun onWirelessState(state: GlassesWirelessStage, detail: String)
        fun onWirelessConnection(connection: DbpTransport)
    }

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(WifiP2pManager::class.java)
        ?: error("Wi-Fi Direct is unavailable")
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val channel = manager.initialize(appContext, appContext.mainLooper) { fail("Wi-Fi Direct channel disconnected") }
    private val retryHandler = Handler(appContext.mainLooper)
    private val running = AtomicBoolean(false)
    @Volatile private var pairing = false
    @Volatile private var receiverRegistered = false
    @Volatile private var connectedGroup = false
    @Volatile private var joiningGroup = false
    @Volatile private var transportEstablished = false
    @Volatile private var matchingDevice: WifiP2pDevice? = null
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var candidateSocket: Socket? = null
    @Volatile private var worker: Thread? = null
    private val discoveryRefresh = Runnable {
        if (shouldRefreshDiscovery(running.get(), connectedGroup, joiningGroup, transportEstablished)) {
            listener.onWirelessState(GlassesWirelessStage.DISCOVERING, "Refreshing paired phone discovery")
            start(pairing)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED && running.get()) fail("Wi-Fi Direct is disabled or unavailable")
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val network = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO) as? NetworkInfo
                    if (network?.isConnected == true && running.get()) {
                        joiningGroup = false
                        connectedGroup = true
                        manager.requestConnectionInfo(channel, ::onConnectionInfo)
                    } else if (running.get() && !transportEstablished && (connectedGroup || joiningGroup)) {
                        connectedGroup = false
                        joiningGroup = false
                        scheduleDiscoveryRefresh(DISCOVERY_RESTART_DELAY_MS)
                    }
                }
                WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1)
                    if (state == WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED) {
                        scheduleDiscoveryRefresh(DISCOVERY_RESTART_DELAY_MS)
                    }
                }
            }
        }
    }

    fun hasPairedPhone(): Boolean = trustStore.load() != null
    fun isRunning(): Boolean = running.get()
    fun forgetPairedPhone(): Boolean = trustStore.forget()

    fun startPairing() {
        if (trustStore.load() != null) error("Forget the paired phone before pairing a replacement")
        start(pairing = true)
    }

    fun startConnect() {
        check(trustStore.load() != null) { "No paired phone; tap to start wireless pairing" }
        start(pairing = false)
    }

    private fun start(pairing: Boolean) {
        check(hasRequiredPermission(appContext)) { "Nearby/location permission is required for Wi-Fi Direct" }
        stopInternal(removeGroup = false)
        this.pairing = pairing
        running.set(true)
        transportEstablished = false
        registerReceiver()
        listener.onWirelessState(GlassesWirelessStage.DISCOVERING, if (pairing) "Searching for phone pairing service" else "Searching for paired phone")
        prepareExistingGroupOrDiscovery()
    }

    @SuppressLint("MissingPermission") // start() performs the version-aware runtime permission check.
    private fun prepareExistingGroupOrDiscovery() {
        manager.requestConnectionInfo(channel) { info ->
            if (!running.get()) return@requestConnectionInfo
            if (info?.groupFormed == true) {
                connectedGroup = true
                listener.onWirelessState(GlassesWirelessStage.GROUP_FORMING, "Resetting stale Wi-Fi Direct group")
                resetExistingGroup()
            } else beginDiscovery()
        }
    }

    @SuppressLint("MissingPermission") // start() performs the version-aware runtime permission check.
    private fun resetExistingGroup(attempt: Int = 0) {
        if (!running.get()) return
        manager.removeGroup(channel, object : Action("reset stale Wi-Fi Direct group") {
            override fun onSuccess() {
                connectedGroup = false
                beginDiscovery()
            }

            override fun onFailure(reason: Int) {
                if (!retryBusy(reason, attempt, "stale group reset") {
                        resetExistingGroup(attempt + 1)
                    }) super.onFailure(reason)
            }
        })
    }

    @SuppressLint("MissingPermission") // start() performs the version-aware runtime permission check.
    private fun beginDiscovery(attempt: Int = 0) {
        if (!running.get()) return
        listener.onWirelessState(GlassesWirelessStage.DISCOVERING, "Activating Wi-Fi Direct discovery")
        manager.discoverPeers(channel, object : Action("activate Wi-Fi Direct discovery") {
            override fun onSuccess() = prepareServiceDiscovery()
            override fun onFailure(reason: Int) {
                if (!retryBusy(reason, attempt, "P2P activation") {
                        beginDiscovery(attempt + 1)
                    }) super.onFailure(reason)
            }
        })
    }

    @SuppressLint("MissingPermission") // beginDiscovery() activates P2P after the runtime permission check.
    private fun prepareServiceDiscovery(attempt: Int = 0) {
        if (!running.get()) return
        manager.setDnsSdResponseListeners(
            channel,
            { instance, registrationType, device ->
                if (running.get() && instance == SERVICE_INSTANCE && registrationType.startsWith(SERVICE_TYPE)) {
                    matchingDevice = device
                    connect(device)
                }
            },
            { _, record, device ->
                if (running.get() && record["protocol"] == PROTOCOL_VERSION && record["port"] == PORT.toString()) {
                    matchingDevice = device
                }
            },
        )
        // The two-argument overload requests only the exact TXT record. We need the
        // service-type PTR response because that is what invokes the service callback
        // above and gives us the advertising phone to connect to.
        val request = WifiP2pDnsSdServiceRequest.newInstance(SERVICE_TYPE)
        manager.clearServiceRequests(channel, object : Action("clear stale service discovery") {
            override fun onSuccess() {
                if (!running.get()) return
                manager.addServiceRequest(channel, request, object : Action("add Display Bridge discovery request") {
                    override fun onSuccess() {
                        if (!running.get()) return
                        manager.discoverServices(channel, object : Action("discover Display Bridge wireless service") {
                            override fun onSuccess() {
                                listener.onWirelessState(GlassesWirelessStage.DISCOVERING, "Searching for Display Bridge phone")
                                scheduleDiscoveryRefresh(DISCOVERY_REFRESH_MS)
                            }
                            override fun onFailure(reason: Int) {
                                if (!retryBusy(reason, attempt, "service discovery") {
                                        prepareServiceDiscovery(attempt + 1)
                                    }) super.onFailure(reason)
                            }
                        })
                    }
                    override fun onFailure(reason: Int) {
                        if (!retryBusy(reason, attempt, "service discovery setup") {
                                prepareServiceDiscovery(attempt + 1)
                            }) super.onFailure(reason)
                    }
                })
            }
            override fun onFailure(reason: Int) {
                if (!retryBusy(reason, attempt, "service discovery cleanup") {
                        prepareServiceDiscovery(attempt + 1)
                    }) super.onFailure(reason)
            }
        })
    }

    @SuppressLint("MissingPermission") // Only reachable after start() accepts the runtime permission.
    private fun connect(device: WifiP2pDevice, attempt: Int = 0) {
        if (!running.get() || worker?.isAlive == true || connectedGroup || joiningGroup) return
        joiningGroup = true
        listener.onWirelessState(GlassesWirelessStage.GROUP_FORMING, "Joining Display Bridge Wi-Fi Direct group")
        val config = createClientConfig(device)
        manager.connect(channel, config, object : Action("join Display Bridge Wi-Fi Direct group") {
            override fun onFailure(reason: Int) {
                joiningGroup = false
                if (!retryGroupJoin(reason, attempt) {
                        connect(device, attempt + 1)
                    }) super.onFailure(reason)
            }
        })
    }

    private fun retryGroupJoin(reason: Int, attempt: Int, retry: () -> Unit): Boolean {
        if (!shouldRetryGroupJoin(reason, attempt) || !running.get()) return false
        listener.onWirelessState(
            GlassesWirelessStage.GROUP_FORMING,
            "Wi-Fi Direct group join was rejected by the framework; retrying",
        )
        retryHandler.postDelayed({
            if (!running.get() || connectedGroup || worker?.isAlive == true) return@postDelayed
            manager.requestConnectionInfo(channel) { info ->
                if (!running.get() || connectedGroup || worker?.isAlive == true) return@requestConnectionInfo
                if (info?.groupFormed == true) {
                    connectedGroup = true
                    joiningGroup = false
                    onConnectionInfo(info)
                } else retry()
            }
        }, GROUP_JOIN_RETRY_DELAY_MS)
        return true
    }

    private fun retryBusy(reason: Int, attempt: Int, stage: String, retry: () -> Unit): Boolean {
        if (!shouldRetryBusy(reason, attempt) || !running.get()) return false
        listener.onWirelessState(GlassesWirelessStage.DISCOVERING, "Wi-Fi Direct is busy during $stage; retrying")
        retryHandler.postDelayed({ if (running.get()) retry() }, BUSY_RETRY_DELAY_MS)
        return true
    }

    private fun onConnectionInfo(info: WifiP2pInfo?) {
        if (!running.get() || info?.groupFormed != true || worker?.isAlive == true) return
        retryHandler.removeCallbacks(discoveryRefresh)
        val address = info.groupOwnerAddress ?: return fail("Wi-Fi Direct phone address is missing")
        val hostAddress = address.hostAddress ?: address.hostName
        if (info.isGroupOwner) {
            listener.onWirelessState(GlassesWirelessStage.TCP_CONNECTING, "Phone joined; opening authenticated TCP listener")
            worker = Thread({ acceptSocket(hostAddress) }, "rdb-wireless-glasses-host").apply { start() }
        } else {
            listener.onWirelessState(GlassesWirelessStage.TCP_CONNECTING, "Opening authenticated TCP connection")
            worker = Thread({ connectSocket(hostAddress) }, "rdb-wireless-glasses-client").apply { start() }
        }
    }

    private fun connectSocket(hostAddress: String) {
        runCatching { authenticate(connectWhenServerReady(hostAddress)) }.onFailure { error ->
            if (running.get()) fail(error.message ?: "Wireless glasses client failed")
        }
    }

    private fun acceptSocket(hostAddress: String) {
        runCatching {
            val server = bindServerWhenAddressReady(hostAddress)
            serverSocket = server
            authenticate(server.accept().apply { soTimeout = HANDSHAKE_TIMEOUT_MS })
        }.onFailure { error ->
            if (running.get()) fail(error.message ?: "Wireless glasses host failed")
        }
    }

    private fun authenticate(socket: Socket) {
        candidateSocket = socket
        val sessionKeys = if (pairing) {
            listener.onWirelessState(GlassesWirelessStage.PAIRING, "Establishing first-use trust")
            val pending = WirelessHandshakeSessions.beginGlassesPairing(
                socket.getInputStream(), socket.getOutputStream(), trustStore.localInstallationId(),
            )
            val completed = WirelessHandshakeSessions.finishGlassesPairing(
                pending, socket.getInputStream(), socket.getOutputStream(),
            )
            check(trustStore.save(completed.peer)) { "Paired phone changed during pairing" }
            completed.sessionKeys
        } else {
            listener.onWirelessState(GlassesWirelessStage.AUTHENTICATING, "Authenticating paired phone")
            val peer = trustStore.load() ?: error("Paired phone was forgotten")
            WirelessHandshakeSessions.authenticateGlasses(
                socket.getInputStream(), socket.getOutputStream(), trustStore.localInstallationId(), peer,
            )
        }
        socket.soTimeout = 1_000
        candidateSocket = null
        serverSocket?.close(); serverSocket = null
        val transport = SocketDbpTransport(
            socket,
            listener,
            outboundKey = sessionKeys.glassesToPhone,
            inboundKey = sessionKeys.phoneToGlasses,
        ).also(SocketDbpTransport::start)
        transportEstablished = true
        listener.onWirelessState(GlassesWirelessStage.ACTIVE, "Encrypted wireless bridge ready")
        listener.onWirelessConnection(transport)
    }

    private fun connectWhenServerReady(hostAddress: String): Socket {
        var lastError: Exception? = null
        repeat(MAX_TCP_CONNECT_ATTEMPTS) { attempt ->
            check(running.get()) { "Wireless glasses client stopped" }
            val socket = Socket()
            candidateSocket = socket
            try {
                socket.tcpNoDelay = true
                bindToP2pNetwork(socket)
                socket.connect(InetSocketAddress(hostAddress, PORT), TCP_CONNECT_ATTEMPT_TIMEOUT_MS)
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                return socket
            } catch (error: Exception) {
                lastError = error
                runCatching { socket.close() }
                candidateSocket = null
                if (attempt + 1 < MAX_TCP_CONNECT_ATTEMPTS) Thread.sleep(TCP_CONNECT_RETRY_DELAY_MS)
            }
        }
        throw lastError ?: IllegalStateException("Phone TCP listener did not become ready")
    }

    private fun bindServerWhenAddressReady(hostAddress: String): ServerSocket {
        var lastError: Exception? = null
        repeat(MAX_SERVER_BIND_ATTEMPTS) { attempt ->
            check(running.get()) { "Wireless glasses host stopped" }
            val server = ServerSocket()
            try {
                server.reuseAddress = true
                server.soTimeout = ACCEPT_TIMEOUT_MS
                server.bind(InetSocketAddress(hostAddress, PORT), 1)
                return server
            } catch (error: BindException) {
                lastError = error
                runCatching { server.close() }
                if (attempt + 1 < MAX_SERVER_BIND_ATTEMPTS) Thread.sleep(SERVER_BIND_RETRY_DELAY_MS)
            } catch (error: Exception) {
                runCatching { server.close() }
                throw error
            }
        }
        throw lastError ?: BindException("Wi-Fi Direct address did not become ready")
    }

    fun abortPairing() {
        runCatching { candidateSocket?.close() }
        stopInternal(removeGroup = true)
    }

    fun stop() = stopInternal(removeGroup = true)

    private fun scheduleDiscoveryRefresh(delayMs: Long) {
        if (!shouldRefreshDiscovery(running.get(), connectedGroup, joiningGroup, transportEstablished)) return
        retryHandler.removeCallbacks(discoveryRefresh)
        retryHandler.postDelayed(discoveryRefresh, delayMs)
    }

    private fun stopInternal(removeGroup: Boolean) {
        running.set(false)
        retryHandler.removeCallbacksAndMessages(null)
        runCatching { serverSocket?.close() }; serverSocket = null
        runCatching { candidateSocket?.close() }; candidateSocket = null
        worker?.interrupt(); worker = null
        transportEstablished = false
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        if (removeGroup) {
            runCatching { manager.clearServiceRequests(channel, object : Action("clear wireless discovery", false) {}) }
            runCatching { manager.cancelConnect(channel, object : Action("cancel wireless connect", false) {}) }
        }
        if (removeGroup && connectedGroup) runCatching { manager.removeGroup(channel, object : Action("leave wireless group", false) {}) }
        connectedGroup = false
        joiningGroup = false
        matchingDevice = null
    }

    private fun bindToP2pNetwork(socket: Socket) {
        val network = connectivity.allNetworks.firstOrNull { candidate ->
            connectivity.getLinkProperties(candidate)?.interfaceName?.startsWith("p2p") == true
        }
        if (network != null) {
            network.bindSocket(socket)
            return
        }
        val localAddress = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .asSequence()
                .filter { it.name.startsWith("p2p") }
                .flatMap { Collections.list(it.inetAddresses).asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull()
        }.getOrNull() ?: error("Wi-Fi Direct client address is not ready")
        socket.bind(InetSocketAddress(localAddress, 0))
    }

    private fun createClientConfig(device: WifiP2pDevice): WifiP2pConfig {
        @Suppress("DEPRECATION")
        return WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 0
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(appContext, receiver, IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
        }, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    private fun fail(message: String) {
        stopInternal(removeGroup = true)
        listener.onWirelessState(GlassesWirelessStage.ERROR, message)
        listener.onError(message)
    }

    override fun close() = stop()

    private open inner class Action(
        private val operation: String,
        private val reportFailure: Boolean = true,
    ) : WifiP2pManager.ActionListener {
        override fun onSuccess() = Unit
        open override fun onFailure(reason: Int) {
            if (reportFailure && running.get()) fail("Could not $operation (${reasonName(reason)})")
        }
    }

    companion object {
        const val PORT = 47_171
        const val SERVICE_INSTANCE = "RokidDisplayBridge"
        const val SERVICE_TYPE = "_rdbw._tcp"
        const val PROTOCOL_VERSION = "2"
        private const val ACCEPT_TIMEOUT_MS = 60_000
        private const val HANDSHAKE_TIMEOUT_MS = 60_000
        private const val MAX_BUSY_RETRIES = 6
        private const val BUSY_RETRY_DELAY_MS = 750L
        private const val MAX_GROUP_JOIN_FRAMEWORK_RETRIES = 3
        private const val GROUP_JOIN_RETRY_DELAY_MS = 1_500L
        private const val MAX_SERVER_BIND_ATTEMPTS = 20
        private const val SERVER_BIND_RETRY_DELAY_MS = 250L
        private const val MAX_TCP_CONNECT_ATTEMPTS = 20
        private const val TCP_CONNECT_ATTEMPT_TIMEOUT_MS = 1_000
        private const val TCP_CONNECT_RETRY_DELAY_MS = 250L
        private const val DISCOVERY_RESTART_DELAY_MS = 1_500L
        private const val DISCOVERY_REFRESH_MS = 45_000L

        fun hasRequiredPermission(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

        fun reasonName(reason: Int): String = when (reason) {
            WifiP2pManager.P2P_UNSUPPORTED -> "P2P unsupported"
            WifiP2pManager.BUSY -> "P2P busy"
            WifiP2pManager.ERROR -> "framework error"
            else -> "reason=$reason"
        }

        fun shouldRetryBusy(reason: Int, attempt: Int): Boolean =
            reason == WifiP2pManager.BUSY && attempt < MAX_BUSY_RETRIES

        fun shouldRetryGroupJoin(reason: Int, attempt: Int): Boolean =
            when (reason) {
                WifiP2pManager.BUSY -> attempt < MAX_BUSY_RETRIES
                WifiP2pManager.ERROR -> attempt < MAX_GROUP_JOIN_FRAMEWORK_RETRIES
                else -> false
            }

        fun shouldRefreshDiscovery(
            running: Boolean,
            connectedGroup: Boolean,
            joiningGroup: Boolean,
            transportEstablished: Boolean,
        ): Boolean = running && !connectedGroup && !joiningGroup && !transportEstablished
    }
}

enum class GlassesWirelessStage { IDLE, PERMISSION, DISCOVERING, GROUP_FORMING, TCP_CONNECTING, PAIRING, AUTHENTICATING, ACTIVE, ERROR }
