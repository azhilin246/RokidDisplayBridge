# Architecture

## Components and identities

| Surface | Companion | Glasses |
|---|---|---|
| Package | `com.azhilin.rokid.displaybridge.companion` | `com.azhilin.rokid.displaybridge.glasses` |
| Role | USB host or Wi-Fi Direct peer, source VirtualDisplay, hardware AVC encoder | USB accessory or Wi-Fi Direct peer, bounded DBP1 receiver, hardware AVC decoder |
| Exported provider | None | None |
| Persisted state | Display layout, favorites, gesture policy, encrypted wireless peer trust | Encrypted wireless peer trust; no persisted live session state |
| Protocol | DBP1 with private session/generation state | DBP1 with private session/generation state |

The companion publishes the unique AOA identity `Zhilin / Rokid Display Bridge / 1.0 / https://github.com/azhilin246/RokidDisplayBridge / rokid-display-bridge-v1`. The glasses filter matches only this manufacturer, model, and version.

## Runtime ownership

`BridgeService` owns the selected USB/AOA or Wi-Fi Direct/TCP connection, DBP1 handshake, exclusive mode state, live session/generation, encoder, source VirtualDisplay, sender queues, counters, and cleanup. `MainActivity` owns mode and pairing actions and launches fresh system MediaProjection consent. Consent data is delivered once to the service and is never persisted or reused.

Only one producer can be active:

```text
IDLE -> EXTERNAL_DISPLAY
IDLE -> SCREEN_MIRROR
EXTERNAL_DISPLAY <-> SCREEN_MIRROR (serialized stop, cleanup, new session)
```

Replacement stops the producer, stops accepting samples, sends `LIVE_STOP`, releases source/projection and encoder resources, clears queues, invalidates session/generation, and only then starts the next mode. Cleanup is idempotent across user stop, projection stop, USB detach, service destruction, and mode replacement.

## Shared video pipeline

Both modes render into the input `Surface` of `SurfaceToAvcStream`. It selects a hardware surface-input AVC encoder, targets 480×640 at up to 30 fps, starts at 1.2 Mbit/s, and adapts within 0.8–1.6 Mbit/s from sender pressure and decoder feedback. Codec configuration and access units are fragmented through bounded DBP1 messages.

```text
Presentation VirtualDisplay ─┐
                             ├─> SurfaceToAvcStream -> DBP1 -> AOA or AES-GCM TCP records -> MediaCodec -> TextureView
MediaProjection VirtualDisplay┘
```

External Display publishes `PUBLIC | PRESENTATION | OWN_CONTENT_ONLY` without `AUTO_MIRROR` or `SECURE`. It remains available to independently launched applications; the bridge does not choose content or configure target applications.

Screen Mirror creates its encoder VirtualDisplay from freshly approved MediaProjection consent. On API 34+ the request targets the whole default display. Android may stop projection when the phone locks; the bridge keeps the AOA connection but requires a new user-approved projection session.

## Bounded DBP1 transport

DBP1 carries video configuration, fragmented access units, feedback, diagnostics, display layout, gesture settings, media commands, discontinuity, and stop/error state. It carries no audio.

The protocol bounds frame size, access-unit bytes, fragment count, reassembly count/deadline, sender queue depths, diagnostics, and gesture payloads. Control traffic has priority over video. Stale session/generation data is discarded before reassembly or decoder mutation.

The glasses decoder drains every accepted access unit to avoid a self-sustaining drop/keyframe loop. Missing reference continuity, surface recreation, and decoder recovery request a new keyframe without terminating a healthy session.

### Wireless transport and trust

The phone advertises `_rdbw._tcp` through DNS-SD. The manually launched glasses receiver joins only the matching service; whichever device becomes Wi-Fi Direct group owner listens on the fixed local port and the other connects to it. TCP uses `TCP_NODELAY`, bounded socket buffers, finite connect/handshake/write-stall deadlines, and binds to the P2P interface even when a phone VPN is active. Only one authenticated transport owns DBP1 session state; attaching USB does not silently replace active wireless playback.

A phone VPN/proxy toggle may remove the Wi-Fi Direct group despite LAN routing exclusions. After a previously authenticated wireless link is lost, both peers therefore rebuild P2P discovery, group formation, authenticated TCP, and DBP1 automatically with a capped 1.5/3/5/8-second retry backoff. The phone discards the stale encoder, queue, and live identity but retains the active presentation or MediaProjection virtual display with no surface. It starts a fresh DBP1 live session and attaches a new encoder surface after reconnect, preserving Screen Mirror authorization and requiring no glasses tap or repeat pairing. Explicit disconnect, transport selection, trust reset, stop, or glasses exit cancels recovery.

The companion persists one preferred Output mode independently of transport. A completed USB or wireless DBP1 handshake starts External Display in the service when selected. Screen Mirror is edge-triggered by the companion on a new ready connection so Android's MediaProjection prompt appears once rather than looping after cancellation; an already active projection is retained across wireless recovery.

Initial wireless pairing is an explicit trust-on-first-use handshake before DBP1. Fresh P-256 ECDH keys and nonces produce a transcript-bound secret; mutual HMAC confirmations complete automatically, then derive a single stored peer key protected with Android Keystore AES-GCM. Reconnect exchanges fresh nonces and mutual proofs. Pairing and reconnect both derive fresh directional traffic keys, and each encoded DBP1 frame is carried as one bounded AES-256-GCM record with an authenticated sequence number before `HELLO` begins.

## Presentation and wake behavior

The glasses render hardware AVC output into a `TextureView` in the fixed 480×640 viewport. The first complete access unit of each live session triggers one bounded wake pulse. Once decoding reaches `PLAYING`, the root view remains keep-screen-on until playback stops or errors.

The full video rectangle always uses the glasses 3:4 aspect ratio. Scale is 250–1000 permille. A reduced frame can move horizontally while both edges remain inside the viewport. Vertical movement can place up to 90% of the frame outside the viewport. Companion editing, preview, persisted layouts, favorites, protocol acknowledgements, and glasses geometry share this contract.

Frame Placement provides a cold-start-off, latest-only low-rate preview. Favorites expand the outer page, support drag-and-drop ordering, and store precise X/Y values. Applying a favorite uses the same acknowledged `DISPLAY_LAYOUT` path as direct editing.

## Input and media routing

Gesture policy is stored on the phone and synchronized after handshake or settings changes. Hidden-overlay tap and swipe actions are configurable. Pairing consumes no gestures; double tap is reserved for immediate application exit. Repeated firmware direction events remain latched until a 350 ms quiet gap, so a continuous swipe yields at most one logical action.

Accepted playback and playlist actions return to Android as paired media-key down/up events. Favorite actions resolve against the current enabled phone-owned order. The visible glasses overlay retains fixed play/pause, seek backward, seek forward, and Exit navigation.

## Audio and protected content

Audio is never captured or transported. There is no playback capture, microphone use, AAC encoder, DBP1 audio sample, glasses `AudioTrack`, forced route change, or Bluetooth control. Android and the originating phone application retain audio ownership.

Secure or DRM-protected content may be black in Screen Mirror, following platform policy.
