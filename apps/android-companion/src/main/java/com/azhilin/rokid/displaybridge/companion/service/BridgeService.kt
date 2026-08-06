package com.azhilin.rokid.displaybridge.companion.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.azhilin.rokid.displaybridge.companion.BridgeMode
import com.azhilin.rokid.displaybridge.companion.BridgeHandshake
import com.azhilin.rokid.displaybridge.companion.BridgeRuntime
import com.azhilin.rokid.displaybridge.companion.AutomaticExternalDisplayGate
import com.azhilin.rokid.displaybridge.companion.ConnectionState
import com.azhilin.rokid.displaybridge.companion.DisplayLayoutStore
import com.azhilin.rokid.displaybridge.companion.FavoriteCycleDirection
import com.azhilin.rokid.displaybridge.companion.FramePlacementFavoritesStore
import com.azhilin.rokid.displaybridge.companion.GestureSettingsStore
import com.azhilin.rokid.displaybridge.companion.MainActivity
import com.azhilin.rokid.displaybridge.companion.BridgePreviewFrames
import com.azhilin.rokid.displaybridge.companion.PreviewCodecConfig
import com.azhilin.rokid.displaybridge.companion.ModeTransition
import com.azhilin.rokid.displaybridge.companion.R
import com.azhilin.rokid.displaybridge.companion.afterDecoderFeedback
import com.azhilin.rokid.displaybridge.companion.afterSenderSuccess
import com.azhilin.rokid.displaybridge.companion.afterVideoQueued
import com.azhilin.rokid.displaybridge.companion.isCurrentLivePacket
import com.azhilin.rokid.displaybridge.companion.cycleGestureFavorite
import com.azhilin.rokid.displaybridge.companion.summary
import com.azhilin.rokid.displaybridge.companion.media.EncoderConfig
import com.azhilin.rokid.displaybridge.companion.media.EncoderStats
import com.azhilin.rokid.displaybridge.companion.media.AdaptiveVideoBitrate
import com.azhilin.rokid.displaybridge.companion.media.BitrateAdjustment
import com.azhilin.rokid.displaybridge.companion.media.PresentationDisplayController
import com.azhilin.rokid.displaybridge.companion.media.ProjectionController
import com.azhilin.rokid.displaybridge.companion.media.SurfaceToAvcStream
import com.azhilin.rokid.displaybridge.companion.usb.AndroidAoaConnection
import com.azhilin.rokid.displaybridge.companion.usb.AndroidAoaHost
import com.azhilin.rokid.displaybridge.companion.usb.UsbPermissionStage
import com.azhilin.rokid.displaybridge.protocol.DiagnosticPayload
import com.azhilin.rokid.displaybridge.protocol.DisplayLayoutPayload
import com.azhilin.rokid.displaybridge.protocol.ErrorCode
import com.azhilin.rokid.displaybridge.protocol.ErrorPayload
import com.azhilin.rokid.displaybridge.protocol.FeedbackFlags
import com.azhilin.rokid.displaybridge.protocol.Frame
import com.azhilin.rokid.displaybridge.protocol.FramePriority
import com.azhilin.rokid.displaybridge.protocol.HelloPayload
import com.azhilin.rokid.displaybridge.protocol.LiveConfigPayload
import com.azhilin.rokid.displaybridge.protocol.LiveDiscontinuityPayload
import com.azhilin.rokid.displaybridge.protocol.LiveFeedbackPayload
import com.azhilin.rokid.displaybridge.protocol.LiveFrameQueue
import com.azhilin.rokid.displaybridge.protocol.LivePayloads
import com.azhilin.rokid.displaybridge.protocol.LiveReadyPayload
import com.azhilin.rokid.displaybridge.protocol.LiveSourceKind
import com.azhilin.rokid.displaybridge.protocol.LiveStartPayload
import com.azhilin.rokid.displaybridge.protocol.LiveStopPayload
import com.azhilin.rokid.displaybridge.protocol.MessageType
import com.azhilin.rokid.displaybridge.protocol.MediaCommand
import com.azhilin.rokid.displaybridge.protocol.QueuedFrame
import com.azhilin.rokid.displaybridge.protocol.SampleFlags
import com.azhilin.rokid.displaybridge.protocol.StopReason
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class BridgeService : Service(), AndroidAoaHost.Listener {
    private lateinit var aoaHost: AndroidAoaHost
    @Volatile private var connection: AndroidAoaConnection? = null
    private val handshake = BridgeHandshake()
    private val queue = LiveFrameQueue()
    private val senderRunning = AtomicBoolean(false)
    private var senderThread: Thread? = null
    private val nextSessionId = AtomicLong(10_000)
    private val nextAccessUnitId = AtomicLong(1)
    private val nextControlRequestId = AtomicInteger(2)
    private val sentVideoUnits = AtomicLong(0)
    private val sentVideoBytes = AtomicLong(0)
    @Volatile private var pendingLayoutRequestId: UInt? = null
    @Volatile private var pendingGestureSettingsRequestId: UInt? = null
    @Volatile private var sessionId: ULong = 0u
    @Volatile private var generation: UInt = 0u
    @Volatile private var acceptingSamples = false
    private var encoder: SurfaceToAvcStream? = null
    private var presentation: PresentationDisplayController? = null
    private var projectionController: ProjectionController? = null
    private var pendingProjection: MediaProjection? = null
    private lateinit var powerManager: PowerManager
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private lateinit var displayLayoutStore: DisplayLayoutStore
    private lateinit var gestureSettingsStore: GestureSettingsStore
    private lateinit var favoritesStore: FramePlacementFavoritesStore
    private val displayLayoutListener: (DisplayLayoutPayload) -> Unit = ::applyDisplayLayout
    private val automaticExternalDisplay = AutomaticExternalDisplayGate()
    @Volatile private var companionVisible = false
    private var adaptiveBitrate = AdaptiveVideoBitrate()
    @Volatile private var previewConfig: PreviewCodecConfig? = null

    private val transition = ModeTransition(object : ModeTransition.Cleanup {
        override fun stopProducer() { acceptingSamples = false }
        override fun stopSamples() { Log.i(TAG, "sample production stopped session=$sessionId generation=$generation") }
        override fun sendLiveStop(previous: BridgeMode) {
            val currentSession = sessionId
            if (currentSession != 0uL) runCatching {
                connection?.takeIf { it.isOpen }?.send(Frame(MessageType.LIVE_STOP, payload = LivePayloads.encodeStop(
                    LiveStopPayload(currentSession, StopReason.REPLACED, "leaving ${previous.name}"))))
            }.onFailure { Log.w(TAG, "LIVE_STOP send failed: ${it.message}") }
        }
        override fun releaseSource(previous: BridgeMode) {
            runCatching { presentation?.close() }; presentation = null
            runCatching { projectionController?.close() }; projectionController = null
            runCatching { pendingProjection?.stop() }; pendingProjection = null
        }
        override fun releaseEncoder() {
            previewConfig = null
            BridgePreviewFrames.clear()
            runCatching { encoder?.close() }
            encoder = null
        }
        override fun clearQueues() { queue.clear() }
        override fun invalidateIdentity() {
            sessionId = 0u; generation = 0u; nextAccessUnitId.set(1)
            BridgeRuntime.update { it.copy(mode = BridgeMode.IDLE, encoder = "idle", display = "none", frameRate = 0,
                bitrate = 0, queueDepth = 0, diagnostic = "Mode stopped") }
        }
    })

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        displayLayoutStore = DisplayLayoutStore(this)
        gestureSettingsStore = GestureSettingsStore(this)
        favoritesStore = FramePlacementFavoritesStore(this)
        val storedLayout = displayLayoutStore.load()
        BridgeRuntime.setDisplayLayoutListener(displayLayoutListener)
        BridgeRuntime.update { it.copy(audioRoute = describeAudioRoute(), displayLayout = storedLayout.summary("stored")) }
        aoaHost = AndroidAoaHost(this, this).also { it.frameListener = ::onFrame }
        BridgeRuntime.setCompanionVisibilityListener(::onCompanionVisibilityChanged)
        BridgePreviewFrames.setActivationListener { enabled ->
            mainExecutor.execute {
                if (enabled) {
                    previewConfig?.let(BridgePreviewFrames::publishConfig)
                    encoder?.requestKeyFrame()
                } else BridgePreviewFrames.clear()
            }
        }
        startSender()
        powerManager = getSystemService(PowerManager::class.java)
        if (Build.VERSION.SDK_INT >= 29) {
            thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
                BridgeRuntime.update { it.copy(thermalStatus = thermalName(status)) }
                if (status >= PowerManager.THERMAL_STATUS_CRITICAL && transition.mode != BridgeMode.IDLE) {
                    fail("Phone thermal state is critical; live video stopped")
                    transition.stop()
                }
            }.also { powerManager.addThermalStatusListener(mainExecutor, it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startBridgeForeground(mediaProjection = intent?.action == ACTION_START_SCREEN_MIRROR)
        runCatching {
            when (intent?.action) {
                ACTION_COMPANION_VISIBILITY -> onCompanionVisibilityChanged(BridgeRuntime.isCompanionVisible())
                ACTION_CONNECT -> {
                    automaticExternalDisplay.onCompanionVisible(transition.mode)
                    aoaHost.connect()
                }
                ACTION_DISCONNECT -> {
                    automaticExternalDisplay.cancel()
                    transition.stop()
                    connection = null
                    handshake.reset()
                    aoaHost.disconnect()
                    BridgeRuntime.update { it.copy(
                        connection = ConnectionState.DISCONNECTED,
                        connectionDetail = "Disconnected by user",
                        handshake = "disconnected",
                        diagnostic = "AOA connection closed by user",
                        error = null,
                    ) }
                }
                ACTION_CANCEL_AUTO_EXTERNAL -> automaticExternalDisplay.cancel()
                ACTION_APPLY_GESTURE_SETTINGS -> sendGestureSettingsIfConnected()
                ACTION_START_EXTERNAL_DISPLAY -> {
                    automaticExternalDisplay.cancel()
                    startExternalDisplayMode()
                }
                ACTION_START_SCREEN_MIRROR -> {
                    automaticExternalDisplay.cancel()
                    startProjectionMode(
                        intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, 0),
                        projectionIntent(intent) ?: error("MediaProjection consent data is missing"),
                    )
                }
                ACTION_STOP -> {
                    automaticExternalDisplay.cancel()
                    transition.stop()
                }
                ACTION_STOP_SERVICE -> {
                    automaticExternalDisplay.cancel()
                    transition.stop()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }.onFailure { fail(it.message ?: it.javaClass.simpleName) }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        connection = null
        BridgeRuntime.setDisplayLayoutListener(null)
        BridgeRuntime.setCompanionVisibilityListener(null)
        BridgePreviewFrames.setActivationListener(null)
        BridgePreviewFrames.clear()
        automaticExternalDisplay.cancel()
        handshake.reset()
        transition.stop()
        if (Build.VERSION.SDK_INT >= 29) thermalListener?.let(powerManager::removeThermalStatusListener)
        thermalListener = null
        senderRunning.set(false); queue.wake(); senderThread?.interrupt(); senderThread = null
        aoaHost.close()
        BridgeRuntime.update { it.copy(connection = ConnectionState.DISCONNECTED, handshake = "service stopped",
            connectionDetail = "Bridge service stopped") }
        super.onDestroy()
    }

    override fun onState(message: String) {
        val state = if (message.contains("Switching") || message.contains("requested")) ConnectionState.SWITCHING_TO_AOA
        else ConnectionState.DISCONNECTED
        BridgeRuntime.update { it.copy(connection = state, connectionDetail = message, error = null) }
    }

    override fun onPermissionRequired(stage: UsbPermissionStage, deviceId: Int) {
        val ordinal = if (stage == UsbPermissionStage.DEVICE_TO_AOA) "1/2" else "2/2"
        BridgeRuntime.update { it.copy(
            connection = ConnectionState.USB_PERMISSION,
            connectionDetail = "Approve USB access ($ordinal: ${stage.diagnosticName}, deviceId=$deviceId)",
            diagnostic = "One request is coalesced for this enumerated USB device; AOA may re-enumerate with a new deviceId",
        ) }
        Log.i(TAG, "USB permission requested stage=${stage.name} deviceId=$deviceId")
    }

    override fun onConnection(connection: AndroidAoaConnection) {
        this.connection = connection
        handshake.reset()
        BridgeRuntime.update { it.copy(connection = ConnectionState.HANDSHAKING, connectionDetail = "Bridge AOA connected",
            handshake = "DBP1 HELLO sent", error = null) }
        enqueueControl(Frame(MessageType.HELLO, requestId = 1u, payload = LivePayloads.encodeHello(HelloPayload())))
    }

    override fun onDisconnected(message: String) {
        connection = null
        handshake.reset()
        transition.stop()
        if (companionVisible) automaticExternalDisplay.onCompanionVisible(BridgeMode.IDLE)
        else automaticExternalDisplay.cancel()
        BridgeRuntime.update { it.copy(connection = ConnectionState.DISCONNECTED, connectionDetail = message,
            handshake = "detached", diagnostic = "USB detach cleanup completed") }
    }

    override fun onError(message: String) {
        if (message.contains("permission denied", ignoreCase = true)) automaticExternalDisplay.cancel()
        fail(message)
    }

    private fun onFrame(frame: Frame) {
        runCatching {
            when (frame.type) {
                MessageType.HELLO_ACK -> {
                    val hello = LivePayloads.decodeHello(frame.payload)
                    val peerMaxPayload = handshake.accept(hello)
                    BridgeRuntime.update { it.copy(connection = ConnectionState.READY, connectionDetail = "Rokid glasses ready",
                        handshake = "DBP1 ready, maxPayload=$peerMaxPayload", diagnostic = "Video-only bridge handshake complete", error = null) }
                    sendDisplayLayout(displayLayoutStore.load())
                    sendGestureSettingsIfConnected()
                    if (automaticExternalDisplay.consumeReady(transition.mode)) {
                        mainExecutor.execute {
                            if (handshake.complete && transition.mode == BridgeMode.IDLE) {
                                runCatching { startExternalDisplayMode() }
                                    .onFailure { fail("Automatic External Display: ${it.message}") }
                            }
                        }
                    }
                }
                MessageType.DISPLAY_LAYOUT_ACK -> {
                    val effective = LivePayloads.decodeDisplayLayout(frame.payload)
                    if (frame.requestId == pendingLayoutRequestId) {
                        pendingLayoutRequestId = null
                        displayLayoutStore.save(effective)
                        BridgeRuntime.update { it.copy(displayLayout = effective.summary("applied"),
                            diagnostic = "Glasses acknowledged display layout") }
                    }
                }
                MessageType.GESTURE_SETTINGS_ACK -> {
                    val effective = LivePayloads.decodeGestureSettings(frame.payload)
                    if (frame.requestId == pendingGestureSettingsRequestId) {
                        pendingGestureSettingsRequestId = null
                        BridgeRuntime.update { it.copy(diagnostic = "Glasses acknowledged gesture settings: " +
                            "swipe=${effective.swipeAction} tap=${effective.tapAction} double=${effective.doubleTapAction}") }
                    }
                }
                MessageType.MEDIA_KEY -> {
                    val command = LivePayloads.decodeMediaKey(frame.payload)
                    if (isCurrent(command.sessionId, command.generation)) dispatchMediaCommand(command.command)
                    else BridgeRuntime.update { it.copy(lastMediaControl = "ignored stale ${command.command}",
                        diagnostic = "Ignored stale glasses media command") }
                }
                MessageType.LIVE_READY -> onLiveReady(LivePayloads.decodeReady(frame.payload))
                MessageType.LIVE_FEEDBACK -> onFeedback(LivePayloads.decodeFeedback(frame.payload))
                MessageType.DIAGNOSTIC -> LivePayloads.decodeDiagnostic(frame.payload).let { diagnostic ->
                    if (diagnostic.sessionId == 0uL || diagnostic.sessionId == sessionId) {
                        BridgeRuntime.update { it.copy(diagnostic = "Glasses: ${diagnostic.detail}") }
                    }
                }
                MessageType.LIVE_STOP -> LivePayloads.decodeStop(frame.payload).let { stop ->
                    if (stop.sessionId == sessionId) {
                        transition.stop()
                        BridgeRuntime.update { snapshot -> snapshot.copy(
                            error = stop.detail.takeIf { stop.reason == StopReason.ERROR },
                            diagnostic = "Glasses stopped live session: ${stop.reason} ${stop.detail}",
                        ) }
                    }
                }
                MessageType.ERROR -> LivePayloads.decodeError(frame.payload).let { fail("Glasses ${it.code}: ${it.detail}") }
                else -> Unit
            }
        }.onFailure { fail("Protocol: ${it.message}") }
    }

    private fun startExternalDisplayMode() {
        check(handshake.complete) { "Connect the bridge to the glasses first" }
        transition.switchTo(BridgeMode.EXTERNAL_DISPLAY) {
            beginSession(LiveSourceKind.PRESENTATION_DISPLAY, "Android external display")
        }
    }

    private fun onCompanionVisibilityChanged(visible: Boolean) {
        if (companionVisible == visible) return
        companionVisible = visible
        if (visible) {
            automaticExternalDisplay.onCompanionVisible(transition.mode)
            aoaHost.setAutoConnectEnabled(true)
            BridgeRuntime.update { it.copy(diagnostic = "Companion open: automatic USB connect and External Display armed") }
        } else {
            aoaHost.setAutoConnectEnabled(false)
            automaticExternalDisplay.onCompanionHidden(aoaHost.hasActiveConnectionAttempt)
        }
    }

    private fun startProjectionMode(resultCode: Int, data: Intent) {
        check(handshake.complete) { "Connect the bridge to the glasses first" }
        val manager = getSystemService(MediaProjectionManager::class.java)
        val projection = manager.getMediaProjection(resultCode, data) ?: error("MediaProjection consent was not granted")
        try {
            transition.switchTo(BridgeMode.SCREEN_MIRROR) {
                pendingProjection = projection
                beginSession(LiveSourceKind.SCREEN_CAPTURE, "Android screen mirror")
            }
        } catch (error: Exception) {
            if (pendingProjection === projection) pendingProjection = null
            runCatching { projection.stop() }
            throw error
        }
    }

    private fun beginSession(source: LiveSourceKind, title: String) {
        sessionId = nextSessionId.getAndIncrement().toULong()
        generation = 1u
        nextAccessUnitId.set(1)
        sentVideoUnits.set(0)
        sentVideoBytes.set(0)
        adaptiveBitrate = AdaptiveVideoBitrate()
        acceptingSamples = false
        enqueueControl(Frame(MessageType.LIVE_START, payload = LivePayloads.encodeStart(
            LiveStartPayload(sessionId, generation, source, title))))
        val mode = if (source == LiveSourceKind.PRESENTATION_DISPLAY) BridgeMode.EXTERNAL_DISPLAY else BridgeMode.SCREEN_MIRROR
        BridgeRuntime.update { it.copy(mode = mode, encoder = "waiting for glasses LIVE_READY", display = "pending",
            sentFrames = 0, droppedFrames = 0, decodedFrames = 0, queueDepth = 0,
            diagnostic = "LIVE_START session=$sessionId generation=$generation source=$source", error = null) }
    }

    private fun onLiveReady(ready: LiveReadyPayload) {
        if (ready.sessionId != sessionId || ready.generation != generation || transition.mode == BridgeMode.IDLE) return
        check(encoder == null) { "encoder already exists" }
        val activeSession = sessionId
        var configPublished = false
        val stream = SurfaceToAvcStream(listener = object : SurfaceToAvcStream.Listener {
            override fun onConfig(config: EncoderConfig) {
                if (!isCurrentSession(activeSession)) return
                if (configPublished) {
                    acceptingSamples = false
                    queue.clearVideo()
                    generation = nextGeneration(generation)
                    nextAccessUnitId.set(1)
                    enqueueControl(Frame(MessageType.LIVE_DISCONTINUITY, payload = LivePayloads.encodeDiscontinuity(
                        LiveDiscontinuityPayload(activeSession, generation))))
                }
                enqueueControl(Frame(MessageType.LIVE_CONFIG, payload = LivePayloads.encodeConfig(
                    LiveConfigPayload(activeSession, generation, config.width.toUShort(), config.height.toUShort(),
                        config.frameRate.toUShort(), config.bitrate.toUInt(), config.profile.toUInt(), config.level.toUInt(),
                        config.csd0, config.csd1))))
                configPublished = true
                acceptingSamples = true
                previewConfig = PreviewCodecConfig(
                    activeSession,
                    generation,
                    config.width,
                    config.height,
                    config.csd0,
                    config.csd1,
                )
                if (BridgePreviewFrames.isEnabled) {
                    previewConfig?.let(BridgePreviewFrames::publishConfig)
                    encoder?.requestKeyFrame()
                }
                BridgeRuntime.update { it.copy(encoder = "${config.codecName} ${config.width}x${config.height}",
                    frameRate = config.frameRate, bitrate = config.bitrate,
                    diagnostic = "AVC config generation=$generation profile=${config.profile} level=${config.level} " +
                        "bitrateMode=${config.bitrateMode} latencyFrames=${config.latencyFrames} " +
                        "realtime=${config.realtimeTuning} csd=${config.csd0.size}/${config.csd1.size}") }
            }

            override fun onAccessUnit(bytes: ByteArray, presentationTimeUs: Long, keyFrame: Boolean, endOfStream: Boolean) {
                if (!acceptingSamples || !isCurrentSession(activeSession)) return
                val sampleGeneration = generation
                if (keyFrame && BridgePreviewFrames.isEnabled) {
                    BridgePreviewFrames.offer(
                        bytes,
                        presentationTimeUs,
                        activeSession,
                        sampleGeneration,
                        SystemClock.elapsedRealtime(),
                    )
                }
                val flags = ((if (keyFrame) SampleFlags.KEY_FRAME.toInt() else 0) or
                    (if (endOfStream) SampleFlags.END_OF_STREAM.toInt() else 0)).toUByte()
                val id = nextAccessUnitId.getAndIncrement().toUInt().takeIf { it != 0u } ?: 1u
                val frames = LivePayloads.fragment(activeSession, sampleGeneration, flags, id,
                    presentationTimeUs.coerceAtLeast(0).toULong(), bytes, handshake.maxPayload)
                    .map { Frame(MessageType.LIVE_SAMPLE, payload = LivePayloads.encodeSample(it)) }
                val outcome = queue.offer(QueuedFrame(frames, FramePriority.VIDEO, keyFrame))
                if (outcome.keyFrameRequired) {
                    encoder?.requestKeyFrame()
                    adaptiveBitrate.onQueuePressure(SystemClock.elapsedRealtime())?.let(::applyBitrateAdjustment)
                    BridgeRuntime.update { it.copy(
                        diagnostic = "USB video queue pressure; stale GOP dropped and keyframe requested",
                    ) }
                    Log.w(TAG, "video queue pressure session=$activeSession generation=$sampleGeneration; requesting keyframe")
                }
                val stats = queue.stats()
                BridgeRuntime.update { it.afterVideoQueued(stats) }
            }

            override fun onStats(stats: EncoderStats) {
                Log.i(TAG, "encoder output frames=${stats.outputFrames} bytes=${stats.outputBytes}")
            }

            override fun onError(message: String) {
                fail("Encoder: $message")
                transition.stop()
            }
        })
        encoder = stream
        val surface = stream.start()
        when (transition.mode) {
            BridgeMode.EXTERNAL_DISPLAY -> {
                val display = PresentationDisplayController(this, surface)
                presentation = display
                BridgeRuntime.update { it.copy(display = display.info.diagnostic(), encoder = "${stream.codecName} running",
                    diagnostic = "Public presentation display ready for compatible applications") }
                check(display.info.inPresentationCategory) { "VirtualDisplay is absent from presentation category" }
                check(!PresentationDisplayController.hasForbiddenFlags(display.info.requestedFlags)) { "VirtualDisplay has forbidden flags" }
            }
            BridgeMode.SCREEN_MIRROR -> {
                val projection = pendingProjection ?: error("MediaProjection instance was not retained")
                pendingProjection = null
                projectionController = ProjectionController(projection, surface) {
                    mainExecutor.execute {
                        if (transition.mode == BridgeMode.SCREEN_MIRROR) {
                            transition.stop()
                            BridgeRuntime.update { it.copy(
                                diagnostic = "MediaProjection stopped by Android; fresh consent is required",
                                error = "Screen Mirror stopped by Android. Unlock the phone if needed, then start Screen Mirror again.",
                            ) }
                        }
                    }
                }
                BridgeRuntime.update { it.copy(display = "MediaProjection 480x640", encoder = "${stream.codecName} running",
                    diagnostic = "Screen Mirror active; protected/DRM content may be black") }
            }
            BridgeMode.IDLE -> error("mode ended before LIVE_READY")
        }
        acceptingSamples = true
    }

    private fun onFeedback(feedback: LiveFeedbackPayload) {
        if (!isCurrent(feedback.sessionId, feedback.generation)) return
        if (feedback.flags.toUInt() and FeedbackFlags.REQUEST_KEY_FRAME.toUInt() != 0u) encoder?.requestKeyFrame()
        adaptiveBitrate.onDecoderFeedback(feedback.queueDepthMs.toInt(), feedback.droppedVideoFrames.toLong(),
            SystemClock.elapsedRealtime())?.let(::applyBitrateAdjustment)
        BridgeRuntime.update { it.afterDecoderFeedback(feedback) }
        Log.i(TAG, "decoder feedback session=${feedback.sessionId} generation=${feedback.generation} " +
            "depthMs=${feedback.queueDepthMs} decoded=${feedback.decodedAccessUnits} " +
            "dropped=${feedback.droppedVideoFrames} flags=${feedback.flags} bitrate=${adaptiveBitrate.currentBitrate}")
    }

    private fun applyBitrateAdjustment(adjustment: BitrateAdjustment) {
        if (encoder?.updateBitrate(adjustment.bitrate) == true) {
            BridgeRuntime.update { it.copy(
                bitrate = adjustment.bitrate,
                diagnostic = "AVC bitrate ${adjustment.bitrate / 1_000} kbps: ${adjustment.reason}",
            ) }
            Log.i(TAG, "adaptive bitrate=${adjustment.bitrate} reason=${adjustment.reason}")
        }
    }

    private fun applyDisplayLayout(value: DisplayLayoutPayload) {
        val effective = value.clamped()
        displayLayoutStore.save(effective)
        BridgeRuntime.update { it.copy(displayLayout = effective.summary(if (handshake.complete) "sending" else "stored")) }
        if (handshake.complete) sendDisplayLayout(effective)
    }

    private fun sendDisplayLayout(value: DisplayLayoutPayload) {
        val requestId = nextControlRequestId.getAndIncrement().toUInt().takeIf { it != 0u } ?: 2u
        pendingLayoutRequestId = requestId
        enqueueControl(Frame(MessageType.DISPLAY_LAYOUT, requestId = requestId,
            payload = LivePayloads.encodeDisplayLayout(value)))
    }

    private fun sendGestureSettingsIfConnected() {
        if (!handshake.complete) return
        val requestId = nextControlRequestId.getAndIncrement().toUInt().takeIf { it != 0u } ?: 2u
        pendingGestureSettingsRequestId = requestId
        val policy = gestureSettingsStore.load().policy
        enqueueControl(Frame(MessageType.GESTURE_SETTINGS, requestId = requestId,
            payload = LivePayloads.encodeGestureSettings(policy)))
        BridgeRuntime.update { it.copy(diagnostic = "Gesture settings sent to glasses") }
    }

    private fun dispatchMediaCommand(command: MediaCommand) {
        if (command == MediaCommand.FAVORITE_PREVIOUS || command == MediaCommand.FAVORITE_NEXT) {
            applyFavoriteCommand(command)
            return
        }
        val keyCode = mediaKeyCode(command)
        val audioManager = getSystemService(AudioManager::class.java)
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        BridgeRuntime.update { it.copy(lastMediaControl = "$command -> keyCode=$keyCode",
            diagnostic = "Glasses media command dispatched through Android") }
        Log.i(TAG, "media command=$command keyCode=$keyCode")
    }

    private fun applyFavoriteCommand(command: MediaCommand) {
        val settings = gestureSettingsStore.load()
        val direction = if (command == MediaCommand.FAVORITE_NEXT) FavoriteCycleDirection.NEXT
            else FavoriteCycleDirection.PREVIOUS
        val favorite = cycleGestureFavorite(
            favoritesStore.load(),
            settings.disabledFavoriteIds,
            displayLayoutStore.load(),
            direction,
        )
        if (favorite == null) {
            BridgeRuntime.update { it.copy(lastMediaControl = "$command -> no enabled favorites",
                diagnostic = "Favorite gesture ignored: no enabled frame favorites") }
            return
        }
        applyDisplayLayout(favorite.layout)
        BridgeRuntime.update { it.copy(lastMediaControl = "$command -> favorite=${favorite.name}",
            diagnostic = "Glasses gesture applied frame favorite: ${favorite.name}") }
        Log.i(TAG, "favorite command=$command id=${favorite.id} name=${favorite.name}")
    }

    private fun isCurrent(session: ULong, generation: UInt) =
        isCurrentLivePacket(session, generation, sessionId, this.generation)
    private fun isCurrentSession(session: ULong) = session != 0uL && session == sessionId && transition.mode != BridgeMode.IDLE

    private fun enqueueControl(frame: Frame) {
        if (!queue.offer(QueuedFrame(listOf(frame), FramePriority.CONTROL)).accepted) {
            throw IOException("DBP1 control queue is full")
        }
    }

    private fun startSender() {
        if (!senderRunning.compareAndSet(false, true)) return
        senderThread = Thread({
            while (senderRunning.get()) {
                val pending = try { queue.take(senderRunning::get) } catch (_: InterruptedException) { null } ?: continue
                val target = connection ?: continue
                runCatching { pending.frames.forEach { target.send(it) } }
                    .onSuccess {
                        val stats = queue.stats()
                        BridgeRuntime.update { it.afterSenderSuccess(pending.priority, stats) }
                        if (pending.priority == FramePriority.VIDEO) {
                            val count = sentVideoUnits.incrementAndGet()
                            val bytes = pending.frames.sumOf { it.payload.size.toLong() }
                            val totalBytes = sentVideoBytes.addAndGet(bytes)
                            if (count <= 3L || count % 30L == 0L) {
                                Log.i(TAG, "USB video sent units=$count payloadBytes=$totalBytes " +
                                    "fragments=${pending.frames.size} key=${pending.keyFrame} queue=${stats.videoDepth} " +
                                    "dropped=${stats.droppedVideoFrames} waitingKey=${stats.waitingForKeyFrame}")
                            }
                        }
                    }
                    .onFailure { error ->
                        Log.w(TAG, "DBP1 sender failed: ${error.message}")
                        BridgeRuntime.update { it.copy(error = "USB sender: ${error.message}") }
                    }
            }
        }, "rdb-usb-sender").apply { start() }
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        BridgeRuntime.update { it.copy(connection = if (connection == null) ConnectionState.ERROR else it.connection,
            error = message, diagnostic = message) }
        if (connection?.isOpen == true) {
            runCatching { enqueueControl(Frame(MessageType.ERROR,
                payload = LivePayloads.encodeError(ErrorPayload(ErrorCode.INTERNAL, message)))) }
        }
    }

    private fun startBridgeForeground(mediaProjection: Boolean) {
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            if (mediaProjection) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_bridge_launcher)
        .setContentTitle("Display Bridge")
        .setContentText("Video-only USB bridge · audio stays on phone")
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .addAction(0, "Stop", PendingIntent.getService(this, 1, Intent(this, BridgeService::class.java).setAction(ACTION_STOP_SERVICE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .build()

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Rokid display bridge session", NotificationManager.IMPORTANCE_LOW))
    }

    private fun describeAudioRoute(): String {
        val manager = getSystemService(AudioManager::class.java)
        val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val preferred = outputs.minByOrNull { routePriority(it.type) }
        return preferred?.let { "Android route candidate: ${it.productName} (${audioType(it.type)})" } ?: "Android route unavailable"
    }

    @Suppress("DEPRECATION")
    private fun projectionIntent(intent: Intent): Intent? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
    } else intent.getParcelableExtra(EXTRA_PROJECTION_DATA)

    private fun thermalName(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "unknown($status)"
    }

    companion object {
        const val ACTION_CONNECT = "com.azhilin.rokid.displaybridge.companion.action.CONNECT"
        const val ACTION_DISCONNECT = "com.azhilin.rokid.displaybridge.companion.action.DISCONNECT"
        const val ACTION_COMPANION_VISIBILITY = "com.azhilin.rokid.displaybridge.companion.action.COMPANION_VISIBILITY"
        const val ACTION_CANCEL_AUTO_EXTERNAL = "com.azhilin.rokid.displaybridge.companion.action.CANCEL_AUTO_EXTERNAL"
        const val ACTION_APPLY_GESTURE_SETTINGS = "com.azhilin.rokid.displaybridge.companion.action.APPLY_GESTURE_SETTINGS"
        const val ACTION_START_EXTERNAL_DISPLAY = "com.azhilin.rokid.displaybridge.companion.action.START_EXTERNAL_DISPLAY"
        const val ACTION_START_SCREEN_MIRROR = "com.azhilin.rokid.displaybridge.companion.action.START_SCREEN_MIRROR"
        const val ACTION_STOP = "com.azhilin.rokid.displaybridge.companion.action.STOP"
        const val ACTION_STOP_SERVICE = "com.azhilin.rokid.displaybridge.companion.action.STOP_SERVICE"
        const val EXTRA_PROJECTION_RESULT_CODE = "com.azhilin.rokid.displaybridge.companion.extra.PROJECTION_RESULT_CODE"
        const val EXTRA_PROJECTION_DATA = "com.azhilin.rokid.displaybridge.companion.extra.PROJECTION_DATA"
        private const val CHANNEL_ID = "rokid_display_bridge_session"
        private const val NOTIFICATION_ID = 24_017
        private const val TAG = "RdbBridgeService"

        fun nextGeneration(current: UInt): UInt = if (current == UInt.MAX_VALUE) 1u else current + 1u

        fun mediaKeyCode(command: MediaCommand): Int = when (command) {
            MediaCommand.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            MediaCommand.SEEK_BACKWARD -> KeyEvent.KEYCODE_MEDIA_REWIND
            MediaCommand.SEEK_FORWARD -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            MediaCommand.PLAYLIST_PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            MediaCommand.PLAYLIST_NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaCommand.FAVORITE_PREVIOUS, MediaCommand.FAVORITE_NEXT ->
                error("Favorite commands are handled through frame placement")
        }

        private fun routePriority(type: Int): Int = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> 0
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> 1
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 2
            else -> 3
        }

        private fun audioType(type: Int): String = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE headset"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "phone speaker"
            else -> "type $type"
        }
    }
}
