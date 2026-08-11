## 1. Shared wireless security

- [x] 1.1 Remove gesture SAS derivation, input routing, UI, failure cooldown, and obsolete tests
- [x] 1.2 Return fresh directional traffic keys from bounded P-256 ECDH pairing and stored-peer reconnect handshakes
- [x] 1.3 Add Android Keystore-backed single-peer trust stores and forget/replace tests in both APKs

## 2. Transport boundaries

- [x] 2.1 Introduce phone and glasses DBP1 connection interfaces and adapt the existing AOA transports without changing USB behavior
- [x] 2.2 Add bounded AES-GCM record protection to authenticated TCP DBP1 connections with TCP_NODELAY, finite deadlines, cleanup, and unit coverage
- [x] 2.3 Enforce explicit single-transport ownership and serialized session cleanup in the phone service and glasses runtime

## 3. Wi-Fi Direct lifecycle

- [x] 3.1 Add phone group-owner, DNS-SD advertisement, server-socket, runtime-permission, and cleanup lifecycle
- [x] 3.2 Add glasses service discovery, matching-peer join, group-owner network binding, reconnect, and cleanup lifecycle
- [x] 3.3 Expose distinct permission, discovery, group, TCP, pairing, authentication, active, contention, and failure diagnostics
- [x] 3.4 Automatically rebuild a lost authenticated wireless link and resume the active virtual-display source without repeat pairing, glasses input, or MediaProjection consent

## 4. Pairing and user experience

- [x] 4.1 Keep phone Pair/Connect/Forget wireless actions without any gesture-sequence UI
- [x] 4.2 Keep glasses manual wireless start and authenticated reconnect UI without pairing input/progress
- [x] 4.3 Preserve direct double-tap exit while removing all pairing-specific gesture routing
- [x] 4.4 Document local-only INTERNET permission, wireless startup, pairing, reconnect, transport selection, and recovery

## 5. Verification

- [x] 5.1 Run protocol, companion, and glasses unit tests plus lint and debug APK assembly
- [x] 5.2 Validate the OpenSpec change strictly and run repository whitespace/diff checks
- [x] 5.3 Verify exact-device permission/capability state and record the remaining manual Wi-Fi Direct, video, Bluetooth-audio, and soak acceptance gates
- [x] 5.4 Run recovery policy/unit tests, release lint/build, strict OpenSpec validation, and install both release APKs on the exact devices
