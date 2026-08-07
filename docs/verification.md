# Verification record

Release candidate: `v0.2.3` Android 16 startup hotfix
Last updated: 2026-08-07 (Europe/Moscow)

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
| `display-bridge-companion-v0.2.3-release.apk` | `com.azhilin.rokid.displaybridge.companion` | `0.2.3` (`5`) | `426E74465CA79651C320A91162B1390BC66742FA23D8CC56E9A4B2B829F712E5` |
| `display-bridge-glasses-v0.2.3-release.apk` | `com.azhilin.rokid.displaybridge.glasses` | `0.2.3` (`5`) | `305D3CC92AC8585A26D715961C68E39F2C2160AA9E19A7F63E67475982750C0A` |

Both APKs use APK Signature Scheme v2 and the same dedicated release certificate:

- Subject: `CN=Display Bridge, OU=Rokid Apps, O=Azhilin, L=Moscow, C=RU`
- Certificate SHA-256: `2C:AA:FA:50:01:48:27:FB:3B:A8:4C:E7:A4:0A:F4:2B:F7:5B:33:22:D8:B6:9E:97:BE:B1:29:49:F2:E5:F5:EC`

The private key and credentials are kept outside Git. They are not included in repository history or GitHub release assets.

The PKCS12 keystore and ignored signing-properties file are backed up in the master-password-protected Bitwarden Secure Note `Rokid Display Bridge Release Signing`. Both attachments and their metadata were verified through Bitwarden CLI, the vault was synchronized, and the temporary CLI session file was deleted.

Packaged manifests were checked to confirm that FileProviders remain absent and that the companion retains `CHANGE_NETWORK_STATE`. Android 16 accepts this normal permission as the connected-device foreground-service prerequisite before USB permission exists; the application does not use it to mutate network routing. The glasses accessory filter contains manufacturer `Zhilin`, model `Rokid Display Bridge`, and version `1.0`; the companion advertises `https://github.com/azhilin246/RokidDisplayBridge` as its AOA URI.

## Device evidence

Development and iterative testing used a Pixel 10 owner profile and RG_glasses with a physical 480×640 display. The user confirmed that the application-neutral External Display MVP renders real content on the glasses.

The release-signed v0.2.2 companion reproduced an Android 16 `SecurityException` while starting `BridgeService` as a `connectedDevice` foreground service before USB permission. Release-signed v0.2.3 was installed in Pixel user 0, cold-started with the same companion-visibility intent, and remained alive as a foreground service with type `0x10`; the cleared crash buffer remained empty. The matching v0.2.3 glasses APK was then installed in place on exact serial `1901092553801899`. Package Manager reported version `0.2.3` (`5`) and release signature `[ad9a3b73]`; the pulled installed base APK matched the release artifact at SHA-256 `305D3CC92AC8585A26D715961C68E39F2C2160AA9E19A7F63E67475982750C0A`.

Session 1 then proved system accessory dispatch and detach behavior without glasses ADB. Android requested permission once for the normal `18d1:4ee7` device (`DEVICE_TO_AOA`) and once for the re-enumerated `18d1:2d01` device (`AOA_DATA`), with distinct device ids and no concurrent duplicate request per stage. AOA/DBP1 opened, External Display started automatically, and decoder feedback returned with a zero sender queue. The user confirmed that removing the active cable closed Display Bridge on the glasses and returned to HOME. USB host history and a fresh session showed automatic reconnect, a recreated 480×640 `PRESENTATION | OWN_CONTENT_ONLY` External Display, and resumed decoder feedback without a stale-session or fatal error.

Session 1 is complete. Both release-signed v0.2.3 APKs are installed on their exact target devices, system accessory dispatch opens only Display Bridge without an ambiguous chooser, and no unrelated package was uninstalled or disabled during install, detach, or reconnect verification. The two visible USB permission dialogs belong to the normal-device and re-enumerated AOA-device stages rather than duplicate requests for one Android `UsbDevice`.

This evidence is intentionally summarized without publishing personal ADB serials, process IDs, local paths, or unrelated installed package names.

Changing from the old debug certificate required a one-time removal of only the Display Bridge packages. Subsequent v0.2.2 to v0.2.3 companion update succeeded in place with the dedicated release certificate.

## Pending acceptance

The following remain explicit post-MVP gates rather than inferred successes:

- sustained playback with bounded queues and no long-run freeze;
- full detach/reconnect and mode-switch matrix;
- physical temple/ring verification for every configurable gesture;
- visual confirmation of the latest frame-placement, favorite sorting, and preview refinements;
- Screen Mirror lock/unlock behavior across platform versions.

OpenSpec task checkboxes remain the source of truth for incomplete hardware acceptance. A passing build does not mark those tasks complete.
