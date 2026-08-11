## Why

The companion currently begins USB discovery only after the user has already opened it, while the glasses application has several controls that stop playback and leave the user inside an intermediate Display Bridge screen. The connection flow should be optional but immediate for the known glasses USB identity, and every glasses action described as leaving playback should close the glasses application instead of exposing that intermediate screen.

## What Changes

- Add a persisted phone setting, enabled by default, that allows Android to open Display Bridge when the known RG_glasses USB device is attached.
- Restrict the attach entry point to the observed glasses normal-mode USB VID/PID and revalidate the device before opening the companion; unrelated USB devices must never launch the companion.
- Keep disabling the setting effective across restarts by disabling the dedicated attach-dispatch component while leaving the normal launcher activity unchanged.
- Change the glasses playback-overlay Exit and the legacy `MAIN_MENU` double-tap action to stop the active live session and immediately finish the glasses application task.
- Remove the now-duplicate phone gesture choice for returning to the Display Bridge main screen while migrating any persisted legacy choice to Exit Application behavior.

## Capabilities

### New Capabilities

- `selective-usb-autostart`: Persisted, user-controlled companion launch on attachment of the exact supported glasses USB identity only.
- `direct-glasses-exit`: One consistent glasses exit contract that sends a bounded live stop when needed and immediately closes the application task.

### Modified Capabilities

None.

## Impact

- Companion manifest, a dedicated USB attach dispatcher, device-filter XML, persisted settings, and Material dashboard UI.
- Glasses input routing, playback overlay exit handling, persisted gesture migration, and companion gesture settings UI.
- Companion and glasses unit/manifest/UI contract tests; no DBP1 wire-format or audio-path change.
