# Changelog

All notable project changes are documented here.

## [0.3.1] - 2026-08-11

Wired connection selection hotfix.

### Fixed

- Prevented a stale wireless transport snapshot from switching the Connection control back to Wireless after Wired was selected.
- Wired Connect now stops Wi-Fi Direct and wireless recovery, clears stale wireless state, and starts an explicit USB/AOA attempt without duplicating the immediate USB scan.

## [0.3.0] - 2026-08-11

### Added

- Optional local-only Wi-Fi Direct/TCP transport for the existing bounded DBP1 video stream.
- One-time authenticated wireless pairing without USB, accounts, cloud services, QR codes, or typed PINs.
- Explicit trust-on-first-use pairing with no gesture/code entry; Android-Keystore-encrypted single-peer trust and mutual authenticated reconnect.
- AES-256-GCM protection for every wireless DBP1 record with fresh per-connection directional keys.
- Wireless permission, discovery, group, TCP, pairing, authentication, and recovery diagnostics.
- Bounded recovery for transient Wi-Fi Direct `BUSY` responses, serialized P2P cleanup/setup, and OEM-compatible system broadcast delivery.

### Changed

- USB/AOA and wireless connections now implement one transport-neutral DBP1 boundary with explicit single-transport ownership.
- Double tap remains reserved for immediate Display Bridge exit, including during wireless connection setup.
- Both APKs declare Android's `INTERNET` permission only because Java TCP sockets require it; the bridge remains local-only and has no Internet service.
- The companion combines Wired and Wireless controls in one Connection card; Layout, Gestures, and Technical details moved to the menu.
- Layout favorites are inserted first, support scale during add/edit, and use apply/edit/delete icon actions.
- The companion remembers the selected Output mode and applies it after either Wired or Wireless connects; Screen Mirror opens Android's capture-permission prompt when a new grant is required.

## [0.2.3] - 2026-08-07

Android 16 foreground-service startup hotfix.

### Fixed

- Restored the normal `CHANGE_NETWORK_STATE` permission required by Android as a `connectedDevice` foreground-service prerequisite before USB permission is available.
- Added manifest regression coverage so the companion cannot again ship with a cold-start crash caused by the missing prerequisite.

## [0.2.2] - 2026-08-06

Repository-opening hardening release.

### Changed

- AOA manufacturer is now `Zhilin` and the accessory URI points to the canonical GitHub repository.
- Removed both unused FileProviders and their path resources. The network-state permission removed in this release was restored in 0.2.3 after real Android 16 verification proved it is a foreground-service prerequisite.
- Added source-available noncommercial licensing, privacy/security guidance, contribution policy, and dependency update automation.
- Backed up the release keystore and signing properties in a master-password-protected Bitwarden Secure Note through CLI.
- Anonymized legacy project references in OpenSpec and repaired duplicate task numbering without changing acceptance status.

## [0.2.1] - 2026-08-06

First stable MVP release.

### Changed

- Both APKs are built with the dedicated Display Bridge release certificate.
- Release signing material is kept outside Git and must be provided through ignored local properties or environment variables.
- Release packaging now fails closed when signing credentials are unavailable instead of silently producing unsigned APKs.

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
