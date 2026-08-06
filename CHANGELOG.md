# Changelog

All notable project changes are documented here.

## [0.2.0] - 2026-08-06

First published MVP preview.

### Added

- Generic public 480×640 External Display for presentation-capable Android applications.
- Fresh-consent whole-screen Screen Mirror using the shared hardware AVC pipeline.
- Independent companion and glasses applications with a unique AOA identity and bounded DBP1 transport.
- Hardware `MediaCodec` AVC encoder/decoder, bounded fragmentation and queues, adaptive bitrate, feedback, and keyframe recovery.
- Material You companion UI and pure-black glasses UI.
- Playback overlay, configurable glasses gestures, media-key forwarding, and continuous-swipe suppression.
- Frame placement with preview, precise coordinates, named favorites, drag-and-drop ordering, and gesture cycling.
- First-frame wake pulse, playback keep-awake, USB detach cleanup, and application exit flows.
- Protocol, companion, and glasses unit tests plus Android lint and build verification.

### Known limitations

- GitHub MVP assets are debug-signed.
- MediaProjection requires fresh consent for every session and may stop when the phone locks.
- Secure and DRM-protected content may render black.
- Only applications that implement Android presentation-display behavior can use External Display.
- Some sustained-run, reconnect, and physical temple/ring acceptance checks remain pending.
