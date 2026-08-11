## Why

Display Bridge currently requires a USB/AOA cable for discovery, launch, and every DBP1 video session. A portable wireless mode is needed so the phone can stream to the glasses without a cable or an existing router while preserving the bounded video pipeline, local-only privacy model, and explicit user control.

## What Changes

- Add a user-selectable Wi-Fi Direct transport carrying the existing DBP1 byte stream over a bounded TCP connection.
- Keep USB/AOA available and make transport ownership explicit so only one connection and live producer can own a session at a time.
- Add explicit one-time trust-on-first-use (TOFU) phone/glasses pairing over the wireless link using an ephemeral key exchange, without USB, codes, or gesture verification.
- Persist peer identity/key material only after the first handshake completes, protect it with Android Keystore, and reject unknown or stale peers on later connections.
- Encrypt and authenticate every wireless DBP1 record with fresh per-connection, per-direction keys derived by the pairing or reconnect handshake.
- Add wireless connection, pairing, authentication, socket-pressure, and recovery diagnostics without logging pairing sequences or key material.
- Retain video-only behavior, phone-owned audio, MediaProjection consent rules, frame placement, gestures, and existing DBP1 live lifecycle.

## Capabilities

### New Capabilities

- `wireless-transport`: Wi-Fi Direct discovery, TCP DBP1 transport, explicit USB/wireless ownership, lifecycle, diagnostics, and recovery.
- `gesture-pairing`: One-time explicit TOFU pairing, persistent peer trust, and authenticated reconnect. The historical capability path is retained, but pairing no longer consumes gestures.

### Modified Capabilities

None.

## Impact

- Phone companion and glasses manifests gain local-network/Wi-Fi permissions; neither application gains a cloud backend or general Internet dependency.
- Phone `BridgeService` and glasses `MainActivity` gain transport-neutral DBP1 connection boundaries plus Wi-Fi Direct managers.
- Glasses input routing no longer has a pairing-only gesture state; double tap remains application exit.
- The shared protocol gains bounded pairing/authentication messages while retaining existing live-video payloads and limits.
- Unit and device verification expand to pairing, reconnect, Wi-Fi Direct contention, TCP pressure, Bluetooth-audio coexistence, and USB/wireless selection.
