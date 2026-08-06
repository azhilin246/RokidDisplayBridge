# Verification record

Release candidate: `v0.2.0` MVP
Last updated: 2026-08-06 (Europe/Moscow)

## Automated release gate

The release candidate was rebuilt from scratch with `--rerun-tasks` and passed:

| Module | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| DBP1 protocol | 13 | 0 | 0 | 0 |
| Android companion | 39 | 0 | 0 | 0 |
| Glasses receiver | 32 | 0 | 0 | 0 |
| **Total** | **84** | **0** | **0** | **0** |

- `:apps:android-companion:lintDebug` passed.
- `:apps:glasses:lintDebug` passed.
- Both `assembleDebug` tasks passed with Android Gradle Plugin 9.2.1, Gradle 9.5.1, Java 17, and compile SDK 36.
- `openspec validate build-display-bridge --strict` passed.
- `openspec validate harden-low-latency-live-video --strict` passed.

Compiler output contains only known Android deprecation warnings for compatibility paths; lint reports no blocking issue.

## Release artifacts

| Artifact | Package | Version | SHA-256 |
|---|---|---|---|
| `display-bridge-companion-v0.2.0-debug.apk` | `com.azhilin.rokid.displaybridge.companion` | `0.2.0` (`2`) | `6AFF3819E40A939E23A390DA36DE7D4029426F96E4396EBA467D9EAE18625739` |
| `display-bridge-glasses-v0.2.0-debug.apk` | `com.azhilin.rokid.displaybridge.glasses` | `0.2.0` (`2`) | `6D5DD55FE1FA9D752D57C50B4B2D0D55B6B44C823BF13590D41A3EA4AF234629` |

Both APKs use the Android debug certificate and APK Signature Scheme v2. They are intended for MVP testing, not store distribution.

## Device evidence

Development and iterative testing used a Pixel 10 owner profile and RG_glasses with a physical 480×640 display. The user confirmed that the application-neutral External Display MVP renders real content on the glasses. Companion and glasses packages have also been installed and cold-started independently without a matching fatal `AndroidRuntime` failure.

This evidence is intentionally summarized without publishing personal ADB serials, process IDs, local paths, or unrelated installed package names.

## Pending acceptance

The following remain explicit post-MVP gates rather than inferred successes:

- sustained playback with bounded queues and no long-run freeze;
- full detach/reconnect and mode-switch matrix;
- physical temple/ring verification for every configurable gesture;
- visual confirmation of the latest frame-placement, favorite sorting, and preview refinements;
- Screen Mirror lock/unlock behavior across platform versions;
- production signing and release-key custody.

OpenSpec task checkboxes remain the source of truth for incomplete hardware acceptance. A passing build does not mark those tasks complete.
