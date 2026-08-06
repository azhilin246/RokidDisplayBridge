# Short-term acceptance plan

Goal: close the 20 remaining hardware/evidence-gated OpenSpec tasks without mixing diagnosis, UI checks, and long-run stability into one session. No task is checked until exact-device logs/counters and any required user visual confirmation exist.

## Session 1 — Release installation and cable lifecycle (30–45 minutes)

1. Run `adb devices -l`, verify the exact phone owner profile and glasses serial, and record package versions before changes.
2. If Android reports a signature mismatch, remove only the two Display Bridge debug packages after explicit approval; never remove unrelated accessory applications.
3. Install both release-signed v0.2.2 APKs together because the AOA manufacturer changed to `Zhilin`.
4. Verify package coexistence and that only the Display Bridge glasses app matches the new accessory identity.
5. With glasses ADB unavailable, open the companion, attach USB, verify automatic permission/handshake/External Display startup, then detach and confirm idempotent cleanup plus HOME.
6. Reconnect once and confirm no ambiguous chooser or duplicate concurrent permission request.

Expected closures: `build-display-bridge` 4.11, 6.4, 8.4; `harden-low-latency-live-video` 4.3.

## Session 2 — Core video modes and switching (45–60 minutes)

1. External Display: use one known presentation-capable independent application, confirm presentation display discovery, non-empty encoded/sent/decoded counters, visible glasses video, phone-side controls, and phone/Bluetooth audio.
2. Launch a second application that does not implement presentation output and capture display/package/log evidence for the capability boundary without changing the bridge.
3. Screen Mirror: require fresh manual consent, confirm first video, audio ownership, explicit stop, and projection callback cleanup.
4. Switch External Display → Screen Mirror → External Display while recording session/generation and decoder diagnostics.
5. Lock/unlock the phone during Screen Mirror and verify platform stop behavior plus fresh-consent recovery.
6. Confirm Frame Placement preview receives actual current content in both source modes.

Expected closures: 6.5, 6.6, 7.5, 8.3, 10.13, and the functional portion of 10.9.

## Session 3 — Glasses UI, wake, controls, and placement (45–60 minutes)

1. Let the glasses sleep before first content in each mode; confirm one first-access-unit wake pulse and continuous keep-awake only while playing.
2. Verify overlay opens with play/pause focused, all four controls work, and Exit emits `LIVE_STOP` without closing the healthy connection.
3. Exercise every configured tap/double-tap choice, swipe reversal, playlist commands, and one-action-per-continuous-swipe behavior using glasses and ring input.
4. Create a precise favorite, drag-reorder favorites, enable/disable gesture selection, cycle them in persisted order, and verify horizontal/vertical placement bounds.
5. Visually check the final phone and glasses Material surfaces and the expanded favorites page.

Expected closures: 8.7, 9.6, 10.9, 10.15, 10.17, 11.6, 12.6, 13.4.

## Session 4 — Stability soak and evidence closeout (90–120 minutes)

1. Run External Display for at least 30 minutes and Screen Mirror for at least 30 minutes with motion-rich content.
2. Sample bitrate, FPS, sender queue, ingress depth, sent/decoded/dropped counts, keyframe requests, thermal state, and visible continuity at fixed intervals.
3. Pause/resume, force one decoder recovery, and perform one mode switch plus one detach/reconnect during the soak.
4. Fail the gate on terminal overflow, steadily growing queue/latency, unrecovered freezes, stale-session errors, or repeated first-frame wake pulses.
5. Record sanitized device builds, package/version/identity, APK hashes, commands, counter snapshots, and user visual confirmations in the verification record.

Expected closures: 8.5, 8.6 and `harden-low-latency-live-video` 4.4.

## Exit criterion

All 20 unchecked tasks are either evidence-backed and checked, or retain a precise reproducible blocker with the last proven pipeline stage. Code changes, if a session reveals a defect, receive a fresh automatic release gate before hardware verification resumes.
