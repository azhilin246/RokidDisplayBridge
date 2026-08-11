package com.azhilin.rokid.displaybridge.companion.wireless

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
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

class PhoneWirelessHost(
    context: Context,
    private val trustStore: AndroidPeerTrustStore,
    private val listener: Listener,
) : Closeable {
    interface Listener : DbpTransport.Listener {
        fun onWirelessState(state: WirelessStage, detail: String)
        fun onWirelessConnection(connection: DbpTransport)
    }

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(WifiP2pManager::class.java)
        ?: error("Wi-Fi Direct is unavailable")
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val channel = manager.initialize(appContext, appContext.mainLooper) {
        fail("Wi-Fi Direct channel disconnected")
    }
    private val retryHandler = Handler(appContext.mainLooper)
    private val running = AtomicBoolean(false)
    @Volatile private var pairing = false
    @Volatile private var receiverRegistered = false
    @Volatile private var groupCreated = false
    @Volatile private var groupAuthenticated = false
    @Volatile private var transportEstablished = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var candidateSocket: Socket? = null
    @Volatile private var worker: Thread? = null
    private var connectionInfoAttempts = 0
    private val discoveryRefresh = Runnable {
        if (shouldRefreshDiscovery(running.get(), groupCreated, transportEstablished)) {
            listener.onWirelessState(WirelessStage.DISCOVERING, "Refreshing Wi-Fi Direct discovery")
            start(pairing)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED && running.get()) {
                        fail("Wi-Fi Direct is disabled or unavailable")
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val network = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO) as? NetworkInfo
                    if (network?.isConnected == true && running.get()) {
                        manager.requestConnectionInfo(channel, ::onConnectionInfo)
                    } else if (running.get() && groupCreated && !transportEstablished) {
                        groupCreated = false
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

    fun startPairing() = start(pairing = true)
    fun startConnect() = start(pairing = false)

    private fun start(pairing: Boolean) {
        check(hasRequiredPermission(appContext)) { "Nearby devices permission is required" }
        if (pairing && trustStore.load() != null) error("Forget the paired glasses before pairing a replacement")
        stopInternal(removeGroup = false)
        this.pairing = pairing
        running.set(true)
        transportEstablished = false
        groupAuthenticated = false
        connectionInfoAttempts = 0
        registerReceiver()
        listener.onWirelessState(WirelessStage.DISCOVERING, if (pairing) "Preparing wireless pairing" else "Preparing wireless reconnect")
        prepareExistingGroupOrActivate()
    }

    @SuppressLint("MissingPermission") // start() performs the version-aware runtime permission check.
    private fun prepareExistingGroupOrActivate() {
        manager.requestConnectionInfo(channel) { info ->
            if (!running.get()) return@requestConnectionInfo
            if (info?.groupFormed == true) {
                fail("Wi-Fi Direct is already in use by another app; stop it and retry")
            } else activateP2p()
        }
    }

    @SuppressLint("MissingPermission") // start() performs the version-aware runtime permission check.
    private fun activateP2p(attempt: Int = 0) {
        if (!running.get()) return
        listener.onWirelessState(WirelessStage.DISCOVERING, "Activating Wi-Fi Direct")
        manager.discoverPeers(channel, object : Action("activate Wi-Fi Direct") {
            override fun onSuccess() = advertiseService()
            override fun onFailure(reason: Int) {
                if (!retryBusy(reason, attempt, "P2P activation") {
                        activateP2p(attempt + 1)
                    }) super.onFailure(reason)
            }
        })
    }

    @SuppressLint("MissingPermission") // start() performs the version-aware runtime permission check.
    private fun advertiseService(attempt: Int = 0) {
        if (!running.get()) return
        val service = WifiP2pDnsSdServiceInfo.newInstance(
            SERVICE_INSTANCE,
            SERVICE_TYPE,
            mapOf("protocol" to PROTOCOL_VERSION, "port" to PORT.toString()),
        )
        manager.clearLocalServices(channel, object : Action("clear stale wireless service") {
            override fun onSuccess() {
                if (!running.get()) return
                manager.addLocalService(channel, service, object : Action("advertise Display Bridge service") {
                    override fun onSuccess() {
                        listener.onWirelessState(WirelessStage.DISCOVERING, "Wireless service advertised; waiting for glasses")
                        scheduleDiscoveryRefresh(DISCOVERY_REFRESH_MS)
                    }
                    override fun onFailure(reason: Int) {
                        if (!retryBusy(reason, attempt, "wireless advertisement") {
                                advertiseService(attempt + 1)
                            }) super.onFailure(reason)
                    }
                })
            }
            override fun onFailure(reason: Int) {
                if (!retryBusy(reason, attempt, "wireless setup") {
                        advertiseService(attempt + 1)
                    }) super.onFailure(reason)
            }
        })
    }

    private fun retryBusy(reason: Int, attempt: Int, stage: String, retry: () -> Unit): Boolean {
        if (!shouldRetryBusy(reason, attempt) || !running.get()) return false
        listener.onWirelessState(WirelessStage.DISCOVERING, "Wi-Fi Direct is busy during $stage; retrying")
        retryHandler.postDelayed({ if (running.get()) retry() }, BUSY_RETRY_DELAY_MS)
        return true
    }

    private fun onConnectionInfo(info: WifiP2pInfo?) {
        if (!running.get() || worker?.isAlive == true) return
        if (!isReadyP2pGroup(info)) {
            retryHandler.postDelayed(::requestReadyConnectionInfo, CONNECTION_INFO_RETRY_DELAY_MS)
            return
        }
        connectionInfoAttempts = 0
        groupCreated = true
        retryHandler.removeCallbacks(discoveryRefresh)
        val address = requireNotNull(info?.groupOwnerAddress)
        val hostAddress = address.hostAddress ?: address.hostName
        if (info.isGroupOwner) {
            listener.onWirelessState(WirelessStage.TCP_CONNECTING, "Glasses joined; opening authenticated TCP listener")
            worker = Thread({ accept(hostAddress) }, "rdb-wireless-phone-host").apply { start() }
        } else {
            listener.onWirelessState(WirelessStage.TCP_CONNECTING, "Glasses owns the Wi-Fi Direct group; connecting securely")
            worker = Thread({ connect(hostAddress) }, "rdb-wireless-phone-client").apply { start() }
        }
    }

    private fun requestReadyConnectionInfo() {
        if (!running.get() || worker?.isAlive == true) return
        if (connectionInfoAttempts >= MAX_CONNECTION_INFO_ATTEMPTS) {
            fail("Wi-Fi Direct group did not become ready")
            return
        }
        connectionInfoAttempts += 1
        manager.requestConnectionInfo(channel, ::onConnectionInfo)
    }

    private fun accept(hostAddress: String) {
        runCatching {
            val server = bindServerWhenAddressReady(hostAddress)
            serverSocket = server
            val socket = server.accept().apply { soTimeout = HANDSHAKE_TIMEOUT_MS }
            authenticate(socket)
        }.onFailure { error ->
            if (running.get()) fail(error.message ?: "Wireless phone host failed")
        }
    }

    private fun connect(hostAddress: String) {
        runCatching { authenticate(connectWhenServerReady(hostAddress)) }
            .onFailure { error ->
                if (running.get()) fail(error.message ?: "Wireless phone client failed")
            }
    }

    private fun authenticate(socket: Socket) {
        candidateSocket = socket
        listener.onWirelessState(WirelessStage.AUTHENTICATING, if (pairing) "Establishing first-use trust" else "Authenticating paired glasses")
        val sessionKeys = if (pairing) {
            val pending = WirelessHandshakeSessions.beginPhonePairing(
                socket.getInputStream(), socket.getOutputStream(), trustStore.localInstallationId(),
            )
            val completed = WirelessHandshakeSessions.finishPhonePairing(pending, socket.getInputStream(), socket.getOutputStream())
            check(trustStore.save(completed.peer)) { "Paired glasses changed during pairing" }
            completed.sessionKeys
        } else {
            val peer = trustStore.load() ?: error("No paired glasses; use Pair wireless first")
            WirelessHandshakeSessions.authenticatePhone(
                socket.getInputStream(), socket.getOutputStream(), trustStore.localInstallationId(), peer,
            )
        }
        // A successful authenticated handshake is the first point where the phone can
        // prove that the active framework group belongs to Display Bridge. Never let a
        // generic Wi-Fi Direct broadcast grant cleanup ownership of another app's group.
        groupAuthenticated = true
        socket.soTimeout = 1_000
        candidateSocket = null
        serverSocket?.close(); serverSocket = null
        val transport = SocketDbpTransport(
            socket,
            listener,
            outboundKey = sessionKeys.phoneToGlasses,
            inboundKey = sessionKeys.glassesToPhone,
        ).also(SocketDbpTransport::start)
        transportEstablished = true
        listener.onWirelessState(WirelessStage.ACTIVE, "Encrypted Wi-Fi Direct/TCP transport ready")
        listener.onWirelessConnection(transport)
    }

    private fun connectWhenServerReady(hostAddress: String): Socket {
        var lastError: Exception? = null
        repeat(MAX_TCP_CONNECT_ATTEMPTS) { attempt ->
            check(running.get()) { "Wireless phone client stopped" }
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
        throw lastError ?: IllegalStateException("Glasses TCP listener did not become ready")
    }

    private fun bindServerWhenAddressReady(hostAddress: String): ServerSocket {
        var lastError: Exception? = null
        repeat(MAX_SERVER_BIND_ATTEMPTS) { attempt ->
            check(running.get()) { "Wireless phone host stopped" }
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

    fun stop() = stopInternal(removeGroup = true)

    private fun scheduleDiscoveryRefresh(delayMs: Long) {
        if (!shouldRefreshDiscovery(running.get(), groupCreated, transportEstablished)) return
        retryHandler.removeCallbacks(discoveryRefresh)
        retryHandler.postDelayed(discoveryRefresh, delayMs)
    }

    private fun stopInternal(removeGroup: Boolean) {
        running.set(false)
        retryHandler.removeCallbacksAndMessages(null)
        connectionInfoAttempts = 0
        runCatching { candidateSocket?.close() }; candidateSocket = null
        runCatching { serverSocket?.close() }; serverSocket = null
        worker?.interrupt(); worker = null
        transportEstablished = false
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        if (removeGroup) {
            runCatching { manager.clearLocalServices(channel, object : Action("clear wireless service", reportFailure = false) {}) }
        }
        if (shouldRemoveGroup(removeGroup, groupAuthenticated)) {
            runCatching { manager.removeGroup(channel, object : Action("remove Wi-Fi Direct group", reportFailure = false) {}) }
        }
        groupCreated = false
        groupAuthenticated = false
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
        listener.onWirelessState(WirelessStage.ERROR, message)
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
        private const val CONNECTION_INFO_RETRY_DELAY_MS = 500L
        private const val MAX_CONNECTION_INFO_ATTEMPTS = 20
        private const val MAX_SERVER_BIND_ATTEMPTS = 20
        private const val SERVER_BIND_RETRY_DELAY_MS = 250L
        private const val MAX_TCP_CONNECT_ATTEMPTS = 20
        private const val TCP_CONNECT_ATTEMPT_TIMEOUT_MS = 1_000
        private const val TCP_CONNECT_RETRY_DELAY_MS = 250L
        private const val DISCOVERY_RESTART_DELAY_MS = 1_500L
        private const val DISCOVERY_REFRESH_MS = 45_000L

        fun hasRequiredPermission(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED

        fun reasonName(reason: Int): String = when (reason) {
            WifiP2pManager.P2P_UNSUPPORTED -> "P2P unsupported"
            WifiP2pManager.BUSY -> "P2P busy"
            WifiP2pManager.ERROR -> "framework error"
            else -> "reason=$reason"
        }

        fun shouldRetryBusy(reason: Int, attempt: Int): Boolean =
            reason == WifiP2pManager.BUSY && attempt < MAX_BUSY_RETRIES

        fun isReadyP2pGroup(info: WifiP2pInfo?): Boolean =
            info?.groupFormed == true && info.groupOwnerAddress != null

        fun shouldRefreshDiscovery(running: Boolean, groupCreated: Boolean, transportEstablished: Boolean): Boolean =
            running && !groupCreated && !transportEstablished

        fun shouldRemoveGroup(removeRequested: Boolean, groupAuthenticated: Boolean): Boolean =
            removeRequested && groupAuthenticated
    }
}

enum class WirelessStage {
    IDLE, PERMISSION, DISCOVERING, GROUP_FORMING, TCP_CONNECTING, PAIRING, AUTHENTICATING, ACTIVE, ERROR,
}
