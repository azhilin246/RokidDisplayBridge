## Context

Display Bridge currently couples DBP1 directly to Android Open Accessory: `BridgeService` owns an `AndroidAoaConnection`, while the glasses Activity owns an `AccessoryTransport` opened only from an accessory attach intent. DBP1 is already a bounded ordered byte-stream protocol, and the H.264 encoder/decoder, feedback, session/generation checks, and queue-pressure behavior do not depend on USB semantics.

The target Pixel 10 and RG_glasses both advertise Wi-Fi Direct. Live framework diagnostics show that the stock Rokid stack has successfully formed P2P groups on this pair, but its glasses-side component is privileged; Display Bridge must use public APIs, runtime permissions, explicit user actions, and actionable failure states. The glasses run Android 12/API 32 and the phone targets API 36.

## Goals / Non-Goals

**Goals:**

- Carry unchanged DBP1 live traffic over a local Wi-Fi Direct TCP stream with bounded latency and cleanup.
- Preserve USB/AOA and enforce one active transport owner and one live producer.
- Pair once without USB, QR, accounts, cloud services, or a typed code.
- Use explicit one-time TOFU pairing without USB, codes, or gesture entry.
- Encrypt and authenticate all wireless DBP1 traffic after the pairing or reconnect handshake.
- Keep double tap exclusively assigned to leaving Display Bridge.
- Authenticate every later wireless connection to the stored peer and support explicit forget/replace.
- Keep the glasses receiver user-launched rather than adding an always-running boot service.

**Non-Goals:**

- Streaming over the Internet, router traversal, cloud signaling, WebRTC, Chromecast, or Miracast.
- Audio transport or changes to Android/Bluetooth audio ownership.
- Reusing or modifying the stock Rokid Wi-Fi Direct group or privileged services.
- Seamless mid-frame migration between USB and wireless.
- Supporting multiple paired phones or multiple paired glasses in the first version.
- Using a user-chosen PIN, a reusable gesture password, or any pairing gesture sequence.

## Decisions

### Add a transport-neutral DBP1 connection boundary

Both APKs will use a small connection interface exposing open state, asynchronous frame send, close, peer/transport label, and listener callbacks. The existing AOA connection and accessory transport will implement the same boundary as the new TCP connection. Encoder, DBP1 handshake, live queue, decoder, and mode-transition code remain transport-independent.

Only one connection owns DBP1 at a time. A user-requested transport replacement performs the existing serialized producer stop, queue clear, session/generation invalidation, old-connection close, new authentication, and new DBP1 handshake. An arriving cable does not silently replace an active wireless session.

### Use phone-owned Wi-Fi Direct and one TCP stream

The phone creates an autonomous Wi-Fi Direct group, advertises a DNS-SD service with a fixed service type and protocol version, and listens on a bounded server socket. The manually launched glasses app discovers the service, joins the group, obtains the group-owner address, and connects to the advertised port. It never selects the first arbitrary peer without matching the service identity.

The socket is bound to the Wi-Fi Direct `Network`, uses `TCP_NODELAY`, finite connect/handshake/write-stall deadlines, bounded socket buffers, and the existing `FrameStreamDecoder`. TCP is chosen because DBP1 already requires ordered delivery and arbitrary read-boundary handling. Raw UDP, split control/video channels, and QUIC remain alternatives only if hardware measurements show unacceptable TCP head-of-line delay.

An Android VPN/proxy policy change can remove and recreate the Wi-Fi Direct group even when local addresses are excluded from the tunnel. A disconnect after an authenticated wireless session therefore triggers full P2P discovery/group reconstruction on both peers with capped retry backoff. Stored peer trust authenticates the replacement socket, so recovery never re-enters first-use pairing. The phone detaches the encoder surface but retains the active presentation `VirtualDisplay` or `MediaProjection` `VirtualDisplay`; after the new DBP1 handshake it creates a fresh live session and encoder, reattaches the surface, and resumes without another capture-consent prompt.

The phone requests `NEARBY_WIFI_DEVICES` on API 33+, while the Android 12 glasses path requests the location permission required by public P2P discovery. Missing permission, disabled/unavailable Wi-Fi Direct, group contention, timeout, authentication failure, and socket failure are distinct UI/diagnostic states.

### Trust the first explicit ephemeral key exchange

Pairing starts only after explicit `Pair wireless` actions on both devices. The socket first runs a bounded binary wireless handshake separate from DBP1. Both peers generate fresh P-256 ECDH keys and nonces, exchange public keys and stable random installation identifiers, derive a transcript-bound secret, and exchange mutual HMAC confirmations. This is trust on first use: an active attacker in radio range during that explicit first window could substitute keys, but ordinary passive discovery cannot recover the secret. A timeout, disconnect, or double tap destroys ephemeral state; peer trust is stored only after both confirmations succeed.

### Encrypt DBP1 after every wireless handshake

The pairing secret or stored peer key plus fresh reconnect nonces derives independent AES-256-GCM keys and nonce prefixes for each direction. Each encoded DBP1 frame becomes one bounded record carrying a monotonically increasing sequence number, length, and ciphertext/tag. The receiver rejects invalid tags, unexpected sequence numbers, markers, or lengths before DBP1 decoding. Keys exist only for the socket lifetime and are never logged or persisted.

### Persist one authenticated peer with Keystore protection

After mutual confirmation, both sides derive a long-term peer key from the pairing secret. A random Android Keystore AES-GCM key encrypts the peer identifier and key before local SharedPreferences persistence. Later connections exchange fresh nonces and mutual HMAC proofs before DBP1 `HELLO`; unknown peer IDs or invalid proofs are rejected without exposing whether a candidate identifier exists.

`Forget paired device` deletes encrypted trust data. `Replace paired device` requires an explicit local action and does not silently overwrite existing trust. Reinstall/data-clear naturally removes trust and requires pairing again.

### Preserve a local-only privacy boundary

`INTERNET` is required by Android for Java sockets but does not authorize a backend. The implementation creates no Internet client, analytics, account, telemetry upload, or remote listener. Wireless services bind only to the selected Wi-Fi Direct network and accept one authenticated peer. README/privacy text will explain this Android permission distinction.

## Risks / Trade-offs

- [Public Wi-Fi Direct APIs may behave differently from Rokid's privileged service] → Gate on runtime capability, expose exact failures, and require device acceptance before declaring seamless startup.
- [Hi Rokid or another app owns the P2P group] → Never reuse it; report contention and allow retry after the other group is released.
- [TCP retransmission can accumulate latency] → Use small bounded buffers, `TCP_NODELAY`, write deadlines, queue-pressure metrics, and stop/reconnect rather than accumulating stale video.
- [VPN activation can destroy the P2P group rather than merely reroute TCP] → Rebuild the complete P2P and authenticated DBP1 stack automatically, retain only the authorized local capture source, and discard stale encoder/session/queue state.
- [TOFU does not detect an active first-connection MITM] → Require explicit pairing mode on both devices, keep the window bounded, persist exactly one peer, and require explicit forget before replacement.
- [AEAD record overhead and copying add latency] → Encrypt one already-bounded DBP1 frame per record, retain bounded socket/queue limits, and measure on the target devices.
- [Wi-Fi and AVC increase glasses power/thermal load] → Report thermal/connection state and include a 30-minute wireless soak gate.
- [Dirty existing USB/exit work overlaps transport and input files] → Preserve those edits, build the neutral boundary around them, and add regression tests for USB autostart and double-tap exit.

## Migration Plan

1. Add protocol-independent TOFU pairing/authentication and AEAD record primitives with tests without changing USB runtime behavior.
2. Introduce the transport-neutral connection interfaces and keep AOA as the default implementation.
3. Add phone Wi-Fi Direct host and glasses discovery/client behind explicit UI actions.
4. Add Keystore trust persistence and authenticated reconnect.
5. Enable the wireless UI after unit/lint/build gates pass, then install release-signed APKs on exact devices for manual pairing and video acceptance.
6. Roll back by disabling/removing wireless actions; the existing USB/AOA path and stored display/gesture settings remain compatible.

## Open Questions

- Hardware acceptance must determine whether TCP alone stays within the target latency under induced packet loss and simultaneous Bluetooth audio.
- Hardware acceptance must determine whether third-party P2P discovery can coexist with the current Rokid firmware without requiring privileged assistance.
