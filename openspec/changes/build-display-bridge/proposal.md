## Why

Rokid Glasses need a standalone, phone-owned bridge that exposes a standard Android presentation display to any application capable of using an external display, plus a separately consented screen-mirror mode. The bridge must remain independent of particular media players and unrelated installed applications.

## What Changes

- Add independent companion and glasses APKs with unique packages, labels, icons, services, actions, authorities, preferences, diagnostics, protocol state, and AOA identity.
- Add one bounded, video-only H.264 `LIVE_*` protocol and `Surface` encoder pipeline shared by public presentation-display and MediaProjection sources.
- Add a generic External Display mode that creates a 480x640 public presentation `VirtualDisplay` discoverable by other processes and does not select, configure, or launch any third-party application.
- When the companion is open, automatically begin the USB/AOA connection flow and start External Display after handshake; keep Screen Mirror as an explicit independent action that can replace it.
- Add a separate Screen Mirror mode with fresh MediaProjection consent per session and no audio capture.
- Add a glasses receiver using AOA bulk transport, hardware AVC decoding, a 480x640 `TextureView`, bounded reassembly, stale-session filtering, feedback, and non-obstructive status overlay.
- Use Android accessory dispatch to open the glasses receiver without ADB and return to the glasses HOME screen when the active accessory cable detaches.
- Add a glasses playback-control overlay ordered play/pause, seek backward, seek forward, and Exit; coordinate-free temple/ring input sends session-scoped Android media-key commands back to the phone, while Exit stops only the active bridge mode.
- Add phone-owned glasses gesture settings for hidden-overlay tap, double tap, and a final plain `Swipes` section containing direction reversal, playlist navigation, and enabled frame-placement favorites, with one action per continuous swipe stream.
- Add a guarded main-screen exit: the first double tap shows a dedicated three-second confirmation prompt and the second double tap closes the glasses task, without changing playback double-tap behavior.
- Add a dedicated frame-placement page whose frame stays locked to the glasses 3:4 aspect ratio, can move horizontally only while remaining fully inside the viewport, permits at most 90% off-screen placement vertically, marks clipped regions with diagonal hatching, supports Reset and a fully expanded list of named drag-reorderable/deletable favorites created through an `Add` modal with precise X/Y entry, and offers an explicitly enabled low-rate live preview that is always off after a cold start.
- Present both APKs through restrained Material You interfaces: a dynamic-color Material 3 companion dashboard with correct system-bar insets and a pure-black, focus-first glasses surface for the fixed 480x640 viewport.
- Give the two launcher surfaces coordinated but device-appropriate Display Bridge artwork: the phone icon uses a solid black field with pale display/glasses outlines and a green display-to-glasses transfer trail, while the glasses icon uses the same mark in green shades on transparency.
- Prefer whole-display MediaProjection consent on API 34+; when platform lock policy terminates projection, preserve the bridge connection and require fresh consent after unlock rather than attempting forbidden token reuse.
- Keep all audio playback and Android audio routing on the phone; no audio codec, protocol message, capture, or glasses playback path is introduced.
- Retain the normal Android connected-device foreground-service prerequisite needed to start the bridge before USB permission is available.

## Capabilities

### New Capabilities

- `application-isolation`: Independent packages, Android identities, persisted state, services, and AOA matching that coexist with unrelated installed applications.
- `live-video-transport`: Shared video-only H.264 lifecycle, fragmentation, bounded priority queueing, feedback, session/generation filtering, and cleanup.
- `external-display`: Generic public presentation `VirtualDisplay`, cross-process discovery diagnostics, and proof that an independent capable application can render real frames to the glasses while retaining phone-side UI and audio.
- `screen-mirror`: Fresh-consent MediaProjection producer, 480x640 hardware AVC output, protected-content notice, and projection lifecycle behavior.
- `glasses-presentation`: Separate AOA glasses app with hardware AVC decode, `TextureView` presentation, bounded reassembly, surface recovery, feedback, and connection states.
- `bridge-diagnostics`: Companion connection, handshake, identity, display, encoder, thermal, audio-route, frame, bitrate, queue, and actionable-error reporting plus real-device evidence.

### Modified Capabilities

None. This is a new standalone project.

## Impact

- New project root `RokidDisplayBridge` with independent Gradle and OpenSpec configuration.
- Android USB host/accessory APIs, `DisplayManager`, `MediaCodec`, `MediaProjection`, and foreground services.
- Two new installable package names coexist with unrelated phone and glasses applications.
- No dependency, configuration, source, package, install, or accessory identity change is made to any third-party application.
