package com.azhilin.rokid.displaybridge.glasses

import android.content.pm.PackageManager
import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbManager
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.azhilin.rokid.displaybridge.glasses.playback.LiveVideoDecoder
import com.azhilin.rokid.displaybridge.glasses.playback.LiveReadyAnnouncementGate
import com.azhilin.rokid.displaybridge.glasses.playback.DecoderIngressDecision
import com.azhilin.rokid.displaybridge.glasses.playback.DecoderIngressGate
import com.azhilin.rokid.displaybridge.glasses.playback.DecoderStateGate
import com.azhilin.rokid.displaybridge.glasses.playback.SurfaceRecoveryAction
import com.azhilin.rokid.displaybridge.glasses.input.FocusMove
import com.azhilin.rokid.displaybridge.glasses.input.InputMode
import com.azhilin.rokid.displaybridge.glasses.input.PlayerInput
import com.azhilin.rokid.displaybridge.glasses.input.PlayerInputAction
import com.azhilin.rokid.displaybridge.glasses.input.PlayerInputRouter
import com.azhilin.rokid.displaybridge.glasses.input.SwipeActionGate
import com.azhilin.rokid.displaybridge.glasses.ui.OverlayControl
import com.azhilin.rokid.displaybridge.glasses.ui.PlaybackOverlayModel
import com.azhilin.rokid.displaybridge.protocol.AccessUnitReassembler
import com.azhilin.rokid.displaybridge.protocol.DisplayLayoutPayload
import com.azhilin.rokid.displaybridge.protocol.LivePayloads
import com.azhilin.rokid.displaybridge.protocol.LiveSourceKind
import com.azhilin.rokid.displaybridge.protocol.MediaCommand
import com.azhilin.rokid.displaybridge.protocol.DoubleTapGestureAction
import com.azhilin.rokid.displaybridge.protocol.GestureSettingsPayload
import com.azhilin.rokid.displaybridge.protocol.SwipeGestureAction
import com.azhilin.rokid.displaybridge.protocol.TapGestureAction
import com.azhilin.rokid.displaybridge.protocol.StopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GlassesUnitTest {
    @Test fun `glasses state surface is pure black with one state label and video overlay stays translucent`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val ui = GlassesUi(activity, {}, {})
        assertTrue(ui.isStateSurfaceVisible())
        assertTrue(ui.stateSurfaceIsPureBlack())
        assertEquals("Display Bridge", ui.appTitleText())
        ui.render(GlassesState.READY)
        assertEquals("READY", ui.stateTitleText())
        assertEquals(1, ui.root.visibleTextOccurrences("READY"))
        assertTrue(GlassesUi.hintFor(GlassesState.READY).contains("PHONE"))

        ui.render(GlassesState.PLAYING)
        assertFalse(ui.isStateSurfaceVisible())
        ui.setOverlayVisible(true)
        assertTrue(ui.isPlaybackOverlayTranslucent())
    }

    @Test fun `all required states have readable presentation`() {
        assertEquals(10, GlassesState.entries.size)
        GlassesState.entries.forEach { state ->
            val presentation = GlassesStatePresentation.forState(state)
            assertTrue(presentation.title.isNotBlank())
            if (state != GlassesState.PLAYING) assertTrue(presentation.detail.isNotBlank())
        }
        assertTrue(GlassesStatePresentation.forState(GlassesState.LOADING).videoVisible)
        assertTrue(GlassesStatePresentation.forState(GlassesState.PLAYING).videoVisible)
        assertFalse(GlassesStatePresentation.forState(GlassesState.ERROR).videoVisible)
    }

    @Test fun `external display waits for video without exposing protocol source kind`() {
        val waiting = GlassesStatePresentation.forState(GlassesState.WAITING_FOR_VIDEO)
        assertEquals("WAITING FOR VIDEO", waiting.title)
        assertFalse(waiting.detail.contains("PRESENTATION_DISPLAY"))
        assertFalse(waiting.videoVisible)
    }

    @Test fun `bridge accessory identity is exact`() {
        assertTrue(MainActivity.isBridgeIdentity("Zhilin", "Rokid Display Bridge", "1.0"))
        assertFalse(MainActivity.isBridgeIdentity("Zhilin", "Other Accessory", "1.0"))
        assertFalse(MainActivity.isBridgeIdentity("Zhilin", "Rokid Display Bridge", "0.1.6"))
        assertFalse(MainActivity.isBridgeIdentity("Other", "Rokid Display Bridge", "1.0"))
    }

    @Test fun `glasses package and manifest are independent`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals("com.azhilin.rokid.displaybridge.glasses", context.packageName)
        assertEquals("Display Bridge", context.applicationInfo.loadLabel(context.packageManager).toString())
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName,
            PackageManager.GET_ACTIVITIES)
        val activity = requireNotNull(info.activities).single()
        assertTrue(activity.name.endsWith("MainActivity"))
        assertTrue(activity.name.startsWith("com.azhilin.rokid.displaybridge.glasses"))
        @Suppress("DEPRECATION")
        val permissions = context.packageManager.getPackageInfo(context.packageName,
            PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertTrue(android.Manifest.permission.WAKE_LOCK in permissions)
    }

    @Test fun `system accessory dispatch resolves glasses activity without ADB`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val matches = context.packageManager.queryIntentActivities(
            Intent(UsbManager.ACTION_USB_ACCESSORY_ATTACHED).setPackage(context.packageName), 0)
        assertTrue(matches.any { it.activityInfo.name.endsWith("MainActivity") })
    }

    @Test fun `accessory permission gate coalesces duplicate lifecycle delivery`() {
        val gate = AccessoryPermissionGate()
        assertTrue(gate.shouldRequest("bridge-v1"))
        assertFalse(gate.shouldRequest("bridge-v1"))
        gate.onResult("bridge-v1")
        assertTrue(gate.shouldRequest("bridge-v1"))
        gate.onDenied("bridge-v1")
        assertFalse(gate.shouldRequest("bridge-v1"))
        gate.reset()
        assertTrue(gate.shouldRequest("bridge-v1"))
    }

    @Test fun `cable detach target is the system home task`() {
        val intent = MainActivity.cableDetachHomeIntent()
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertTrue(intent.categories.contains(Intent.CATEGORY_HOME))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun `playing state keeps overlay empty`() {
        val playing = GlassesStatePresentation.forState(GlassesState.PLAYING)
        assertEquals("", playing.detail)
        assertTrue(playing.videoVisible)
    }

    @Test fun `TextureView has no unsupported background drawable`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val ui = GlassesUi(activity, {}, {})
        assertEquals(null, ui.textureView.background)
    }

    @Test fun `TextureView stays attached behind black state layer while waiting for source`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val ui = GlassesUi(activity, {}, {})
        ui.render(GlassesState.WAITING_FOR_VIDEO)
        assertEquals(View.VISIBLE, ui.textureView.visibility)
        assertTrue(ui.isStateSurfaceVisible())
        assertTrue(ui.stateSurfaceIsPureBlack())
    }

    @Test fun `live ready is announced once per accepted session independent of surface lifecycle`() {
        val gate = LiveReadyAnnouncementGate()
        gate.begin(101u, 1u)
        assertTrue(gate.take(101u, 1u))
        assertFalse(gate.take(101u, 1u))
        assertFalse(gate.take(101u, 2u))
        gate.begin(102u, 1u)
        assertFalse(gate.take(101u, 1u))
        assertTrue(gate.take(102u, 1u))
    }

    @Test fun `USB signals do not infer ADB ownership from enabled function`() {
        assertEquals(GlassesState.NO_CABLE, idleGlassesState(UsbConnectionSignals()))
        assertEquals(GlassesState.CHARGE_ONLY_CABLE, idleGlassesState(UsbConnectionSignals(usbPowered = true)))
        assertEquals(GlassesState.WAITING_FOR_BRIDGE, idleGlassesState(
            UsbConnectionSignals(usbPowered = true, dataConnected = true)))
        assertEquals(GlassesState.ADB_OWNS_DATA_CABLE, idleGlassesState(
            UsbConnectionSignals(dataConnected = true, adbSessionProven = true)))
        assertEquals(GlassesState.WAITING_FOR_BRIDGE, idleGlassesState(
            UsbConnectionSignals(accessoryPresent = true)))
    }

    @Test fun `playing keeps glasses awake and all other states release it`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val ui = GlassesUi(activity, {}, {})
        ui.render(GlassesState.PLAYING)
        assertTrue(ui.root.keepScreenOn)
        ui.render(GlassesState.READY)
        assertFalse(ui.root.keepScreenOn)
        ui.render(GlassesState.ERROR)
        assertFalse(ui.root.keepScreenOn)
    }

    @Test fun `bounded wake pulse wakes without changing persistent playback policy`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val ui = GlassesUi(activity, {}, {})
        ui.beginWakePulse()
        assertTrue(ui.root.keepScreenOn)
        ui.endWakePulse(playing = false)
        assertFalse(ui.root.keepScreenOn)
        ui.beginWakePulse()
        ui.endWakePulse(playing = true)
        assertTrue(ui.root.keepScreenOn)
    }

    @Test fun `display wake is one shot on first frame of every live session`() {
        val gate = DisplayWakeGate()
        assertFalse(gate.acceptFirstFrame(0u, LiveSourceKind.SCREEN_CAPTURE))
        assertTrue(gate.acceptFirstFrame(20u, LiveSourceKind.SCREEN_CAPTURE))
        assertFalse(gate.acceptFirstFrame(20u, LiveSourceKind.SCREEN_CAPTURE))
        assertTrue(gate.acceptFirstFrame(21u, LiveSourceKind.PRESENTATION_DISPLAY))
        assertFalse(gate.acceptFirstFrame(21u, LiveSourceKind.PRESENTATION_DISPLAY))
        gate.resetConnection()
        assertTrue(gate.acceptFirstFrame(20u, LiveSourceKind.SCREEN_CAPTURE))
    }

    @Test fun `overlay focus resets to first play pause and cycles across four controls`() {
        val model = PlaybackOverlayModel()
        model.show()
        assertEquals(OverlayControl.PLAY_PAUSE, model.focused)
        assertEquals(OverlayControl.PLAY_PAUSE, PlaybackOverlayModel.ORDER.first())
        assertEquals(OverlayControl.SEEK_BACKWARD, model.next())
        assertEquals(OverlayControl.SEEK_FORWARD, model.next())
        assertEquals(OverlayControl.EXIT, model.next())
        assertEquals(OverlayControl.PLAY_PAUSE, model.next())
        assertEquals(OverlayControl.EXIT, model.previous())
        assertEquals(4, PlaybackOverlayModel.ORDER.size)
        model.focus(OverlayControl.SEEK_FORWARD)
        model.hide()
        model.show()
        assertEquals(OverlayControl.PLAY_PAUSE, model.focused)
    }

    @Test fun `overlay controls map only playback actions to media keys`() {
        assertEquals(MediaCommand.PLAY_PAUSE, OverlayControl.PLAY_PAUSE.mediaCommand)
        assertEquals(MediaCommand.SEEK_BACKWARD, OverlayControl.SEEK_BACKWARD.mediaCommand)
        assertEquals(MediaCommand.SEEK_FORWARD, OverlayControl.SEEK_FORWARD.mediaCommand)
        assertEquals(null, OverlayControl.EXIT.mediaCommand)
    }

    @Test fun `Exit image control dispatches its click callback`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var clicked: OverlayControl? = null
        val ui = GlassesUi(activity, { clicked = it }, {})
        ui.render(GlassesState.PLAYING)
        ui.setOverlayVisible(true)
        assertTrue(ui.performControl(OverlayControl.EXIT))
        assertEquals(OverlayControl.EXIT, clicked)
    }

    @Test fun `overlay exit requests a user stop for the active session`() {
        val stop = MainActivity.overlayExitStopPayload(71uL)
        assertEquals(71uL, stop.sessionId)
        assertEquals(StopReason.USER, stop.reason)
        assertTrue(stop.detail.contains("overlay Exit"))
    }

    @Test fun `input matrix matches hidden and visible overlay gestures`() {
        assertEquals(PlayerInputAction.None, PlayerInputRouter.route(InputMode.MAIN, PlayerInput.SINGLE_TAP))
        assertEquals(PlayerInputAction.None, PlayerInputRouter.route(InputMode.MAIN, PlayerInput.PREVIOUS))
        assertEquals(PlayerInputAction.ExitApplication(true),
            PlayerInputRouter.route(InputMode.MAIN, PlayerInput.DOUBLE_TAP))
        assertEquals(PlayerInputAction.SendMediaCommand(MediaCommand.PLAY_PAUSE),
            PlayerInputRouter.route(InputMode.PLAYER_HIDDEN, PlayerInput.SINGLE_TAP))
        assertEquals(PlayerInputAction.ShowOverlay, PlayerInputRouter.route(InputMode.PLAYER_HIDDEN, PlayerInput.DOUBLE_TAP))
        assertEquals(PlayerInputAction.SendMediaCommand(MediaCommand.SEEK_BACKWARD),
            PlayerInputRouter.route(InputMode.PLAYER_HIDDEN, PlayerInput.PREVIOUS))
        assertEquals(PlayerInputAction.SendMediaCommand(MediaCommand.SEEK_FORWARD),
            PlayerInputRouter.route(InputMode.PLAYER_HIDDEN, PlayerInput.NEXT))
        assertEquals(PlayerInputAction.ActivateFocused, PlayerInputRouter.route(InputMode.OVERLAY_VISIBLE, PlayerInput.SINGLE_TAP))
        assertEquals(PlayerInputAction.HideOverlay, PlayerInputRouter.route(InputMode.OVERLAY_VISIBLE, PlayerInput.DOUBLE_TAP))
        assertEquals(PlayerInputAction.MoveFocus(FocusMove.PREVIOUS),
            PlayerInputRouter.route(InputMode.OVERLAY_VISIBLE, PlayerInput.PREVIOUS))
        assertEquals(PlayerInputAction.MoveFocus(FocusMove.NEXT),
            PlayerInputRouter.route(InputMode.OVERLAY_VISIBLE, PlayerInput.NEXT))
    }

    @Test fun `configured hidden gestures reverse directions and preserve overlay controls`() {
        val settings = GestureSettingsPayload(
            swipeAction = SwipeGestureAction.FAVORITES,
            tapAction = TapGestureAction.NONE,
            doubleTapAction = DoubleTapGestureAction.MAIN_MENU,
            reverseSwipeDirections = true,
            confirmExitWithSecondDoubleTap = false,
        )
        assertEquals(PlayerInputAction.None,
            PlayerInputRouter.route(InputMode.PLAYER_HIDDEN, PlayerInput.SINGLE_TAP, settings))
        assertEquals(PlayerInputAction.SendMediaCommand(MediaCommand.FAVORITE_NEXT),
            PlayerInputRouter.route(InputMode.PLAYER_HIDDEN, PlayerInput.PREVIOUS, settings))
        assertEquals(PlayerInputAction.SendMediaCommand(MediaCommand.FAVORITE_PREVIOUS),
            PlayerInputRouter.route(InputMode.PLAYER_HIDDEN, PlayerInput.NEXT, settings))
        assertEquals(PlayerInputAction.ExitToMain(false),
            PlayerInputRouter.route(InputMode.PLAYER_HIDDEN, PlayerInput.DOUBLE_TAP, settings))
        assertEquals(PlayerInputAction.ActivateFocused,
            PlayerInputRouter.route(InputMode.OVERLAY_VISIBLE, PlayerInput.SINGLE_TAP, settings))
        assertEquals(PlayerInputAction.HideOverlay,
            PlayerInputRouter.route(InputMode.OVERLAY_VISIBLE, PlayerInput.DOUBLE_TAP, settings))
    }

    @Test fun `guarded exit requires two double taps inside timeout`() {
        var now = 1_000L
        val controller = ExitConfirmationController(timeoutMs = 3_000L, clockMs = { now })
        assertEquals(ExitConfirmationAction.ARM, controller.onDoubleTap())
        now = 3_999L
        assertEquals(ExitConfirmationAction.CONFIRM_EXIT, controller.onDoubleTap())
        assertEquals(ExitConfirmationAction.ARM, controller.onDoubleTap())
        now = 7_000L
        assertEquals(ExitConfirmationAction.ARM, controller.onDoubleTap())
        controller.disarm()
        assertEquals(ExitConfirmationAction.ARM, controller.onDoubleTap())
    }

    @Test fun `exit prompt is a dedicated overlay`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val ui = GlassesUi(activity, {}, {})
        ui.render(GlassesState.READY, "ordinary status")
        ui.setExitConfirmationVisible(true)
        assertTrue(ui.isExitConfirmationVisible())
        ui.setExitConfirmationVisible(false)
        assertFalse(ui.isExitConfirmationVisible())
    }

    @Test fun `continuous swipe stream emits one action until a quiet gap`() {
        val gate = SwipeActionGate(150)
        assertTrue(gate.accept(1_000))
        assertFalse(gate.accept(1_050))
        assertFalse(gate.accept(1_180))
        assertFalse(gate.accept(1_300))
        assertTrue(gate.accept(1_451))
        gate.reset()
        assertTrue(gate.accept(1_452))
    }

    @Test fun `display layout moves within horizontal bounds with vertical overscan`() {
        val geometry = DisplayLayoutModel.geometry(DisplayLayoutPayload(500u, 1_000, -1_000))
        assertEquals(240, geometry.width)
        assertEquals(320, geometry.height)
        assertEquals(240, geometry.left)
        assertEquals(-288, geometry.top)
        assertEquals(3.0 / 4.0, geometry.width.toDouble() / geometry.height, 0.0001)
        val fullSize = DisplayLayoutModel.geometry(DisplayLayoutPayload(1_000u, 1_000, 500))
        assertEquals(0, fullSize.left)
        assertEquals(288, fullSize.top)
    }

    @Test fun `late session and generation messages cannot resurrect stopped mode`() {
        val guard = IncomingSessionGuard()
        assertTrue(guard.acceptStart(10u, 1u))
        assertTrue(guard.acceptConfig(10u, 1u))
        assertTrue(guard.acceptDiscontinuity(10u, 2u))
        assertFalse(guard.acceptConfig(10u, 1u))
        assertTrue(guard.acceptStop(10u))
        assertFalse(guard.acceptStart(10u, 3u))
        assertFalse(guard.acceptStop(10u))
        assertTrue(guard.acceptStart(11u, 1u))
    }

    @Test fun `surface recovery requests a decoder rebuild and keyframe only with config`() {
        assertEquals(SurfaceRecoveryAction.NONE, LiveVideoDecoder.surfaceRecoveryAction(false, true))
        assertEquals(SurfaceRecoveryAction.WAIT_FOR_CONFIG, LiveVideoDecoder.surfaceRecoveryAction(true, false))
        assertEquals(SurfaceRecoveryAction.RECREATE_DECODER_REQUEST_KEYFRAME,
            LiveVideoDecoder.surfaceRecoveryAction(true, true))
        assertEquals(8, LiveVideoDecoder.MAX_PENDING_ACCESS_UNITS)
        assertEquals(4, LiveVideoDecoder.MAX_QUEUED_ACCESS_UNITS)
    }

    @Test fun `decoder ingress is bounded and waits for keyframe after pressure`() {
        val gate = DecoderIngressGate(maxPendingAccessUnits = 2)
        gate.beginStream()
        assertEquals(DecoderIngressDecision.ACCEPT, gate.offer(keyFrame = false))
        assertEquals(DecoderIngressDecision.ACCEPT, gate.offer(keyFrame = false))
        assertEquals(DecoderIngressDecision.DROP_REQUEST_KEY_FRAME, gate.offer(keyFrame = false))
        assertEquals(DecoderIngressDecision.DROP, gate.offer(keyFrame = false))
        assertEquals(2, gate.stats().pendingAccessUnits)
        assertTrue(gate.stats().waitingForKeyFrame)
        gate.complete()
        assertEquals(DecoderIngressDecision.ACCEPT, gate.offer(keyFrame = true))
        assertFalse(gate.stats().waitingForKeyFrame)
        assertEquals(2, gate.stats().pendingAccessUnits)
        gate.complete(); gate.complete()
        assertEquals(0, gate.stats().pendingAccessUnits)
    }

    @Test fun `decoder recovery request is coalesced until a keyframe`() {
        val gate = DecoderIngressGate(maxPendingAccessUnits = 2)
        assertTrue(gate.requireKeyFrame())
        assertFalse(gate.requireKeyFrame())
        assertEquals(DecoderIngressDecision.DROP, gate.offer(keyFrame = false))
        assertEquals(DecoderIngressDecision.ACCEPT, gate.offer(keyFrame = true))
        assertFalse(gate.stats().waitingForKeyFrame)
    }

    @Test fun `decoder state is published only on transition`() {
        val gate = DecoderStateGate(LiveVideoDecoder.DecoderState.IDLE)
        assertFalse(gate.transition(LiveVideoDecoder.DecoderState.IDLE))
        assertTrue(gate.transition(LiveVideoDecoder.DecoderState.LOADING))
        assertFalse(gate.transition(LiveVideoDecoder.DecoderState.LOADING))
        assertTrue(gate.transition(LiveVideoDecoder.DecoderState.PLAYING))
        assertFalse(gate.transition(LiveVideoDecoder.DecoderState.PLAYING))
        assertTrue(gate.transition(LiveVideoDecoder.DecoderState.ERROR))
    }

    @Test fun `glasses reassembly bound rejects a second pending access unit`() {
        val reassembler = AccessUnitReassembler(maxPendingUnits = 1).apply { replaceSession(9u, 1u) }
        val first = LivePayloads.fragment(9u, 1u, 0u, 1u, 0u, ByteArray(100), 80).first()
        val second = LivePayloads.fragment(9u, 1u, 0u, 2u, 0u, ByteArray(100), 80).first()
        assertEquals(com.azhilin.rokid.displaybridge.protocol.ReassemblyResult.Pending, reassembler.accept(first, 0))
        org.junit.Assert.assertThrows(com.azhilin.rokid.displaybridge.protocol.PayloadException::class.java) {
            reassembler.accept(second, 0)
        }
    }
}

private fun View.visibleTextOccurrences(expected: String): Int {
    if (visibility != View.VISIBLE) return 0
    val own = if (this is TextView && text.toString() == expected) 1 else 0
    return own + if (this is ViewGroup) {
        (0 until childCount).sumOf { getChildAt(it).visibleTextOccurrences(expected) }
    } else 0
}
