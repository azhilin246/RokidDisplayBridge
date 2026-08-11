package com.azhilin.rokid.displaybridge.companion

import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.graphics.Color
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pInfo
import android.app.Activity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
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
import com.azhilin.rokid.displaybridge.companion.wireless.PhoneWirelessHost
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
    @Test fun `wireless host retries only bounded P2P busy failures`() {
        assertTrue(PhoneWirelessHost.shouldRetryBusy(WifiP2pManager.BUSY, 0))
        assertTrue(PhoneWirelessHost.shouldRetryBusy(WifiP2pManager.BUSY, 5))
        assertFalse(PhoneWirelessHost.shouldRetryBusy(WifiP2pManager.BUSY, 6))
        assertFalse(PhoneWirelessHost.shouldRetryBusy(WifiP2pManager.ERROR, 0))
    }

    @Test fun `wireless host refreshes only while waiting for a group`() {
        assertTrue(PhoneWirelessHost.shouldRefreshDiscovery(true, false, false))
        assertFalse(PhoneWirelessHost.shouldRefreshDiscovery(false, false, false))
        assertFalse(PhoneWirelessHost.shouldRefreshDiscovery(true, true, false))
        assertFalse(PhoneWirelessHost.shouldRefreshDiscovery(true, false, true))
    }

    @Test fun `wireless host never removes a group before peer authentication`() {
        assertFalse(PhoneWirelessHost.shouldRemoveGroup(removeRequested = true, groupAuthenticated = false))
        assertFalse(PhoneWirelessHost.shouldRemoveGroup(removeRequested = false, groupAuthenticated = true))
        assertTrue(PhoneWirelessHost.shouldRemoveGroup(removeRequested = true, groupAuthenticated = true))
    }

    @Test fun `wireless host accepts either formed P2P role once owner address exists`() {
        val info = WifiP2pInfo()
        assertFalse(PhoneWirelessHost.isReadyP2pGroup(info))
        info.groupFormed = true
        info.isGroupOwner = false
        assertFalse(PhoneWirelessHost.isReadyP2pGroup(info))
        info.groupOwnerAddress = java.net.InetAddress.getByName("192.168.49.1")
        assertTrue(PhoneWirelessHost.isReadyP2pGroup(info))
        info.isGroupOwner = true
        assertTrue(PhoneWirelessHost.isReadyP2pGroup(info))
    }

    @Test fun `dashboard keeps connection and output mode on the main screen`() {
        val controller = Robolectric.buildActivity(Activity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        val activity = controller.setup().get()
        val dashboard = CompanionDashboard(
            activity,
            DisplayLayoutStore(activity),
            CompanionDashboard.Actions({}, {}, {}, {}, {}),
        )

        assertTrue(dashboard.root.findViewById<View>(R.id.connection_card) is MaterialCardView)
        assertTrue(dashboard.root.findViewById<View>(R.id.connection_mode_group) != null)
        assertTrue(dashboard.root.findViewById<View>(R.id.connection_wired_action) != null)
        assertTrue(dashboard.root.findViewById<View>(R.id.connection_wireless_action) != null)
        assertTrue(dashboard.root.findViewById<View>(R.id.metrics_card) == null)
        assertTrue(dashboard.root.findViewById<View>(R.id.frame_card) == null)
        assertTrue(dashboard.root.findViewById<View>(R.id.gesture_settings_card) == null)
        assertTrue(dashboard.root.findViewById<View>(R.id.technical_card) == null)
        assertTrue(dashboard.root.findViewById<View>(R.id.mode_external).contentDescription == null)
        assertTrue(dashboard.root is androidx.drawerlayout.widget.DrawerLayout)
        val navigation = dashboard.root.findViewById<com.google.android.material.navigation.NavigationView>(R.id.navigation_view)
        assertEquals(listOf("Home", "Layout", "Gestures", "Technical details"),
            (0 until navigation.menu.size()).map { navigation.menu.getItem(it).title.toString() })
        assertTrue((0 until navigation.menu.size()).all { navigation.menu.getItem(it).icon == null })
        assertTrue(navigation.menu.findItem(R.id.nav_home).isChecked)
        val checkedTextColor = requireNotNull(navigation.itemTextColor).getColorForState(
            intArrayOf(android.R.attr.state_checked),
            Color.TRANSPARENT,
        )
        assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(
            checkedTextColor,
            com.google.android.material.color.MaterialColors.getColor(
                activity,
                androidx.appcompat.R.attr.colorPrimary,
                Color.TRANSPARENT,
            ),
        ) >= 4.5)
        assertNotNull(navigation.itemBackground)
        dashboard.root.openDrawer(androidx.core.view.GravityCompat.START)
        assertTrue(dashboard.root.isDrawerOpen(androidx.core.view.GravityCompat.START))
        navigation.menu.performIdentifierAction(R.id.nav_layout, 0)
        assertFalse(dashboard.root.isDrawerOpen(androidx.core.view.GravityCompat.START))
        assertTrue(navigation.menu.findItem(R.id.nav_home).isChecked)
        assertFalse(navigation.menu.findItem(R.id.nav_layout).isChecked)
        assertEquals(FramePlacementActivity::class.java.name,
            org.robolectric.Shadows.shadowOf(activity).nextStartedActivity.component?.className)

        dashboard.render(BridgeSnapshot())
        val wiredMode = dashboard.root.findViewById<MaterialButton>(R.id.connection_wired_action)
        val wirelessModeUnselected = dashboard.root.findViewById<MaterialButton>(R.id.connection_wireless_action)
        assertEquals(
            com.google.android.material.color.MaterialColors.getColor(
                activity,
                com.google.android.material.R.attr.colorSurfaceContainer,
                Color.TRANSPARENT,
            ),
            dashboard.root.findViewById<MaterialCardView>(R.id.connection_card).cardBackgroundColor.defaultColor,
        )
        assertNotEquals(
            dashboard.root.findViewById<MaterialCardView>(R.id.connection_card).cardBackgroundColor.defaultColor,
            requireNotNull(wiredMode.backgroundTintList).defaultColor,
        )
        assertNotEquals(
            requireNotNull(wiredMode.backgroundTintList).defaultColor,
            requireNotNull(wirelessModeUnselected.backgroundTintList).defaultColor,
        )
        assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(
            wiredMode.currentTextColor,
            requireNotNull(wiredMode.backgroundTintList).defaultColor,
        ) >= 4.5)

        val externalMode = dashboard.root.findViewById<MaterialButton>(R.id.mode_external)
        val mirrorMode = dashboard.root.findViewById<MaterialButton>(R.id.mode_mirror)
        assertNotEquals(
            requireNotNull(externalMode.backgroundTintList).defaultColor,
            requireNotNull(mirrorMode.backgroundTintList).defaultColor,
        )
        assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(
            externalMode.currentTextColor,
            requireNotNull(externalMode.backgroundTintList).defaultColor,
        ) >= 4.5)

        dashboard.root.findViewById<MaterialButton>(R.id.connection_wireless_action).performClick()
        dashboard.render(BridgeSnapshot(wirelessPaired = true))
        val forget = dashboard.root.findViewById<MaterialButton>(R.id.wireless_forget_action)
        assertEquals(View.VISIBLE, forget.visibility)
        assertEquals(View.GONE, dashboard.root.findViewById<View>(R.id.wireless_pair_action).visibility)
        assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(
            forget.currentTextColor,
            requireNotNull(forget.backgroundTintList).defaultColor,
        ) >= 4.5)

        dashboard.render(BridgeSnapshot(
            connection = ConnectionState.HANDSHAKING,
            transport = "Wi-Fi Direct",
            wirelessPaired = true,
        ))
        val wirelessMode = dashboard.root.findViewById<MaterialButton>(R.id.connection_wireless_action)
        assertTrue(wirelessMode.isEnabled)
        assertFalse(wirelessMode.isClickable)
        assertEquals(1f, wirelessMode.alpha)
        assertEquals(View.GONE, dashboard.root.findViewById<View>(R.id.wireless_forget_action).parent.let { it as View }.visibility)

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
        val technical = BridgeSnapshot(
            connection = ConnectionState.ERROR,
            mode = BridgeMode.SCREEN_MIRROR,
            sentFrames = 100,
            decodedFrames = 96,
            droppedFrames = 4,
        ).technicalText()
        assertTrue(technical.contains("SCREEN_MIRROR"))
        assertTrue(technical.contains("sent=100 decoded=96 dropped=4"))
        assertFalse(technical.contains("preview", true))
    }

    @Test fun `dashboard removes configuration sections and prompt copy from main content`() {
        val controller = Robolectric.buildActivity(Activity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        val activity = controller.setup().get()
        var framePlacementRequests = 0
        val dashboard = CompanionDashboard(
            activity,
            DisplayLayoutStore(activity),
            CompanionDashboard.Actions({}, {}, {}, {}, {}, framePlacement = { framePlacementRequests++ }),
        )

        assertEquals("Display Bridge", dashboard.root.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.header_title).title)
        assertFalse(allText(dashboard.root).any { it == "DB" || it.contains("Universal external display", true) })
        assertFalse(allText(dashboard.root).any {
            it == "Stop bridge mode" || it.contains("Video only", true) ||
                it.contains("never changes", true) || it.contains("No router", true)
        })
        assertFalse(allText(dashboard.root).any { it == "Frame placement" || it == "Glasses gestures" })
        assertEquals(0, framePlacementRequests)
    }

    @Test fun `technical details is a drawer page with live diagnostics`() {
        val controller = Robolectric.buildActivity(TechnicalDetailsActivity::class.java).setup()
        val activity = controller.get()
        val navigation = activity.findViewById<com.google.android.material.navigation.NavigationView>(R.id.navigation_view)
        assertTrue(navigation.menu.findItem(R.id.nav_technical).isChecked)
        assertTrue(allText(activity.findViewById(android.R.id.content)).any { it.contains("Connection :") })
        controller.pause().stop().destroy()
    }

    @Test fun `home drawer destination finishes child page without launching another activity`() {
        val controller = Robolectric.buildActivity(FramePlacementActivity::class.java).setup()
        val activity = controller.get()
        val navigation = activity.findViewById<com.google.android.material.navigation.NavigationView>(R.id.navigation_view)

        navigation.menu.performIdentifierAction(R.id.nav_home, 0)

        assertTrue(activity.isFinishing)
        assertNull(org.robolectric.Shadows.shadowOf(activity).nextStartedActivity)
        controller.destroy()
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

    @Test fun `stale wireless transport does not override manual wired selection while disconnected`() {
        val controller = Robolectric.buildActivity(Activity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        val activity = controller.setup().get()
        var wiredConnects = 0
        var wirelessConnects = 0
        val dashboard = CompanionDashboard(
            activity,
            DisplayLayoutStore(activity),
            CompanionDashboard.Actions(
                connect = { wiredConnects++ },
                externalDisplay = {},
                screenMirror = {},
                stop = {},
                displayLayout = {},
                connectWireless = { wirelessConnects++ },
            ),
        )
        val staleWirelessSnapshot = BridgeSnapshot(
            connection = ConnectionState.DISCONNECTED,
            transport = "Wi-Fi Direct/TCP",
            wirelessPaired = true,
        )

        dashboard.render(staleWirelessSnapshot)
        dashboard.root.findViewById<MaterialButton>(R.id.connection_wireless_action).performClick()
        dashboard.root.findViewById<MaterialButton>(R.id.connection_wired_action).performClick()
        dashboard.render(staleWirelessSnapshot)
        dashboard.root.findViewById<MaterialButton>(R.id.connection_action).performClick()

        assertTrue(dashboard.root.findViewById<MaterialButton>(R.id.connection_wired_action).isChecked)
        assertEquals(1, wiredConnects)
        assertEquals(0, wirelessConnects)
    }

    @Test fun `dashboard applies system bars around toolbar and page content`() {
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
        val toolbar = dashboard.root.findViewById<View>(R.id.header_title)
        val scroll = (toolbar.parent as ViewGroup).getChildAt(1)
        val navigation = dashboard.root.findViewById<com.google.android.material.navigation.NavigationView>(R.id.navigation_view)
        val drawerHeader = navigation.getHeaderView(0)
        assertEquals(bars.top, toolbar.paddingTop)
        assertEquals(bars.left, scroll.paddingLeft)
        assertEquals(bars.right, scroll.paddingRight)
        assertEquals(bars.bottom, scroll.paddingBottom)
        assertEquals((28 * activity.resources.displayMetrics.density).toInt() + bars.top, drawerHeader.paddingTop)
        assertEquals((24 * activity.resources.displayMetrics.density).toInt() + bars.left, drawerHeader.paddingLeft)
        assertEquals(bars.bottom, navigation.paddingBottom)
    }

    @Test fun `bridge package and foreground service declarations include startup prerequisites`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals("com.azhilin.rokid.displaybridge.companion", context.packageName)
        assertEquals("Display Bridge", context.getString(R.string.app_name))
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName,
            PackageManager.GET_SERVICES or PackageManager.GET_PERMISSIONS)
        val service = requireNotNull(info.services).single { it.name.endsWith("BridgeService") }
        assertTrue(service.name.startsWith("com.azhilin.rokid.displaybridge.companion"))
        assertTrue(service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE != 0)
        assertTrue(service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION != 0)
        val permissions = requireNotNull(info.requestedPermissions).toSet()
        assertTrue("android.permission.CHANGE_NETWORK_STATE" in permissions)
        assertTrue("android.permission.INTERNET" in permissions)
        assertTrue("android.permission.ACCESS_WIFI_STATE" in permissions)
        assertTrue("android.permission.CHANGE_WIFI_STATE" in permissions)
        assertTrue("android.permission.NEARBY_WIFI_DEVICES" in permissions)
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

    @Test fun `preferred output mode persists and defaults safely`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("display_bridge_output_mode_v1", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        val store = PreferredOutputModeStore(context)
        assertEquals(PreferredOutputMode.EXTERNAL_DISPLAY, store.load())
        store.save(PreferredOutputMode.SCREEN_MIRROR)
        assertEquals(PreferredOutputMode.SCREEN_MIRROR, PreferredOutputModeStore(context).load())
        assertEquals(BridgeMode.SCREEN_MIRROR, store.load().bridgeMode)
    }

    @Test fun `preferred screen mirror prompts once on each ready edge`() {
        val readyIdle = BridgeSnapshot(connection = ConnectionState.READY, mode = BridgeMode.IDLE)
        assertTrue(shouldRequestPreferredScreenMirror(false, readyIdle, PreferredOutputMode.SCREEN_MIRROR))
        assertFalse(shouldRequestPreferredScreenMirror(true, readyIdle, PreferredOutputMode.SCREEN_MIRROR))
        assertFalse(shouldRequestPreferredScreenMirror(false, readyIdle, PreferredOutputMode.EXTERNAL_DISPLAY))
        assertFalse(shouldRequestPreferredScreenMirror(
            false,
            readyIdle.copy(mode = BridgeMode.SCREEN_MIRROR),
            PreferredOutputMode.SCREEN_MIRROR,
        ))
    }

    @Test fun `dashboard shows preferred output while disconnected`() {
        val controller = Robolectric.buildActivity(Activity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        val activity = controller.setup().get()
        val dashboard = CompanionDashboard(
            activity,
            DisplayLayoutStore(activity),
            CompanionDashboard.Actions({}, {}, {}, {}, {}),
            preferredOutputMode = PreferredOutputMode.SCREEN_MIRROR,
        )
        dashboard.render(BridgeSnapshot())
        val external = dashboard.root.findViewById<MaterialButton>(R.id.mode_external)
        val mirror = dashboard.root.findViewById<MaterialButton>(R.id.mode_mirror)
        assertTrue(mirror.strokeWidth > external.strokeWidth)
        dashboard.setPreferredOutputMode(PreferredOutputMode.EXTERNAL_DISPLAY)
        assertTrue(external.strokeWidth > mirror.strokeWidth)
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
