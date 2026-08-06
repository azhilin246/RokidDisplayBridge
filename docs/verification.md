# Verification record

Release candidate: `v0.2.1` stable MVP
Last updated: 2026-08-06 (Europe/Moscow)

## Automated release gate

The release candidate was rebuilt from scratch with `--rerun-tasks` and passed:

| Module | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| DBP1 protocol | 13 | 0 | 0 | 0 |
| Android companion | 39 | 0 | 0 | 0 |
| Glasses receiver | 32 | 0 | 0 | 0 |
| **Total** | **84** | **0** | **0** | **0** |

- `:apps:android-companion:lintRelease` passed.
- `:apps:glasses:lintRelease` passed.
- Both `assembleRelease` tasks passed with Android Gradle Plugin 9.2.1, Gradle 9.5.1, Android Studio JBR 21, and compile SDK 36.
- `apksigner verify --verbose --print-certs` passed for both APKs.
- `openspec validate build-display-bridge --strict` passed.
- `openspec validate harden-low-latency-live-video --strict` passed.

Compiler output contains only known Android deprecation warnings for compatibility paths; lint reports no blocking issue.

## Release artifacts

| Artifact | Package | Version | SHA-256 |
|---|---|---|---|
| `display-bridge-companion-v0.2.1-release.apk` | `com.azhilin.rokid.displaybridge.companion` | `0.2.1` (`3`) | `3B77785CC9B53DEAF4520927359DA9669521258D9353B8F7F12F802757C0782F` |
| `display-bridge-glasses-v0.2.1-release.apk` | `com.azhilin.rokid.displaybridge.glasses` | `0.2.1` (`3`) | `F189CD2543BCFE512FF69F2DF8B71D0CC258D0F284C07505FAAFED2E31F18817` |

Both APKs use APK Signature Scheme v2 and the same dedicated release certificate:

- Subject: `CN=Display Bridge, OU=Rokid Apps, O=Azhilin, L=Moscow, C=RU`
- Certificate SHA-256: `2C:AA:FA:50:01:48:27:FB:3B:A8:4C:E7:A4:0A:F4:2B:F7:5B:33:22:D8:B6:9E:97:BE:B1:29:49:F2:E5:F5:EC`

The private key and credentials are kept outside Git. They are not included in repository history or GitHub release assets.

## Device evidence

Development and iterative testing used a Pixel 10 owner profile and RG_glasses with a physical 480×640 display. The user confirmed that the application-neutral External Display MVP renders real content on the glasses. Companion and glasses packages have also been installed and cold-started independently without a matching fatal `AndroidRuntime` failure.

This evidence is intentionally summarized without publishing personal ADB serials, process IDs, local paths, or unrelated installed package names.

The `v0.2.1` release certificate was verified automatically, but the release-signed APKs were not installed over the existing debug builds because Android requires a one-time uninstall when changing signing certificates.

## Pending acceptance

The following remain explicit post-MVP gates rather than inferred successes:

- sustained playback with bounded queues and no long-run freeze;
- full detach/reconnect and mode-switch matrix;
- physical temple/ring verification for every configurable gesture;
- visual confirmation of the latest frame-placement, favorite sorting, and preview refinements;
- Screen Mirror lock/unlock behavior across platform versions;
- secure off-machine backup of the release keystore and credentials.

OpenSpec task checkboxes remain the source of truth for incomplete hardware acceptance. A passing build does not mark those tasks complete.
