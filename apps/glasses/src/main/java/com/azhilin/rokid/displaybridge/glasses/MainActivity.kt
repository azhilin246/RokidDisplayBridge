package com.azhilin.rokid.displaybridge.glasses

import android.app.Activity
import android.app.PendingIntent
import android.Manifest
import android.os.BatteryManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import com.azhilin.rokid.displaybridge.glasses.playback.LiveVideoDecoder
import com.azhilin.rokid.displaybridge.glasses.transport.AccessoryTransport
import com.azhilin.rokid.displaybridge.glasses.input.FocusMove
import com.azhilin.rokid.displaybridge.glasses.input.InputMode
import com.azhilin.rokid.displaybridge.glasses.input.PlayerInput
import com.azhilin.rokid.displaybridge.glasses.input.PlayerInputAction
import com.azhilin.rokid.displaybridge.glasses.input.PlayerInputRouter
import com.azhilin.rokid.displaybridge.glasses.input.SwipeActionGate
import com.azhilin.rokid.displaybridge.glasses.wireless.GlassesWirelessClient
import com.azhilin.rokid.displaybridge.glasses.wireless.GlassesWirelessStage
import com.azhilin.rokid.displaybridge.glasses.ui.OverlayControl
import com.azhilin.rokid.displaybridge.glasses.ui.PlaybackOverlayModel
import com.azhilin.rokid.displaybridge.protocol.DiagnosticPayload
import com.azhilin.rokid.displaybridge.protocol.DbpTransport
import com.azhilin.rokid.displaybridge.protocol.DisplayLayoutPayload
import com.azhilin.rokid.displaybridge.protocol.ErrorCode
import com.azhilin.rokid.displaybridge.protocol.ErrorPayload
import com.azhilin.rokid.displaybridge.protocol.Frame
import com.azhilin.rokid.displaybridge.protocol.GestureSettingsPayload
import com.azhilin.rokid.displaybridge.protocol.HelloPayload
import com.azhilin.rokid.displaybridge.protocol.LivePayloads
import com.azhilin.rokid.displaybridge.protocol.LiveSourceKind
import com.azhilin.rokid.displaybridge.protocol.LiveStopPayload
import com.azhilin.rokid.displaybridge.protocol.MediaCommand
import com.azhilin.rokid.displaybridge.protocol.MediaKeyPayload
import com.azhilin.rokid.displaybridge.protocol.MessageType
import com.azhilin.rokid.displaybridge.protocol.StopReason
import com.azhilin.rokid.displaybridge.protocol.AndroidPeerTrustStore
import com.azhilin.rokid.displaybridge.protocol.WirelessRecoveryPolicy
import kotlin.math.abs

class MainActivity : Activity(), DbpTransport.Listener, LiveVideoDecoder.Listener, GlassesWirelessClient.Listener {
    private lateinit var usbManager: UsbManager
    private lateinit var ui: GlassesUi
    private lateinit var decoder: LiveVideoDecoder
    private lateinit var wireless: GlassesWirelessClient
    private lateinit var wirelessTrust: AndroidPeerTrustStore
    private lateinit var displayWakeLock: PowerManager.WakeLock
    private lateinit var gestures: GestureDetector
    @Volatile private var transport: DbpTransport? = null
    private val sessionGuard = IncomingSessionGuard()
    private val displayWakeGate = DisplayWakeGate()
    private val overlayModel = PlaybackOverlayModel()
    private val exitConfirmation = ExitConfirmationController(EXIT_CONFIRMATION_TIMEOUT_MS)
    private val swipeActionGate = SwipeActionGate()
    @Volatile private var gestureSettings = GestureSettingsPayload()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentState = GlassesState.WAITING_FOR_BRIDGE
    private var currentSourceKind: LiveSourceKind? = null
    private var usbPowered = false
    private var usbDataConnected = false
    private var usbDataConfigured = false
    private var receiverRegistered = false
    private val accessoryPermissionGate = AccessoryPermissionGate()
    private var activeAccessoryKey: String? = null
    private var destroying = false
    private var exitingForCableDetach = false
    private var exitingForUserAction = false
    private val hideOverlay = Runnable { hidePlaybackOverlay() }
    private val disarmExit = Runnable { resetExitConfirmation() }
    private val endWakePulse = Runnable { ui.endWakePulse(currentState == GlassesState.PLAYING) }
    private val finishUserExit = Runnable { completeUserExit() }
    private var wirelessRecoveryActive = false
    private var wirelessRecoveryAttempt = 0
    private val wirelessRecoveryRetry = Runnable {
        if (!wirelessRecoveryActive || transport != null || destroying) return@Runnable
        runCatching(wireless::startConnect).onFailure { error ->
            Log.w(TAG, "Wireless recovery start failed: ${error.message}")
            scheduleWirelessRecovery("Could not restart Wi-Fi Direct: ${error.message}")
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION -> {
                    @Suppress("DEPRECATION")
                    val accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY) as? UsbAccessory
                        ?: usbManager.accessoryList?.firstOrNull()
                    accessory?.let { accessoryPermissionGate.onResult(accessoryKey(it)) }
                    if (accessory != null && usbManager.hasPermission(accessory)) open(accessory)
                    else {
                        accessory?.let { accessoryPermissionGate.onDenied(accessoryKey(it)) }
                        render(GlassesState.ERROR, "USB accessory permission denied")
                    }
                }
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                    @Suppress("DEPRECATION")
                    val detached = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY) as? UsbAccessory
                    if (detached == null || (transport as? AccessoryTransport)?.matches(detached) == true ||
                        activeAccessoryKey == accessoryKey(detached)
                    ) exitToHomeAfterCableDetach("USB accessory detached")
                }
                Intent.ACTION_BATTERY_CHANGED, ACTION_USB_STATE -> {
                    updateConnectionSignals(intent)
                    if (transport == null && !wireless.isRunning()) renderWirelessIdle()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        ui = GlassesUi(this, ::onControlClicked, overlayModel::focus)
        setContentView(ui.root)
        @Suppress("DEPRECATION")
        displayWakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "$packageName:first-live-frame",
        ).apply { setReferenceCounted(false) }
        gestures = GestureDetector(this, PlayerGestures())
        decoder = LiveVideoDecoder(ui.textureView, this)
        wirelessTrust = AndroidPeerTrustStore(this, "glasses")
        wireless = GlassesWirelessClient(this, wirelessTrust, this)
        usbManager = getSystemService(UsbManager::class.java)
        updateConnectionSignals(registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        updateConnectionSignals(registerReceiver(null, IntentFilter(ACTION_USB_STATE)))
        registerUsbReceiver()
        connect(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) { setIntent(intent); connect(intent) }
    }

    override fun onDestroy() {
        destroying = true
        cancelWirelessRecovery()
        resetExitConfirmation()
        mainHandler.removeCallbacks(hideOverlay)
        mainHandler.removeCallbacks(endWakePulse)
        mainHandler.removeCallbacks(finishUserExit)
        ui.endWakePulse(false)
        if (::displayWakeLock.isInitialized && displayWakeLock.isHeld) displayWakeLock.release()
        ui.root.keepScreenOn = false
        val currentTransport = transport
        transport = null
        currentTransport?.close()
        wireless.close()
        activeAccessoryKey = null
        accessoryPermissionGate.reset()
        decoder.close()
        if (receiverRegistered) unregisterReceiver(receiver)
        receiverRegistered = false
        super.onDestroy()
    }

    override fun onPause() {
        resetExitConfirmation()
        hidePlaybackOverlay()
        super.onPause()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        gestures.onTouchEvent(event) || super.dispatchTouchEvent(event)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = when (event.keyCode) {
        KeyEvent.KEYCODE_NOTIFICATION -> {
            true
        }
        KeyEvent.KEYCODE_BACK, KEYCODE_SPRITE_DOUBLE_TAP -> consumeInput(event, PlayerInput.DOUBLE_TAP)
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> consumeInput(event, PlayerInput.SINGLE_TAP)
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KEYCODE_SWIPE_FORWARD,
        KeyEvent.KEYCODE_MEDIA_NEXT -> consumeDirection(event, PlayerInput.NEXT)
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KEYCODE_SWIPE_BACKWARD,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> consumeDirection(event, PlayerInput.PREVIOUS)
        else -> super.dispatchKeyEvent(event)
    }

    private fun connect(intent: Intent?) {
        @Suppress("DEPRECATION")
        val attached = intent?.getParcelableExtra(UsbManager.EXTRA_ACCESSORY) as? UsbAccessory
        val accessory = attached ?: usbManager.accessoryList?.firstOrNull()
        if (accessory == null) {
            if (wireless.hasPairedPhone() && GlassesWirelessClient.hasRequiredPermission(this)) {
                runCatching(wireless::startConnect).onFailure { render(GlassesState.ERROR, it.message) }
            } else renderWirelessIdle()
            return
        }
        if (transport?.transportName?.startsWith("Wi-Fi") == true) {
            render(currentState, "USB attached; active wireless session retained")
            return
        }
        if (!isBridgeIdentity(accessory.manufacturer, accessory.model, accessory.version)) {
            render(GlassesState.ERROR, "Accessory identity does not belong to Rokid Display Bridge")
            return
        }
        val key = accessoryKey(accessory)
        activeAccessoryKey = key
        val hasPermission = usbManager.hasPermission(accessory)
        render(GlassesState.CONNECTING, if (hasPermission) "Opening bridge accessory" else "Bridge accessory detected; requesting permission")
        if (hasPermission) {
            accessoryPermissionGate.onResult(key)
            open(accessory)
        } else if (accessoryPermissionGate.shouldRequest(key)) {
            val permission = PendingIntent.getBroadcast(this, 24_017,
                Intent(ACTION_USB_PERMISSION).setPackage(packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
            usbManager.requestPermission(accessory, permission)
        } else {
            render(GlassesState.ERROR, "USB accessory permission was denied; reconnect the cable to retry")
        }
    }

    private fun open(accessory: UsbAccessory) {
        cancelWirelessRecovery()
        if ((transport as? AccessoryTransport)?.matches(accessory) == true) return
        activeAccessoryKey = accessoryKey(accessory)
        accessoryPermissionGate.onResult(activeAccessoryKey!!)
        val previous = transport
        transport = null
        previous?.close()
        wireless.stop()
        val descriptor = usbManager.openAccessory(accessory) ?: run {
            render(GlassesState.ERROR, "Could not open bridge USB accessory")
            return
        }
        val candidate = AccessoryTransport(accessory, descriptor, this)
        transport = candidate
        candidate.start()
        render(GlassesState.CONNECTING, "Waiting for DBP1 HELLO")
        Log.i(TAG, "opened identity=${accessory.manufacturer}/${accessory.model}/${accessory.version}/${accessory.serial}")
    }

    override fun onFrame(frame: Frame) {
        runCatching {
            when (frame.type) {
                MessageType.HELLO -> {
                    if (wirelessRecoveryActive) finishWirelessRecovery()
                    sessionGuard.resetConnection()
                    displayWakeGate.resetConnection()
                    currentSourceKind = null
                    gestureSettings = GestureSettingsPayload()
                    decoder.stop(null)
                    val remote = LivePayloads.decodeHello(frame.payload)
                    val response = HelloPayload(
                        maxPayloadBytes = minOf(remote.maxPayloadBytes, com.azhilin.rokid.displaybridge.protocol.DBP_MAX_PAYLOAD_SIZE.toUInt()),
                        maxAccessUnitBytes = minOf(remote.maxAccessUnitBytes, LivePayloads.MAX_ACCESS_UNIT_BYTES.toUInt()),
                        maxFragments = minOf(remote.maxFragments, LivePayloads.MAX_FRAGMENTS.toUShort()),
                        capabilities = LivePayloads.VIDEO_CAPABILITY,
                    )
                    send(Frame(MessageType.HELLO_ACK, requestId = frame.requestId, payload = LivePayloads.encodeHello(response)))
                    render(GlassesState.READY)
                    sendDiagnostic(0u, 0u, "DBP1 video-only handshake ready")
                }
                MessageType.DISPLAY_LAYOUT -> {
                    val layout = LivePayloads.decodeDisplayLayout(frame.payload)
                    runOnUiThread { ui.applyDisplayLayout(layout) }
                    send(Frame(MessageType.DISPLAY_LAYOUT_ACK, requestId = frame.requestId,
                        payload = LivePayloads.encodeDisplayLayout(layout)))
                    sendDiagnostic(sessionGuard.currentSessionId, sessionGuard.currentGeneration,
                        "Display layout scale=${layout.scalePermille} x=${layout.offsetXPermille} y=${layout.offsetYPermille}")
                }
                MessageType.GESTURE_SETTINGS -> {
                    val settings = LivePayloads.decodeGestureSettings(frame.payload)
                    gestureSettings = settings
                    runOnUiThread { resetExitConfirmation() }
                    send(Frame(MessageType.GESTURE_SETTINGS_ACK, requestId = frame.requestId,
                        payload = LivePayloads.encodeGestureSettings(settings)))
                    sendDiagnostic(sessionGuard.currentSessionId, sessionGuard.currentGeneration,
                        "Gesture settings applied swipe=${settings.swipeAction} tap=${settings.tapAction} " +
                            "double=${settings.doubleTapAction} reverse=${settings.reverseSwipeDirections}")
                }
                MessageType.LIVE_START -> LivePayloads.decodeStart(frame.payload).also {
                    if (sessionGuard.acceptStart(it.sessionId, it.generation)) {
                        currentSourceKind = it.sourceKind
                        decoder.start(it)
                        render(
                            GlassesState.WAITING_FOR_VIDEO,
                            if (it.sourceKind == LiveSourceKind.PRESENTATION_DISPLAY) null
                            else "Waiting for screen content.",
                        )
                    }
                }
                MessageType.LIVE_CONFIG -> LivePayloads.decodeConfig(frame.payload).also {
                    if (sessionGuard.acceptConfig(it.sessionId, it.generation)) decoder.configure(it)
                }
                MessageType.LIVE_SAMPLE -> decoder.accept(LivePayloads.decodeSample(frame.payload))
                MessageType.LIVE_DISCONTINUITY -> LivePayloads.decodeDiscontinuity(frame.payload).also {
                    if (sessionGuard.acceptDiscontinuity(it.sessionId, it.generation)) decoder.discontinuity(it)
                }
                MessageType.LIVE_STOP -> LivePayloads.decodeStop(frame.payload).also {
                    if (sessionGuard.acceptStop(it.sessionId)) {
                        currentSourceKind = null
                        decoder.stop(it)
                        render(GlassesState.READY, "Bridge mode stopped")
                    }
                }
                MessageType.DIAGNOSTIC -> Log.i(TAG, "phone diagnostic=${LivePayloads.decodeDiagnostic(frame.payload).detail}")
                MessageType.ERROR -> LivePayloads.decodeError(frame.payload).let { render(GlassesState.ERROR, "Phone ${it.code}: ${it.detail}") }
                MessageType.MEDIA_KEY, MessageType.DISPLAY_LAYOUT_ACK, MessageType.GESTURE_SETTINGS_ACK -> Unit
                else -> Unit
            }
        }.onFailure { error ->
            Log.e(TAG, "protocol error", error)
            render(GlassesState.ERROR, error.message)
            send(Frame(MessageType.ERROR, payload = LivePayloads.encodeError(ErrorPayload(ErrorCode.MALFORMED_FRAME,
                error.message ?: error.javaClass.simpleName))))
        }
    }

    override fun onDisconnected(message: String) = runOnUiThread {
        val lost = transport ?: return@runOnUiThread
        if (destroying) return@runOnUiThread
        if (lost.transportName == "USB/AOA") exitToHomeAfterCableDetach(message)
        else {
            transport = null
            decoder.stop(null)
            sessionGuard.resetConnection()
            beginWirelessRecovery(message)
        }
    }
    override fun onError(message: String) = runOnUiThread {
        if (destroying || exitingForUserAction || exitingForCableDetach) return@runOnUiThread
        if (wirelessRecoveryActive || transport?.transportName?.startsWith("Wi-Fi") == true) {
            Log.w(TAG, "Wireless link error before recovery: $message")
            render(GlassesState.CONNECTING, "Wireless link changed; reconnecting automatically")
            return@runOnUiThread
        }
        val failedSession = sessionGuard.currentSessionId
        if (failedSession != 0uL && sessionGuard.acceptStop(failedSession)) {
            send(Frame(MessageType.LIVE_STOP, payload = LivePayloads.encodeStop(
                LiveStopPayload(failedSession, StopReason.ERROR, message.take(LivePayloads.MAX_TEXT_BYTES)))))
        }
        render(GlassesState.ERROR, message)
    }

    override fun onReady(payload: com.azhilin.rokid.displaybridge.protocol.LiveReadyPayload) {
        send(Frame(MessageType.LIVE_READY, payload = LivePayloads.encodeReady(payload)))
        sendDiagnostic(payload.sessionId, payload.generation, "Live session accepted; decoder bounds available")
    }

    override fun onFirstFrame(sessionId: ULong, generation: UInt) {
        if (sessionId != sessionGuard.currentSessionId || generation != sessionGuard.currentGeneration) return
        if (displayWakeGate.acceptFirstFrame(sessionId, currentSourceKind)) {
            sendDiagnostic(sessionId, generation, "First ${currentSourceKind?.name} access unit received; waking display")
            pulseDisplayAwake("First ${currentSourceKind?.name} access unit")
        }
    }

    override fun onFeedback(payload: com.azhilin.rokid.displaybridge.protocol.LiveFeedbackPayload) {
        send(Frame(MessageType.LIVE_FEEDBACK, payload = LivePayloads.encodeFeedback(payload)))
    }

    override fun onDiagnostic(sessionId: ULong, generation: UInt, detail: String) {
        sendDiagnostic(sessionId, generation, detail)
    }

    override fun onState(state: LiveVideoDecoder.DecoderState, detail: String?) {
        if (destroying || exitingForCableDetach || exitingForUserAction) return
        when (state) {
            LiveVideoDecoder.DecoderState.IDLE -> render(GlassesState.READY, detail)
            LiveVideoDecoder.DecoderState.LOADING -> render(GlassesState.LOADING, detail)
            LiveVideoDecoder.DecoderState.PLAYING -> render(GlassesState.PLAYING)
            LiveVideoDecoder.DecoderState.ERROR -> render(GlassesState.ERROR, detail)
        }
    }

    private fun pulseDisplayAwake(reason: String) = runOnUiThread {
        mainHandler.removeCallbacks(endWakePulse)
        @Suppress("DEPRECATION")
        displayWakeLock.acquire(DISPLAY_WAKE_LOCK_MS)
        ui.beginWakePulse()
        mainHandler.postDelayed(endWakePulse, DISPLAY_WAKE_PULSE_MS)
        Log.i(TAG, "one-shot display wake: $reason")
    }

    private fun exitToHomeAfterCableDetach(message: String) {
        if (destroying || exitingForCableDetach) return
        cancelWirelessRecovery()
        exitingForCableDetach = true
        Log.i(TAG, "active bridge cable disconnected; exiting to HOME: $message")
        val currentTransport = transport
        transport = null
        activeAccessoryKey = null
        accessoryPermissionGate.reset()
        currentTransport?.close()
        decoder.stop(null)
        sessionGuard.resetConnection()
        ui.root.keepScreenOn = false
        startActivity(cableDetachHomeIntent())
        finishAndRemoveTask()
    }

    private fun send(frame: Frame) = transport?.send(frame)

    private fun sendDiagnostic(sessionId: ULong, generation: UInt, detail: String) {
        send(Frame(MessageType.DIAGNOSTIC, payload = LivePayloads.encodeDiagnostic(
            DiagnosticPayload(sessionId, generation, detail.take(LivePayloads.MAX_TEXT_BYTES)))))
    }

    private fun render(state: GlassesState, detail: String? = null) = runOnUiThread {
        resetExitConfirmation()
        currentState = state
        if (state != GlassesState.PLAYING) hidePlaybackOverlay()
        ui.render(state, detail)
        Log.i(TAG, "state=$state keepScreenOn=${ui.root.keepScreenOn} detail=${detail.orEmpty()}")
    }

    private fun consumeInput(event: KeyEvent, input: PlayerInput): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) routeInput(input)
        return true
    }

    private fun consumeDirection(event: KeyEvent, input: PlayerInput): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            swipeActionGate.accept(event.eventTime)
        ) routeInput(input)
        return true
    }

    private fun routeInput(input: PlayerInput) {
        if (input == PlayerInput.DOUBLE_TAP) {
            cancelWirelessRecovery()
            wireless.abortPairing()
            exitApplication("Double tap exited Display Bridge")
            return
        }
        if (transport == null && input == PlayerInput.SINGLE_TAP) {
            startWirelessFromGlasses()
            return
        }
        val mode = if (currentState != GlassesState.PLAYING) InputMode.MAIN
        else if (overlayModel.isVisible) InputMode.OVERLAY_VISIBLE else InputMode.PLAYER_HIDDEN
        if (overlayModel.isVisible) scheduleOverlayHide()
        when (val action = PlayerInputRouter.route(mode, input, gestureSettings)) {
            PlayerInputAction.None -> Unit
            is PlayerInputAction.SendMediaCommand -> sendMediaCommand(action.command)
            PlayerInputAction.ShowOverlay -> showPlaybackOverlay()
            PlayerInputAction.HideOverlay -> hidePlaybackOverlay()
            PlayerInputAction.ActivateFocused -> activateOverlayControl(overlayModel.focused)
            is PlayerInputAction.MoveFocus -> ui.requestOverlayFocus(when (action.direction) {
                FocusMove.PREVIOUS -> overlayModel.previous()
                FocusMove.NEXT -> overlayModel.next()
            })
            is PlayerInputAction.ExitApplication -> handleConfiguredExit(action.requireConfirmation)
        }
    }

    private fun handleConfiguredExit(requireConfirmation: Boolean) {
        if (!requireConfirmation) {
            exitApplication("Glasses gesture exited application")
            return
        }
        when (exitConfirmation.onDoubleTap()) {
            ExitConfirmationAction.ARM -> {
                mainHandler.removeCallbacks(disarmExit)
                ui.setExitConfirmationVisible(true, "Double tap again to exit Display Bridge")
                mainHandler.postDelayed(disarmExit, EXIT_CONFIRMATION_TIMEOUT_MS)
                Log.i(TAG, "configured exit armed")
            }
            ExitConfirmationAction.CONFIRM_EXIT -> {
                mainHandler.removeCallbacks(disarmExit)
                ui.setExitConfirmationVisible(false)
                Log.i(TAG, "configured exit confirmed")
                exitApplication("Glasses gesture exited application")
            }
        }
    }

    private fun exitApplication(detail: String) {
        if (destroying || exitingForUserAction) return
        exitingForUserAction = true
        cancelWirelessRecovery()
        wireless.abortPairing()
        resetExitConfirmation()
        val session = sessionGuard.currentSessionId
        val stop = directExitStopPayload(session, detail)
        if (stop != null) {
            sessionGuard.acceptStop(session)
            decoder.stop(stop)
        }
        currentSourceKind = null
        hidePlaybackOverlay()
        if (::ui.isInitialized) ui.root.keepScreenOn = false
        Log.i(TAG, "exiting glasses application session=$session detail=$detail")
        if (stop == null || transport == null) {
            completeUserExit()
            return
        }
        mainHandler.postDelayed(finishUserExit, USER_EXIT_WRITE_TIMEOUT_MS)
        transport?.send(Frame(MessageType.LIVE_STOP, payload = LivePayloads.encodeStop(stop))) {
            mainHandler.post(::completeUserExit)
        }
    }

    private fun completeUserExit() {
        if (destroying) return
        mainHandler.removeCallbacks(finishUserExit)
        finishAndRemoveTask()
    }

    private fun resetExitConfirmation() {
        mainHandler.removeCallbacks(disarmExit)
        exitConfirmation.disarm()
        if (::ui.isInitialized) ui.setExitConfirmationVisible(false)
    }

    override fun onWirelessState(state: GlassesWirelessStage, detail: String) = runOnUiThread {
        if (state == GlassesWirelessStage.ERROR && wirelessRecoveryActive) {
            scheduleWirelessRecovery(detail)
            return@runOnUiThread
        }
        when (state) {
            GlassesWirelessStage.PAIRING -> render(GlassesState.PAIRING, detail)
            GlassesWirelessStage.ERROR -> render(GlassesState.ERROR, detail)
            GlassesWirelessStage.ACTIVE -> render(GlassesState.CONNECTING, detail)
            else -> render(GlassesState.CONNECTING, detail)
        }
    }

    override fun onWirelessConnection(connection: DbpTransport) = runOnUiThread {
        if (transport != null) {
            connection.close()
            return@runOnUiThread
        }
        mainHandler.removeCallbacks(wirelessRecoveryRetry)
        transport = connection
        activeAccessoryKey = null
        sessionGuard.resetConnection()
        displayWakeGate.resetConnection()
        render(GlassesState.CONNECTING, "Authenticated wireless transport; waiting for DBP1 HELLO")
    }

    private fun beginWirelessRecovery(reason: String) {
        if (!wirelessRecoveryActive) {
            wirelessRecoveryActive = true
            wirelessRecoveryAttempt = 0
        }
        wireless.stop()
        scheduleWirelessRecovery(reason)
    }

    private fun scheduleWirelessRecovery(reason: String) {
        if (!wirelessRecoveryActive || transport != null || destroying) return
        mainHandler.removeCallbacks(wirelessRecoveryRetry)
        val attempt = wirelessRecoveryAttempt++
        val delayMs = WirelessRecoveryPolicy.delayMs(attempt)
        render(
            GlassesState.CONNECTING,
            "$reason. Reconnecting automatically in ${delayMs / 1_000.0}s",
        )
        Log.i(TAG, "wireless recovery attempt=${attempt + 1} delayMs=$delayMs reason=$reason")
        mainHandler.postDelayed(wirelessRecoveryRetry, delayMs)
    }

    private fun finishWirelessRecovery() {
        mainHandler.removeCallbacks(wirelessRecoveryRetry)
        wirelessRecoveryActive = false
        wirelessRecoveryAttempt = 0
        Log.i(TAG, "wireless transport and DBP1 recovered automatically")
    }

    private fun cancelWirelessRecovery() {
        mainHandler.removeCallbacks(wirelessRecoveryRetry)
        wirelessRecoveryActive = false
        wirelessRecoveryAttempt = 0
    }

    private fun renderWirelessIdle() {
        render(
            GlassesState.WAITING_FOR_BRIDGE,
            if (wireless.hasPairedPhone()) "Tap to reconnect to the paired phone wirelessly."
            else "Tap to start one-time wireless pairing.",
        )
    }

    private fun startWirelessFromGlasses() {
        if (!GlassesWirelessClient.hasRequiredPermission(this)) {
            val permissions = if (android.os.Build.VERSION.SDK_INT >= 33) {
                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            }
            requestPermissions(permissions, REQUEST_WIRELESS_PERMISSION)
            render(GlassesState.CONNECTING, "Approve Nearby access for local Wi-Fi Direct discovery")
            return
        }
        runCatching {
            if (wireless.hasPairedPhone()) wireless.startConnect() else wireless.startPairing()
        }.onFailure { render(GlassesState.ERROR, it.message) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_WIRELESS_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startWirelessFromGlasses()
            else render(GlassesState.ERROR, "Location access is required for Wi-Fi Direct discovery on Android 12")
        }
    }

    private fun onControlClicked(control: OverlayControl) {
        activateOverlayControl(control)
    }

    private fun activateOverlayControl(control: OverlayControl) {
        if (!overlayModel.isVisible) return
        overlayModel.focus(control)
        val command = control.mediaCommand
        if (command != null) {
            sendMediaCommand(command)
            scheduleOverlayHide()
        } else {
            Log.i(TAG, "playback overlay activating Exit session=${sessionGuard.currentSessionId} state=$currentState")
            exitApplication("Glasses playback overlay Exit")
        }
    }

    private fun sendMediaCommand(command: MediaCommand) {
        val session = sessionGuard.currentSessionId
        val generation = sessionGuard.currentGeneration
        if (session == 0uL || generation == 0u) return
        send(Frame(MessageType.MEDIA_KEY, payload = LivePayloads.encodeMediaKey(
            MediaKeyPayload(session, generation, command))))
        sendDiagnostic(session, generation, "Media control sent: $command")
    }

    private fun showPlaybackOverlay() {
        if (currentState != GlassesState.PLAYING) return
        overlayModel.show()
        ui.setOverlayVisible(true)
        ui.requestOverlayFocus(overlayModel.focused)
        swipeActionGate.reset()
        scheduleOverlayHide()
    }

    private fun hidePlaybackOverlay() {
        mainHandler.removeCallbacks(hideOverlay)
        overlayModel.hide()
        if (::ui.isInitialized) ui.setOverlayVisible(false)
        swipeActionGate.reset()
    }

    private fun scheduleOverlayHide() {
        mainHandler.removeCallbacks(hideOverlay)
        mainHandler.postDelayed(hideOverlay, OVERLAY_AUTO_HIDE_MS)
    }

    private fun registerUsbReceiver() {
        ContextCompat.registerReceiver(this, receiver, IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(ACTION_USB_STATE)
        }, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    private fun updateConnectionSignals(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BATTERY_CHANGED -> {
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                usbPowered = plugged and BatteryManager.BATTERY_PLUGGED_USB != 0
            }
            ACTION_USB_STATE -> {
                usbDataConnected = intent.getBooleanExtra(USB_CONNECTED_EXTRA, false)
                usbDataConfigured = intent.getBooleanExtra(USB_CONFIGURED_EXTRA, false)
            }
        }
    }

    private fun currentIdleState() = idleGlassesState(UsbConnectionSignals(
        usbPowered = usbPowered,
        dataConnected = usbDataConnected,
        dataConfigured = usbDataConfigured,
        accessoryPresent = !usbManager.accessoryList.isNullOrEmpty(),
    ))

    private inner class PlayerGestures : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = true
        override fun onSingleTapConfirmed(event: MotionEvent): Boolean { routeInput(PlayerInput.SINGLE_TAP); return true }
        override fun onDoubleTap(event: MotionEvent): Boolean = true
        override fun onDoubleTapEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP) routeInput(PlayerInput.DOUBLE_TAP)
            return true
        }
        override fun onFling(first: MotionEvent?, second: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (first == null) return false
            val dx = second.x - first.x
            val dy = second.y - first.y
            if (abs(dx) < abs(dy) || abs(dx) < MIN_FLING_DISTANCE_PX) return false
            routeInput(if (dx > 0) PlayerInput.NEXT else PlayerInput.PREVIOUS)
            return true
        }
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.azhilin.rokid.displaybridge.glasses.action.USB_PERMISSION"
        const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"
        private const val USB_CONNECTED_EXTRA = "connected"
        private const val USB_CONFIGURED_EXTRA = "configured"
        private const val KEYCODE_SPRITE_DOUBLE_TAP = 202
        private const val KEYCODE_SWIPE_FORWARD = 183
        private const val KEYCODE_SWIPE_BACKWARD = 184
        private const val MIN_FLING_DISTANCE_PX = 80
        private const val OVERLAY_AUTO_HIDE_MS = 5_000L
        private const val EXIT_CONFIRMATION_TIMEOUT_MS = 3_000L
        private const val DISPLAY_WAKE_PULSE_MS = 15_000L
        private const val DISPLAY_WAKE_LOCK_MS = 10_000L
        private const val USER_EXIT_WRITE_TIMEOUT_MS = 500L
        private const val REQUEST_WIRELESS_PERMISSION = 24_018
        private const val TAG = "RdbGlassesMain"

        fun isBridgeIdentity(manufacturer: String?, model: String?, version: String?): Boolean = manufacturer == "Zhilin" &&
            model == "Rokid Display Bridge" && version == "1.0"

        internal fun directExitStopPayload(sessionId: ULong, detail: String): LiveStopPayload? =
            if (sessionId == 0uL) null else LiveStopPayload(sessionId, StopReason.USER, detail)

        internal fun overlayExitStopPayload(sessionId: ULong): LiveStopPayload =
            requireNotNull(directExitStopPayload(sessionId, "Glasses playback overlay Exit"))

        internal fun accessoryKey(accessory: UsbAccessory): String =
            "${accessory.manufacturer}\u0000${accessory.model}\u0000${accessory.version}\u0000${accessory.serial}"

        internal fun cableDetachHomeIntent(): Intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

}

internal class AccessoryPermissionGate {
    private var pendingKey: String? = null
    private var deniedKey: String? = null

    @Synchronized
    fun shouldRequest(key: String): Boolean {
        if (pendingKey == key || deniedKey == key) return false
        pendingKey = key
        return true
    }

    @Synchronized
    fun onResult(key: String) {
        if (pendingKey == key) pendingKey = null
        if (deniedKey == key) deniedKey = null
    }

    @Synchronized
    fun onDenied(key: String) {
        if (pendingKey == key) pendingKey = null
        deniedKey = key
    }

    @Synchronized
    fun reset() {
        pendingKey = null
        deniedKey = null
    }
}
