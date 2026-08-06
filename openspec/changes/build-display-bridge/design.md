## Context

Display Bridge is a standalone multi-module Android project: the Pixel is AOA host and hardware encoder; RG_glasses is accessory and hardware decoder at 480x640. Its code, packages, state, protocol, and accessory identity are self-contained.

Android allows an application to publish a `PUBLIC | PRESENTATION` VirtualDisplay that other processes can enumerate. Whether another application chooses that display is entirely that application's behavior. The bridge therefore provides the display as infrastructure and never contains player-specific selection, launch, settings, or preferences.

Audio is deliberately outside the bridge. The application rendering to the external display or being mirrored remains the Android audio owner.

## Goals / Non-Goals

**Goals:**

- Install both APKs alongside unrelated phone and glasses applications without identity ambiguity.
- Offer independent `EXTERNAL_DISPLAY` and `SCREEN_MIRROR` modes through one hardware AVC/DBP1 pipeline.
- Let any other process discover the public presentation display and use it if that application implements external-display support.
- Bound queues, reassembly, memory, latency, session/generation state, and cleanup.
- Expose actionable diagnostics, with an optional low-rate placement preview that is disabled on every cold start and never participates in the transport hot path while disabled.

**Non-Goals:**

- Bundling, configuring, launching, or emulating a specific media player.
- Chromecast, Miracast, HDMI emulation, privileged routing, DRM bypass, or secure-surface capture.
- Playback audio capture, AAC, glasses audio playback, route switching, Bluetooth control, or volume control.
- Any change to unrelated installed applications.

## Decisions

### Use standalone DBP1 and a generic AOA identity

The wire magic is `DBP1`, with independent capability bits, sessions, source kinds, diagnostics, actions, and state. The phone publishes `Azhilin / Rokid Display Bridge / 1.0 / https://azhilin.com/rokid-display-bridge / rokid-display-bridge-v1`; the glasses filter matches that model and version only.

### Use one video-only pipeline

`LIVE_START`, `LIVE_READY`, `LIVE_CONFIG`, `LIVE_SAMPLE`, `LIVE_FEEDBACK`, `LIVE_DISCONTINUITY`, `LIVE_STOP`, `ERROR`, and `DIAGNOSTIC` carry generic video. Source kind distinguishes `PRESENTATION_DISPLAY` from `SCREEN_CAPTURE`. There is no audio capability or audio message.

`SurfaceToAvcStream` probes a hardware surface-input AVC encoder, configures at most 480x640/30 fps with bounded bitrate and periodic keyframes, fragments output, and applies keyframe feedback. Both source controllers target its input `Surface`.

### Treat External Display as infrastructure

The companion creates a 480x640 VirtualDisplay with exactly `PUBLIC | PRESENTATION | OWN_CONTENT_ONLY`, without `AUTO_MIRROR`, `SECURE`, or MediaProjection. It records id, flags, mode, size, density, refresh rate, and presentation-category membership. It neither launches nor modifies another application. The user may open any application; only applications that implement presentation/external-display behavior will render there.

Success requires an independent capable application to select the display, non-empty encoder output, DBP1 delivery, decoded/rendered feedback, user-visible glasses video, and phone-owned audio. Display existence alone is not proof.

### Automate the cable lifecycle without ADB

While the companion Activity is open, it arms USB discovery and begins the phone-host permission/AOA flow for an already attached device or the next device attachment. Permission requests are coalesced per enumerated `UsbDevice`. AOA changes the USB identity and normally creates a new Android `UsbDevice`, so the bridge requests permission for the re-enumerated AOA device only when Android has not carried permission forward; public APIs provide no safe permission bypass for that second identity. Diagnostics identify the normal-device and AOA-device stages separately.

After DBP1 `HELLO_ACK`, a pending automatic connection starts `EXTERNAL_DISPLAY` only if the mode is still `IDLE`. Explicit Screen Mirror consent and manual mode switching cancel the pending default and continue to use the existing serialized replacement path.

The glasses manifest owns the unique `USB_ACCESSORY_ATTACHED` filter, allowing Android to launch the glasses Activity without ADB. Detach or EOF of the active accessory performs idempotent decoder/transport cleanup, finishes the glasses task, and launches the system HOME intent. Expected replacement of an in-process transport is not treated as physical cable detach.

### Keep explicit MediaProjection ownership

The Activity launches fresh system consent for every Screen Mirror request. On API 34+ it requests `MediaProjectionConfig.createConfigForDefaultDisplay()` so the system consent is scoped to the entire default display; older releases use the ordinary system chooser. The `connectedDevice|mediaProjection` service registers its callback before creating the 480x640 display and owns projection cleanup. Protected content may remain black.

Android 15 QPR1 and later terminate MediaProjection when the display is locked. The bridge cannot override this privacy policy or reuse the invalid token. `onStop()` therefore releases projection/video resources while leaving the AOA connection and companion process healthy, exposes a fresh-consent action after unlock, and never claims that the capture survived the lock.

### Serialize exclusive modes

The state machine permits only `IDLE`, `EXTERNAL_DISPLAY`, and `SCREEN_MIRROR`. Replacement stops the producer and samples, sends `LIVE_STOP`, releases source/projection and encoder, clears queues, invalidates session/generation, and only then starts the next mode. Receivers reject old session/generation data before decoder or UI mutation.

### Decode into TextureView

The glasses app uses bounded access-unit reassembly and a hardware AVC `MediaCodec` rendering into a `TextureView` inside the canonical 480x640 viewport. Surface recreation rebuilds the decoder and requests a keyframe. While decoded video is playing, the activity keeps the glasses display awake.

### Route glasses controls as phone media keys

The glasses intercept coordinate-free temple/ring classifications before focused views. With the overlay hidden, a tap sends play/pause, a horizontal swipe sends rewind/fast-forward, and a double tap opens the overlay. The overlay's visual and cyclic focus order is play/pause, seek backward, seek forward, and Exit; every opening resets focus to the first play/pause control. With the overlay visible, swipes change the selected control, a tap activates it, and a double tap hides the overlay. The Exit control sends `LIVE_STOP(USER)` for the active session, releases the local decoder, and returns the glasses to `READY` while retaining the healthy DBP1 transport and glasses task. Raw contact and paired firmware aliases are consumed and deduplicated. `MEDIA_KEY` carries the current session and generation so late input is silently ignored. The phone dispatches paired Android media-key down/up events to the active media session; the bridge never guesses a seek duration or targets a particular application.

On every non-playing main state, a double tap uses a separate guarded-exit controller. The first double tap arms exit and displays a dedicated prompt above ordinary status text for three seconds; a second double tap within that window calls `finishAndRemoveTask()`. Timeout, pause, destruction, transition into playback, or any fresh main-state render disarms and hides the prompt. Single taps and swipes remain inert on the main surface. Playback double taps continue to show or hide the media-control overlay and never arm application exit.

### Keep gesture policy phone-owned and bounded

The companion persists a compact gesture policy and synchronizes it to the glasses after every DBP1 handshake and whenever the user saves the Gesture Settings page. The settings affect the playing surface only while the playback overlay is hidden; the visible overlay retains tap-to-activate, swipe-to-focus, and double-tap-to-hide so its controls remain operable. The non-playing main screen retains its dedicated application-exit gesture and honors the same second-double-tap confirmation flag.

Hidden-overlay swipes can be disabled, emit one rewind/fast-forward media step, emit previous/next playlist media keys, or request the previous/next enabled frame-placement favorite. A direction-reversal flag swaps previous and next before command selection. Key/firmware swipe aliases are collapsed into one logical action: after the first accepted direction event, every subsequent direction event extends a bounded quiet-gap latch, and no new action is accepted until the stream has been quiet long enough to represent a new gesture. Hidden-overlay tap can be disabled, emit play/pause, or open the overlay. Hidden-overlay double tap can open the overlay, return to the bridge main screen by ending only the active live mode, or close the glasses task. Exit actions may require a second double tap within the existing bounded confirmation window.

The Gesture Settings hierarchy presents tap and double tap first. Its final plain `Swipes` section keeps swipe mode, reversal, and the ordered enabled-favorite selection together so the `FAVORITES` action and its input set are configured in one place, without wrapping the whole section in another card.

The wire carries only bounded enum values and flags. Favorite identities and layout metadata remain phone-owned: the glasses sends session-scoped previous/next favorite commands, and the companion resolves them against the current persisted favorite order before applying the normal acknowledged display-layout path. Gesture selection persists disabled favorite IDs rather than enabled IDs, so a newly created favorite is automatically selected and deleted favorites disappear without migration.

### Put frame placement on a dedicated bounded editor surface

`DISPLAY_LAYOUT` / `DISPLAY_LAYOUT_ACK` retain the existing 250-1000 permille uniform scale and X/Y wire fields. X maps from left-aligned through centered to right-aligned for a reduced frame and is clamped so neither horizontal edge can leave the viewport; a full-size frame therefore has no horizontal travel. Y remains bounded so at least 10% of the frame remains visible vertically. Direct dragging, stored layouts, favorites, preview, and glasses use the same geometry contract. The displayed rectangle is always 3:4, matching 480x640, because the bridge does not infer source aspect ratio. The companion moves editing to a dedicated page, renders the glasses viewport smaller than its gesture area so reset handles remain comfortably reachable, marks vertically clipped frame regions with diagonal hatching, and retains Reset without Fit, Zoom, or Stretch modes.

Named favorites persist only layout metadata in bridge-owned storage. Users can apply or delete a favorite and reorder the list with direct drag-and-drop backed by deterministic persisted moves. The favorites list has no nested scrolling viewport: it expands to the measured height of every row and lengthens the outer Frame Placement page, whose single page scroll remains responsible for navigation. The final list row is `Add`; it opens a Material modal with a non-blank name and precise bounded X/Y numeric fields initialized from the current frame. Saving creates the favorite and applies the entered layout through the ordinary acknowledged path. Loading, saving, or applying a favorite clamps X so the frame remains fully inside the viewport and Y to the shared vertical overscan bound. Applying a favorite sends the same acknowledged `DISPLAY_LAYOUT` request as direct editing.

`Preview Content` is an explicit opt-in toggle and is reset to off on every process cold start. When enabled during an active video session, a bounded low-rate sampler may show the actual encoded content inside the editor. It must use an asynchronous latest-frame-only path, never block encoder/USB/decoder threads, never change video bitrate or queue policy, discard protected/unavailable frames, and release all preview resources when the toggle, page, session, or process stops. The glasses apply acknowledged geometry by resizing and positioning the video `TextureView`; controls and status remain full-screen.

### Use restrained Material You without adding video-path weight

The companion uses Material 3 Views with system dynamic color where Android supports it and a deterministic bridge seed palette otherwise. Its top-level hierarchy is connection health, exclusive mode actions, a navigation action for Frame Placement, and expandable technical details containing performance counters; actionable errors remain distinct. System-bar insets are applied to the scrolling content. Connection action is `Connect` while disconnected and a red `Disconnect` while connected; there is no second Stop action. Active mode styling is sufficient and no separate selected-mode label is shown.

The glasses use Material spacing, typography, and focus shapes over a pure-black background suited to the green optical display. The fixed 480x640 main surface uses `Display Bridge` as its heading and shows each state exactly once: no DB badge, no duplicate status chip/title, and no tinted page/card fill. After External Display handshake it shows `WAITING FOR VIDEO` without exposing the protocol source-kind string; `LOADING` is reserved for an actual decoder startup after video arrives. Playback controls remain icon-first, focus-first, and translucent so video stays primary. Material UI construction SHALL NOT add work to encoder, decoder, or USB threads.

Launcher artwork uses one recognizable transfer metaphor: a video display in the upper-left sends a shrinking pixel trail toward equal-width glasses lenses in the lower-right, with slight display/glasses overlap. The phone APK packages a black legacy icon plus a black adaptive background and pale mint/green foreground. The glasses APK packages a transparent green-only legacy icon and a transparent adaptive background with a green-only foreground. Both APKs expose round and monochrome adaptive variants where supported, and launcher assets remain independent from in-app UI decoration.

### Avoid inferred ADB ownership

The USB function broadcast's `adb` extra means the function is enabled, not that an authenticated ADB transport currently owns the cable. The glasses therefore map a connected/configured cable without an accessory to `WAITING_FOR_BRIDGE`. `ADB_OWNS_DATA_CABLE` is reserved for independently proven active ADB ownership and is never inferred solely from the USB function flag or missing phone-side accessory permission.

## Risks / Trade-offs

- [A third-party app enumerates but does not select the display] -> Record display and application evidence; this is an application capability limitation, not a reason to add player-specific code.
- [No tested installed app supports the synthetic display] -> Keep External Display available and report the unproven device gate without changing the bridge into an embedded player.
- [AOA dispatch ambiguity] -> Use a unique model/version filter and verify both application pairs remain installed.
- [AOA re-enumeration appears as duplicate permission] -> Coalesce requests for each enumerated device, label both stages, and request again only when Android reports no permission for the new AOA identity.
- [USB stalls grow latency] -> Bound priority queues and drop obsolete non-key video.
- [Late packets resurrect a decoder] -> Check session/generation before reassembly, config, decoder creation, feedback, or UI.
- [Projection stops asynchronously] -> Converge callback, detach, service stop, user stop, and replacement on one idempotent cleanup.
- [Phone lock stops MediaProjection by platform policy] -> Keep AOA connected, report the exact stop reason, and require a fresh user-approved session after unlock.
- [Placement preview adds backpressure] -> Keep it opt-in, latest-frame-only, low-rate, and independently disposable; disable it on cold start and any session/page teardown.

## Migration Plan

1. Replace all player-specific labels, identity strings, actions, state, protocol names, and UI with generic External Display terminology.
2. Rebuild and update both new APKs on exact serials; verify four-package coexistence and accessory dispatch.
3. Prove External Display with any independently installed capable application without bridge-side configuration or launch.
4. Prove Screen Mirror and bidirectional mode switching.
5. Run detach and sustained-operation gates.

Rollback removes only the two `com.azhilin.rokid.displaybridge.*` packages.

## Open Questions

- Which currently installed application will provide the first independent presentation-display proof?
- Which Pixel hardware AVC profile produces the most reliable sustained decoder path on RG_glasses?
