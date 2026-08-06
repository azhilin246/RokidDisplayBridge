# Display Bridge v0.2.1 — Stable MVP

This is the first stable, release-signed MVP of Display Bridge for a Pixel phone and Rokid Glasses.

## Highlights

- Universal Android presentation display over USB/AOA.
- Whole-screen mirroring after explicit Android consent.
- Hardware AVC encoding and decoding at up to 480×640 and 30 fps.
- Video-only transport: sound remains on the phone or its selected Bluetooth route.
- Low-latency bounded queues, adaptive bitrate, decoder feedback, and keyframe recovery.
- Configurable glasses gestures, Android media-key forwarding, and frame-placement favorites.
- Dedicated long-lived release certificate for both Android packages.

## Install

1. If a debug build is installed, uninstall only the matching Display Bridge package first; Android cannot update a debug-signed package with a release-signed APK.
2. Install `display-bridge-companion-v0.2.1-release.apk` on the phone owner profile.
3. Install `display-bridge-glasses-v0.2.1-release.apk` on the glasses.
4. Open Display Bridge on the phone and connect the glasses by USB.

## Known limitations

- External Display works only with Android applications that support presentation displays.
- Screen Mirror requires fresh MediaProjection consent for every session.
- Android may stop MediaProjection when the phone locks.
- Secure and DRM-protected content may render black.
- Remaining hardware acceptance is tracked in `openspec/changes/` and `docs/verification.md`.
