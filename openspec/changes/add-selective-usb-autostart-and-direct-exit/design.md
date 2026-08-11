## Context

The companion launcher Activity currently owns the only path that marks the UI visible and starts USB discovery. Android can dispatch `USB_DEVICE_ATTACHED` to an exported Activity whose manifest metadata references a device filter, but the behavior must be user-controlled and must not react to arbitrary USB peripherals. The target glasses were verified in normal device mode as VID/PID `18d1:4ee7` with Rokid/RG_glasses descriptor text before AOA re-enumeration.

On the glasses, playback-overlay Exit and the persisted `MAIN_MENU` double-tap action currently send `LIVE_STOP` and render the in-app `READY` screen. Other exit paths finish the task. This creates two meanings for Exit and exposes a screen the user no longer wants.

## Goals / Non-Goals

**Goals:**

- Let the user enable or disable companion launch when the supported glasses are attached.
- Keep the setting enabled by default and persisted across process/device restarts.
- Use both an exact manifest VID/PID filter and a strict runtime descriptor check before showing the companion.
- Make every glasses playback exit stop the active session, avoid a `READY` render, and immediately finish the task.
- Preserve DBP1 compatibility with already persisted or received `MAIN_MENU` enum values.

**Non-Goals:**

- Launching for arbitrary Android, AOA, storage, display-adapter, or charging devices.
- Starting Screen Mirror without consent or changing the existing automatic External Display default after handshake.
- Changing DBP1 enum ordinals, audio behavior, accessory identity, or cable-detach HOME behavior.

## Decisions

### Use a dedicated attach-dispatch Activity

Add a no-display exported `UsbAttachActivity` with `USB_DEVICE_ATTACHED` metadata pointing to a filter containing only `18d1:4ee7`. It verifies that the action and `UsbDevice` match the strict Rokid descriptor predicate, checks the persisted toggle, starts the normal `MainActivity`, and immediately finishes. Keeping USB dispatch outside `MainActivity` avoids exposing USB intent handling to ordinary launcher starts and makes the component independently disableable.

### Persist and enforce the toggle through component state

`UsbAutoLaunchSettings` owns a bridge-specific SharedPreferences file and uses `PackageManager.setComponentEnabledSetting(..., DONT_KILL_APP)` to enable or disable only `UsbAttachActivity`. The setting defaults to enabled. `MainActivity` synchronizes component state on creation, and a Material switch on the dashboard commits changes immediately. Disabling the component prevents Android from resolving future attach intents while leaving the launcher and foreground service untouched.

### Require exact identity twice

The manifest filter rejects every VID/PID other than the verified normal-mode glasses identity. Runtime matching additionally requires a Rokid or RG_glasses manufacturer/product descriptor. The dispatcher fails closed if the extra or descriptor is absent. AOA PID ranges are deliberately excluded because they are generic and the running companion already owns the normal-to-AOA transition.

### Collapse in-app main-menu exits into task exit

The wire enum `MAIN_MENU` remains decodable, but both the glasses router and the phone settings store normalize it to Exit Application semantics. The phone settings UI removes the duplicate main-screen choice. Overlay Exit and configured exit share one path that sends `LIVE_STOP(USER)` if a session is active, performs local idempotent decoder/session cleanup without rendering `READY`, and calls `finishAndRemoveTask()`.

## Risks / Trade-offs

- [A firmware revision changes the normal-mode VID/PID or descriptor] -> Fail closed and retain manual launcher access; add new verified identity only after exact-device evidence.
- [Android retains an explicit component override across an update] -> Synchronize it from persisted settings every time `MainActivity` opens.
- [A legacy `MAIN_MENU` policy reaches old/new peers] -> Keep the wire enum unchanged and normalize only behavior, preserving protocol compatibility.
- [The stop frame cannot be delivered before task teardown] -> Submit the bounded control frame through the ordered writer, suppress intermediate decoder UI, and finish after write completion or a 500 ms safety timeout; do not wait for a protocol acknowledgement.

## Migration Plan

1. Existing installs receive auto-open enabled because no preference key exists and the manifest component is enabled.
2. Existing persisted `MAIN_MENU` gesture settings load as `EXIT_APP` and are saved in normalized form on the next settings save.
3. Rollback removes the attach dispatcher and restores the previous menu behavior; no DBP1 or stored-layout migration is needed.

## Open Questions

None.
