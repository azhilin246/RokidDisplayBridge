package com.azhilin.rokid.displaybridge.companion

import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.app.Activity
import android.view.KeyEvent
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.azhilin.rokid.displaybridge.companion.media.PresentationDisplayController
import com.azhilin.rokid.displaybridge.companion.media.SurfaceToAvcStream
import com.azhilin.rokid.displaybridge.companion.media.AdaptiveVideoBitrate
import com.azhilin.rokid.displaybridge.companion.service.BridgeService
import com.azhilin.rokid.displaybridge.companion.usb.AoaBulkWriter
import com.azhilin.rokid.displaybridge.companion.usb.UsbDeviceIdentity
import com.azhilin.rokid.displaybridge.companion.usb.UsbPermissionStage
import com.azhilin.rokid.displaybridge.companion.usb.selectTargetUsbDevice
import com.azhilin.rokid.displaybridge.companion.usb.shouldConnectOnAttach
import com.azhilin.rokid.displaybridge.companion.usb.usbPermissionStage
import com.azhilin.rokid.displaybridge.protocol.FeedbackFlags
import com.azhilin.rokid.displaybridge.protocol.DisplayLayoutPayload
import com.azhilin.rokid.displaybridge.protocol.FramePriority
import com.azhilin.rokid.displaybridge.protocol.HelloPayload
import com.azhilin.rokid.displaybridge.protocol.LiveFeedbackPayload
import com.azhilin.rokid.displaybridge.protocol.MediaCommand
import com.azhilin.rokid.displaybridge.protocol.QueueStats
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton

@RunWith(RobolectricTestRunner::class)
class CompanionUnitTest {
    @Test fun `Material dashboard prioritizes health modes and live metrics`() {
        val controller = Robolectric.buildActivity(Activity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        val activity = controller.setup().get()
        val dashboard = CompanionDashboard(
            activity,
            DisplayLayoutStore(activity),
            CompanionDashboard.Actions({}, {}, {}, {}, {}),
        )

        assertTrue(dashboard.root.findViewById<View>(R.id.connection_card) is MaterialCardView)
        assertTrue(dashboard.root.findViewById<View>(R.id.metrics_card) is MaterialCardView)
        assertTrue(dashboard.root.findViewById<View>(R.id.frame_card) is MaterialCardView)
        assertTrue(dashboard.root.findViewById<View>(R.id.gesture_settings_card) is MaterialCardView)
        assertTrue(dashboard.root.findViewById<View>(R.id.technical_card) is MaterialCardView)
        assertTrue(dashboard.root.findViewById<View>(R.id.mode_external).contentDescription == null)

        dashboard.render(BridgeSnapshot(
            connection = ConnectionState.ERROR,
            mode = BridgeMode.SCREEN_MIRROR,
            frameRate = 29,
            bitrate = 1_200_000,
            queueDepth = 2,
            sentFrames = 100,
            decodedFrames = 96,
            droppedFrames = 4,
            error = "Reconnect the USB cable",
        ))
        assertEquals(View.VISIBLE, dashboard.root.findViewById<View>(R.id.error_card).visibility)
        val technical = dashboard.root.findViewById<android.widget.TextView>(R.id.technical_details).text.toString()
        assertTrue(technical.contains("SCREEN_MIRROR"))
        assertTrue(technical.contains("sent=100 decoded=96 dropped=4"))
        assertFalse(technical.contains("preview", true))
    }

    @Test fun `dashboard removes duplicate branding and puts metrics under technical details`() {
        val controller = Robolectric.buildActivity(Activity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        val activity = controller.setup().get()
        var framePlacementRequests = 0
        val dashboard = CompanionDashboard(
            activity,
            DisplayLayoutStore(activity),
            CompanionDashboard.Actions({}, {}, {}, {}, {}, framePlacement = { framePlacementRequests++ }),
        )

        assertEquals("Display Bridge", dashboard.root.findViewById<android.widget.TextView>(R.id.header_title).text)
        assertFalse(allText(dashboard.root).any { it == "DB" || it.contains("Universal external display", true) })
        val technical = dashboard.root.findViewById<View>(R.id.technical_card) as android.view.ViewGroup
        assertTrue(isDescendant(technical, dashboard.root.findViewById(R.id.metrics_card)))
        assertFalse(allText(dashboard.root).any { it == "Stop bridge mode" || it.contains("Video only", true) })
        dashboard.root.findViewById<View>(R.id.frame_placement_action).performClick()
        assertEquals(1, framePlacementRequests)
    }

    @Test fun `connection action is filled and changes to red disconnect when ready`() {
        val controller = Robolectric.buildActivity(Activity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        val activity = controller.setup().get()
        var connects = 0
        var disconnects = 0
        val dashboard = CompanionDashboard(
            activity,
            DisplayLayoutStore(activity),
            CompanionDashboard.Actions(
                connect = { connects++ }, externalDisplay = {}, screenMirror = {}, stop = {}, displayLayout = {},
                disconnect = { disconnects++ },
            ),
        )
        val button = dashboard.root.findViewById<MaterialButton>(R.id.connection_action)
        dashboard.render(BridgeSnapshot(connection = ConnectionState.DISCONNECTED))
        val connectFill = button.backgroundTintList!!.defaultColor
        assertEquals("Connect", button.text)
        button.performClick()
        assertEquals(1, connects)

        dashboard.render(BridgeSnapshot(connection = ConnectionState.READY))
        assertEquals("Disconnect", button.text)
        assertFalse(connectFill == button.backgroundTintList!!.defaultColor)
        button.performClick()
        assertEquals(1, disconnects)
    }

    @Test fun `dashboard consumes system bars as root padding`() {
        val controller = Robolectric.buildActivity(Activity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        val activity = controller.setup().get()
        val dashboard = CompanionDashboard(
            activity,
            DisplayLayoutStore(activity),
            CompanionDashboard.Actions({}, {}, {}, {}, {}),
        )
        val bars = Insets.of(3, 47, 5, 29)
        ViewCompat.dispatchApplyWindowInsets(
            dashboard.root,
            WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.systemBars(), bars).build(),
        )
        assertEquals(bars.left, dashboard.root.paddingLeft)
        assertEquals(bars.top, dashboard.root.paddingTop)
        assertEquals(bars.right, dashboard.root.paddingRight)
        assertEquals(bars.bottom, dashboard.root.paddingBottom)
    }

    @Test fun `bridge package and foreground service declarations are independent`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals("com.azhilin.rokid.displaybridge.companion", context.packageName)
        assertEquals("Display Bridge", context.getString(R.string.app_name))
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName,
            PackageManager.GET_SERVICES)
        val service = requireNotNull(info.services).single { it.name.endsWith("BridgeService") }
        assertTrue(service.name.startsWith("com.azhilin.rokid.displaybridge.companion"))
        assertTrue(service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE != 0)
        assertTrue(service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION != 0)
    }

    @Test fun `AOA identity is stable and application neutral`() {
        assertEquals("Zhilin", BridgeIdentity.manufacturer)
        assertEquals("Rokid Display Bridge", BridgeIdentity.model)
        assertEquals("rokid-display-bridge-v1", BridgeIdentity.serial)

        assertEquals("https://github.com/azhilin246/RokidDisplayBridge", BridgeIdentity.uri)

        assertEquals(6, BridgeIdentity.strings.size)
        assertTrue(BridgeIdentity.strings.none { it.contains("vlc", true) || it.contains("videolan", true) })
    }

    @Test fun `actions preferences and channel namespace are bridge-only`() {
        listOf(BridgeService.ACTION_CONNECT, BridgeService.ACTION_DISCONNECT, BridgeService.ACTION_START_EXTERNAL_DISPLAY,
            BridgeService.ACTION_START_SCREEN_MIRROR, BridgeService.ACTION_STOP,
            BridgeService.ACTION_COMPANION_VISIBILITY, BridgeService.ACTION_CANCEL_AUTO_EXTERNAL)
            .forEach { value ->
                assertTrue(value.contains("displaybridge") || value.startsWith("rokid_display_bridge"))
                assertFalse(value.contains("rwp", true))
            }
    }

    @Test fun `USB selector prefers Rokid and rejects ambiguity`() {
        val apple = UsbDeviceIdentity(0x05ac, 0x1463, "Apple", "USB-C AV")
        val rokid = UsbDeviceIdentity(0x18d1, 0x4ee7, "Rokid", "RG_glasses")
        assertEquals(1, selectTargetUsbDevice(listOf(apple, rokid)))
        assertNull(selectTargetUsbDevice(emptyList()))
        assertThrows(IllegalArgumentException::class.java) { selectTargetUsbDevice(listOf(apple, apple.copy(productId = 1))) }
    }

    @Test fun `USB permission stages distinguish AOA re-enumeration and attach continuation`() {
        assertEquals(UsbPermissionStage.DEVICE_TO_AOA, usbPermissionStage(0x18d1, 0x4ee7))
        assertEquals(UsbPermissionStage.AOA_DATA, usbPermissionStage(0x18d1, 0x2d00))
        assertFalse(shouldConnectOnAttach(false, false))
        assertTrue(shouldConnectOnAttach(true, false))
        assertTrue(shouldConnectOnAttach(false, true))
    }

    @Test fun `automatic external display starts once while idle and manual mode cancels it`() {
        val gate = AutomaticExternalDisplayGate()
        gate.onCompanionVisible(BridgeMode.IDLE)
        assertTrue(gate.pending)
        assertTrue(gate.consumeReady(BridgeMode.IDLE))
        assertFalse(gate.consumeReady(BridgeMode.IDLE))
        gate.onCompanionVisible(BridgeMode.IDLE)
        gate.cancel()
        assertFalse(gate.consumeReady(BridgeMode.IDLE))
        gate.onCompanionVisible(BridgeMode.SCREEN_MIRROR)
        assertFalse(gate.consumeReady(BridgeMode.SCREEN_MIRROR))
    }

    @Test fun `automatic attempt survives permission UI pause but not an idle hidden companion`() {
        val gate = AutomaticExternalDisplayGate()
        gate.onCompanionVisible(BridgeMode.IDLE)
        gate.onCompanionHidden(connectionAttemptActive = true)
        assertTrue(gate.pending)
        gate.onCompanionHidden(connectionAttemptActive = false)
        assertFalse(gate.pending)
    }

    @Test fun `bulk writer handles partial writes and bounds retries`() {
        val offsets = mutableListOf<Int>()
        AoaBulkWriter(maxChunkSize = 4).write(ByteArray(10), { true }) { _, offset, requested ->
            offsets += offset
            minOf(2, requested)
        }
        assertEquals(listOf(0, 2, 4, 6, 8), offsets)
        assertThrows(IOException::class.java) {
            AoaBulkWriter(maxAttemptsPerChunk = 2).write(byteArrayOf(1), { true }) { _, _, _ -> -1 }
        }
    }

    @Test fun `mode switch runs required cleanup before new start`() {
        val events = mutableListOf<String>()
        val transition = ModeTransition(object : ModeTransition.Cleanup {
            override fun stopProducer() { events += "stopProducer" }
            override fun stopSamples() { events += "stopSamples" }
            override fun sendLiveStop(previous: BridgeMode) { events += "liveStop:$previous" }
            override fun releaseSource(previous: BridgeMode) { events += "releaseSource:$previous" }
            override fun releaseEncoder() { events += "releaseEncoder" }
            override fun clearQueues() { events += "clearQueues" }
            override fun invalidateIdentity() { events += "invalidate" }
        })
        transition.switchTo(BridgeMode.EXTERNAL_DISPLAY) { events += "startExternal" }
        transition.switchTo(BridgeMode.SCREEN_MIRROR) { events += "startMirror" }
        assertEquals(listOf("startExternal", "stopProducer", "stopSamples", "liveStop:EXTERNAL_DISPLAY",
            "releaseSource:EXTERNAL_DISPLAY", "releaseEncoder", "clearQueues", "invalidate", "startMirror"), events)
        assertEquals(BridgeMode.SCREEN_MIRROR, transition.mode)
    }

    @Test fun `idempotent idle stop performs no second cleanup`() {
        var releases = 0
        val transition = ModeTransition(object : ModeTransition.Cleanup {
            override fun stopProducer() { releases++ }
            override fun stopSamples() = Unit
            override fun sendLiveStop(previous: BridgeMode) = Unit
            override fun releaseSource(previous: BridgeMode) = Unit
            override fun releaseEncoder() = Unit
            override fun clearQueues() = Unit
            override fun invalidateIdentity() = Unit
        })
        transition.switchTo(BridgeMode.EXTERNAL_DISPLAY) {}
        transition.stop(); transition.stop()
        assertEquals(1, releases)
    }

    @Test fun `starting the same active mode performs a full restart`() {
        val events = mutableListOf<String>()
        val transition = ModeTransition(object : ModeTransition.Cleanup {
            override fun stopProducer() { events += "stopProducer" }
            override fun stopSamples() { events += "stopSamples" }
            override fun sendLiveStop(previous: BridgeMode) { events += "liveStop:$previous" }
            override fun releaseSource(previous: BridgeMode) { events += "releaseSource:$previous" }
            override fun releaseEncoder() { events += "releaseEncoder" }
            override fun clearQueues() { events += "clearQueues" }
            override fun invalidateIdentity() { events += "invalidate" }
        })
        transition.switchTo(BridgeMode.SCREEN_MIRROR) { events += "startFirst" }
        transition.switchTo(BridgeMode.SCREEN_MIRROR) { events += "startSecond" }
        assertEquals(listOf("startFirst", "stopProducer", "stopSamples", "liveStop:SCREEN_MIRROR",
            "releaseSource:SCREEN_MIRROR", "releaseEncoder", "clearQueues", "invalidate", "startSecond"), events)
    }

    @Test fun `presentation display flags are exact and exclude clone secure`() {
        val expected = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        assertEquals(expected, PresentationDisplayController.REQUIRED_FLAGS)
        assertFalse(PresentationDisplayController.hasForbiddenFlags(expected))
        assertTrue(PresentationDisplayController.hasForbiddenFlags(expected or DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR))
        assertTrue(PresentationDisplayController.hasForbiddenFlags(expected or DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE))
    }

    @Test fun `encoder reconfiguration generation advances without zero`() {
        assertEquals(2u, BridgeService.nextGeneration(1u))
        assertEquals(1u, BridgeService.nextGeneration(UInt.MAX_VALUE))
    }

    @Test fun `encoder realtime tuning is bounded and contains no reorder buffer`() {
        val format = MediaFormat()
        SurfaceToAvcStream.applyRealtimeSettings(
            format,
            SurfaceToAvcStream.FRAME_RATE,
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
        )
        assertEquals(0, format.getInteger(MediaFormat.KEY_PRIORITY))
        assertEquals(0, format.getInteger(MediaFormat.KEY_LATENCY))
        assertEquals(0, format.getInteger(MediaFormat.KEY_MAX_B_FRAMES))
        assertEquals(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
            format.getInteger(MediaFormat.KEY_BITRATE_MODE))
        assertEquals(30.0f, format.getFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER))
        assertEquals(1_200_000, SurfaceToAvcStream.BITRATE)
    }

    @Test fun `adaptive bitrate reacts quickly to pressure and recovers slowly`() {
        val adaptive = AdaptiveVideoBitrate()
        assertEquals(1_200_000, adaptive.currentBitrate)
        assertEquals(1_020_000, adaptive.onQueuePressure(1_000)!!.bitrate)
        assertEquals(null, adaptive.onQueuePressure(1_200))
        assertEquals(null, adaptive.onDecoderFeedback(450, 1, 2_000))
        assertEquals(867_000, adaptive.onDecoderFeedback(450, 2, 4_100)!!.bitrate)
        assertEquals(null, adaptive.onDecoderFeedback(0, 2, 8_000))
        assertEquals(967_000, adaptive.onDecoderFeedback(0, 2, 12_100)!!.bitrate)
    }

    @Test fun `handshake resets completely on detach`() {
        val handshake = BridgeHandshake()
        assertEquals(8_192, handshake.accept(HelloPayload(maxPayloadBytes = 8_192u)))
        assertTrue(handshake.complete)
        handshake.reset()
        assertFalse(handshake.complete)
        assertEquals(com.azhilin.rokid.displaybridge.protocol.DBP_MAX_PAYLOAD_SIZE, handshake.maxPayload)
    }

    @Test fun `stale feedback is rejected and counters track completed video sends`() {
        assertTrue(isCurrentLivePacket(20u, 2u, 20u, 2u))
        assertFalse(isCurrentLivePacket(19u, 2u, 20u, 2u))
        assertFalse(isCurrentLivePacket(20u, 1u, 20u, 2u))
        val stats = QueueStats(controlDepth = 0, videoDepth = 3, droppedVideoFrames = 4)
        val sent = BridgeSnapshot().afterSenderSuccess(FramePriority.VIDEO, stats)
        assertEquals(1, sent.sentFrames)
        assertEquals(4, sent.droppedFrames)
        assertEquals(3, sent.queueDepth)
        val feedback = LiveFeedbackPayload(20u, 2u, 25u, 7u, 1u, 123u, FeedbackFlags.REQUEST_KEY_FRAME)
        assertEquals(7, sent.afterDecoderFeedback(feedback).decodedFrames)
    }

    @Test fun `external display identity and display name are application-neutral`() {
        assertEquals("Rokid External Display", PresentationDisplayController.DISPLAY_NAME)
        listOf(BridgeIdentity.model, BridgeIdentity.description, BridgeIdentity.uri, BridgeIdentity.serial,
            BridgeService.ACTION_START_EXTERNAL_DISPLAY, PresentationDisplayController.DISPLAY_NAME).forEach {
            assertFalse(it.contains("vlc", true))
            assertFalse(it.contains("videolan", true))
        }
    }

    @Test fun `glasses controls map to standard Android media keys`() {
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, BridgeService.mediaKeyCode(MediaCommand.PLAY_PAUSE))
        assertEquals(KeyEvent.KEYCODE_MEDIA_REWIND, BridgeService.mediaKeyCode(MediaCommand.SEEK_BACKWARD))
        assertEquals(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, BridgeService.mediaKeyCode(MediaCommand.SEEK_FORWARD))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, BridgeService.mediaKeyCode(MediaCommand.PLAYLIST_PREVIOUS))
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, BridgeService.mediaKeyCode(MediaCommand.PLAYLIST_NEXT))
    }

    @Test fun `frame editor stays three by four while moving resizing and resetting`() {
        val resized = DisplayLayoutEditorModel.resize(DisplayLayoutPayload(), FrameResizeHandle.BOTTOM_RIGHT, -120.0, -160.0)
        val geometry = DisplayLayoutEditorModel.geometry(resized)
        assertTrue(resized.scalePermille.toInt() < 1_000)
        assertEquals(3.0 / 4.0, geometry.width / geometry.height, 0.0001)
        val moved = DisplayLayoutEditorModel.move(resized, 500.0, -500.0)
        assertEquals(1_000, moved.offsetXPermille.toInt())
        assertEquals(DisplayLayoutEditorModel.VIEWPORT_WIDTH - geometry.width,
            DisplayLayoutEditorModel.geometry(moved).left, 0.0001)
        assertEquals(geometry.top - 500.0, DisplayLayoutEditorModel.geometry(moved).top, 0.5)
        assertTrue(DisplayLayoutEditorModel.geometry(moved).top < 0.0)
        assertEquals(DisplayLayoutPayload(), DisplayLayoutEditorModel.reset())
    }

    @Test fun `full size frame can move vertically beyond viewport`() {
        val upward = DisplayLayoutEditorModel.move(DisplayLayoutPayload(), 0.0, -320.0)
        assertEquals(-556, upward.offsetYPermille.toInt())
        assertEquals(-320.0, DisplayLayoutEditorModel.geometry(upward).top, 0.5)
        val fullyBelow = DisplayLayoutEditorModel.move(DisplayLayoutPayload(), 0.0, 10_000.0)
        assertEquals(1_000, fullyBelow.offsetYPermille.toInt())
        assertEquals(576.0, DisplayLayoutEditorModel.geometry(fullyBelow).top, 0.0001)
        assertEquals(0.0, DisplayLayoutEditorModel.geometry(fullyBelow).left, 0.0001)
    }

    @Test fun `frame layout persists in bridge-owned preferences`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = DisplayLayoutStore(context)
        val value = DisplayLayoutPayload(625u, -300, 700)
        store.save(value)
        assertEquals(value, store.load())
    }


    private fun allText(root: View): List<String> {
        val own = (root as? android.widget.TextView)?.text?.toString()?.let(::listOf).orEmpty()
        val group = root as? android.view.ViewGroup ?: return own
        return own + (0 until group.childCount).flatMap { allText(group.getChildAt(it)) }
    }

    private fun isDescendant(parent: android.view.ViewGroup, child: View): Boolean {
        if (parent === child) return true
        return (0 until parent.childCount).any { index ->
            val candidate = parent.getChildAt(index)
            candidate === child || (candidate is android.view.ViewGroup && isDescendant(candidate, child))
        }
    }
}
