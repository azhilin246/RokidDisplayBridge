# Display Bridge

Display Bridge turns Rokid Glasses into a low-latency, video-only Android display over USB. It consists of a phone companion and a separate glasses receiver connected through Android Open Accessory (AOA) and the bounded DBP1 protocol.

> **MVP status:** `v0.2.0` is the first published preview. The core External Display and Screen Mirror paths work on the target Pixel/RG_glasses pair, while several long-run and physical-input acceptance checks remain documented as pending.

## Features

- **External Display** publishes a 480×640 public presentation `VirtualDisplay`. Any Android application that supports presentation displays can render to it.
- **Screen Mirror** captures the whole phone display after fresh Android MediaProjection consent.
- **Video only:** hardware AVC encoding/decoding; audio remains on the phone and follows normal Android/Bluetooth routing.
- **Low-latency transport:** bounded queues, latest-frame pressure handling, fragmentation/reassembly, keyframe feedback, session/generation filtering, and idempotent cleanup.
- **Glasses controls:** configurable tap, double-tap, and one-action-per-gesture swipes; media keys are routed back to Android.
- **Frame Placement:** fixed 3:4 resizing and placement, live preview, named favorites, precise X/Y entry, and drag-and-drop ordering.
- **Diagnostics:** connection, AOA identity, handshake, encoder, thermal state, FPS, bitrate, sent/dropped frames, and queue depth.

## Packages

| Target | Application ID | Role |
|---|---|---|
| Android phone | `com.azhilin.rokid.displaybridge.companion` | AOA host, display source, AVC encoder |
| Rokid Glasses | `com.azhilin.rokid.displaybridge.glasses` | AOA accessory, AVC decoder, 480×640 presentation |

## Project layout

```text
apps/android-companion/  Phone application
apps/glasses/            Glasses application
protocol/kotlin/         Shared bounded DBP1 protocol
protocol/specification/  Wire protocol reference
openspec/                Product specifications and tracked acceptance work
docs/                    Architecture, verification, and release notes
```

See [architecture](docs/architecture.md) and the [verification record](docs/verification.md) for implementation and acceptance details.

## Requirements

- Android Studio JBR or another Java 17 runtime
- Android SDK 36
- A phone with USB host support (tested on Pixel 10)
- Rokid Glasses with a 480×640 display

## Build and verify

On Windows:

```powershell
.\gradlew.bat :protocol:kotlin:testDebugUnitTest `
  :apps:android-companion:testDebugUnitTest `
  :apps:glasses:testDebugUnitTest `
  :apps:android-companion:lintDebug `
  :apps:glasses:lintDebug `
  :apps:android-companion:assembleDebug `
  :apps:glasses:assembleDebug `
  --no-configuration-cache
```

Generated APKs:

- `apps/android-companion/build/outputs/apk/debug/android-companion-debug.apk`
- `apps/glasses/build/outputs/apk/debug/glasses-debug.apk`

## Install

Always select exact device serials. The phone APK should be scoped to the owner profile:

```powershell
adb devices -l
adb -s <phone-serial> shell pm list users
adb -s <phone-serial> install --user 0 -r "apps/android-companion/build/outputs/apk/debug/android-companion-debug.apk"
adb -s <glasses-serial> install -r "apps/glasses/build/outputs/apk/debug/glasses-debug.apk"
```

## Usage

1. Install both APKs and open Display Bridge on the phone.
2. Connect the glasses to the phone by USB and approve Android's USB requests when shown.
3. External Display starts automatically after AOA/DBP1 handshake. Open any presentation-capable application on the phone.
4. To mirror the phone instead, select Screen Mirror and approve the fresh system capture prompt.
5. Configure frame placement and glasses gestures from the companion settings.

Protected or DRM content may appear black during Screen Mirror. The bridge does not capture audio, change audio routes, bypass protected surfaces, or emulate Chromecast/Miracast/HDMI.

## Release signing

The MVP GitHub assets are debug-signed for direct testing. Production distribution requires a private release keystore and a separate signed release pipeline; no signing material is stored in this repository.
