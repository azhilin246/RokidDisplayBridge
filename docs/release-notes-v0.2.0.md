# Display Bridge v0.2.0 — MVP

This is the first published MVP preview of Display Bridge for a Pixel phone and Rokid Glasses.

## Highlights

- Universal Android presentation display over USB/AOA.
- Whole-screen mirroring after explicit Android consent.
- Hardware AVC encoding and decoding at up to 480×640 and 30 fps.
- Video-only transport: sound remains on the phone or its selected Bluetooth route.
- Low-latency bounded queues, adaptive bitrate, decoder feedback, and keyframe recovery.
- Material You phone UI and pure-black glasses UI.
- Configurable glasses gestures and Android media-key forwarding.
- Frame placement with preview, precise X/Y, favorites, and drag-and-drop sorting.
- First-frame wake, playback keep-awake, clean detach, and idempotent session switching.

## Install

The two attached APKs are debug-signed MVP builds:

1. Install `display-bridge-companion-v0.2.0-debug.apk` on the phone owner profile.
2. Install `display-bridge-glasses-v0.2.0-debug.apk` on the glasses.
3. Open Display Bridge on the phone and connect the glasses by USB.

## Known limitations

- External Display works only with Android applications that support presentation displays.
- Screen Mirror requires fresh MediaProjection consent for every session.
- Android may stop MediaProjection when the phone locks.
- Secure and DRM-protected content may render black.
- The APKs are debug-signed and intended for direct MVP testing, not store distribution.
- Remaining hardware acceptance is tracked in `openspec/changes/` and `docs/verification.md`.
