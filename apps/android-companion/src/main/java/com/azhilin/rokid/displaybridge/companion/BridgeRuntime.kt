package com.azhilin.rokid.displaybridge.companion

import com.azhilin.rokid.displaybridge.protocol.FramePriority
import com.azhilin.rokid.displaybridge.protocol.DisplayLayoutPayload
import com.azhilin.rokid.displaybridge.protocol.LiveFeedbackPayload
import com.azhilin.rokid.displaybridge.protocol.QueueStats
import java.util.concurrent.CopyOnWriteArrayList

enum class BridgeMode { IDLE, EXTERNAL_DISPLAY, SCREEN_MIRROR }
enum class ConnectionState { DISCONNECTED, USB_PERMISSION, SWITCHING_TO_AOA, HANDSHAKING, READY, ERROR }

data class BridgeSnapshot(
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val connectionDetail: String = "Waiting for Connect",
    val transport: String = "none",
    val wirelessStage: String = "idle",
    val wirelessPaired: Boolean = false,
    val aoaIdentity: String = BridgeIdentity.summary,
    val handshake: String = "not started",
    val encoder: String = "idle",
    val mode: BridgeMode = BridgeMode.IDLE,
    val display: String = "none",
    val audioRoute: String = "Android default",
    val thermalStatus: String = "unknown",
    val frameRate: Int = 0,
    val bitrate: Int = 0,
    val sentFrames: Long = 0,
    val droppedFrames: Long = 0,
    val queueDepth: Int = 0,
    val decodedFrames: Long = 0,
    val diagnostic: String = "Bridge initialized",
    val displayLayout: String = "scale=1000 x=0 y=0",
    val lastMediaControl: String = "none",
    val error: String? = null,
)

fun BridgeSnapshot.afterVideoQueued(stats: QueueStats): BridgeSnapshot = copy(
    droppedFrames = stats.droppedVideoFrames,
    queueDepth = stats.videoDepth,
)

fun BridgeSnapshot.afterSenderSuccess(priority: FramePriority, stats: QueueStats): BridgeSnapshot = copy(
    sentFrames = sentFrames + if (priority == FramePriority.VIDEO) 1 else 0,
    droppedFrames = stats.droppedVideoFrames,
    queueDepth = stats.videoDepth,
)

fun BridgeSnapshot.afterDecoderFeedback(feedback: LiveFeedbackPayload): BridgeSnapshot = copy(
    decodedFrames = feedback.decodedAccessUnits.toLong(),
    diagnostic = "Glasses queue=${feedback.queueDepthMs}ms decoded=${feedback.decodedAccessUnits} " +
        "dropped=${feedback.droppedVideoFrames}",
)

fun isCurrentLivePacket(session: ULong, generation: UInt, currentSession: ULong, currentGeneration: UInt): Boolean =
    session != 0uL && session == currentSession && generation == currentGeneration

object BridgeIdentity {
    const val manufacturer = "Zhilin"
    const val model = "Rokid Display Bridge"
    const val description = "Universal external display and screen mirror bridge for Rokid Glasses"
    const val version = "1.0"
    const val uri = "https://github.com/azhilin246/RokidDisplayBridge"
    const val serial = "rokid-display-bridge-v1"
    val strings = arrayOf(manufacturer, model, description, version, uri, serial)
    val summary = "$manufacturer / $model / $version / $serial"
}

object BridgeRuntime {
    private val listeners = CopyOnWriteArrayList<(BridgeSnapshot) -> Unit>()
    @Volatile private var current = BridgeSnapshot()
    @Volatile private var displayLayoutListener: ((DisplayLayoutPayload) -> Unit)? = null
    @Volatile private var companionVisible = false
    @Volatile private var companionVisibilityListener: ((Boolean) -> Unit)? = null

    fun snapshot(): BridgeSnapshot = current

    @Synchronized
    fun update(transform: (BridgeSnapshot) -> BridgeSnapshot) {
        current = transform(current)
        listeners.forEach { it(current) }
    }

    fun addListener(listener: (BridgeSnapshot) -> Unit) {
        listeners += listener
        listener(current)
    }

    fun removeListener(listener: (BridgeSnapshot) -> Unit) {
        listeners -= listener
    }

    fun setDisplayLayoutListener(listener: ((DisplayLayoutPayload) -> Unit)?) {
        displayLayoutListener = listener
    }

    fun submitDisplayLayout(value: DisplayLayoutPayload) {
        val effective = value.clamped()
        update { it.copy(displayLayout = effective.summary("pending")) }
        displayLayoutListener?.invoke(effective)
    }

    fun isCompanionVisible(): Boolean = companionVisible

    fun setCompanionVisible(visible: Boolean) {
        companionVisible = visible
        companionVisibilityListener?.invoke(visible)
    }

    fun setCompanionVisibilityListener(listener: ((Boolean) -> Unit)?) {
        companionVisibilityListener = listener
    }
}

class AutomaticExternalDisplayGate {
    @Volatile var pending: Boolean = false
        private set

    @Synchronized
    fun onCompanionVisible(mode: BridgeMode) {
        pending = mode == BridgeMode.IDLE
    }

    @Synchronized
    fun onCompanionHidden(connectionAttemptActive: Boolean) {
        if (!connectionAttemptActive) pending = false
    }

    @Synchronized
    fun cancel() {
        pending = false
    }

    @Synchronized
    fun consumeReady(mode: BridgeMode): Boolean {
        val shouldStart = pending && mode == BridgeMode.IDLE
        pending = false
        return shouldStart
    }
}

fun DisplayLayoutPayload.summary(state: String): String =
    "scale=$scalePermille x=$offsetXPermille y=$offsetYPermille ($state)"
