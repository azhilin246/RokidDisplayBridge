# DBP1 video bridge protocol

DBP1 is the private, video-only protocol used between the Display Bridge companion and glasses applications.

## Wireless pre-handshake

USB/AOA begins directly with DBP1. A wireless TCP connection first uses the bounded `RDBW` version-2 pre-handshake, then carries DBP1 only in encrypted authenticated records after pairing or reconnect authentication succeeds.

Initial pairing is explicit trust on first use. It exchanges fresh P-256 public keys, 16-byte installation identifiers, and 16-byte nonces. Both sides derive the same transcript-bound key and exchange confirmation tags automatically before deriving a persistent 32-byte peer key.

Later wireless connections exchange fresh nonces and mutual HMAC-SHA-256 proofs against that peer key. Both handshake variants derive independent AES-256-GCM keys and four-byte nonce prefixes for each direction. Each wireless record contains marker `RDBE`, an eight-byte monotonically increasing sequence number, a four-byte ciphertext length, and one complete encrypted DBP1 frame plus its 16-byte GCM tag. The header is authenticated as AAD. Unknown identities, invalid proofs or tags, replay/reordering, oversized messages, timeout, or double tap close the candidate connection before a DBP1 frame is accepted.

## Framing and bounds

Every frame begins with the ASCII magic `DBP1`, protocol version 1.1, a message type, flags, request id, and unsigned payload length. Version 1.1 adds display-layout and phone media-key control messages; both APKs must be updated together. The decoder rejects invalid magic, versions, types, and payloads larger than 65,536 bytes before allocating message storage.

The handshake exchanges a single video capability and the maximum payload size. DBP1 deliberately has no audio capability, audio track kind, audio configuration, or audio sample message.

The control message types added beside the live lifecycle are:

- `DISPLAY_LAYOUT` (`0x30`) and request-correlated `DISPLAY_LAYOUT_ACK` (`0x31`);
- glasses-to-phone `MEDIA_KEY` (`0x32`).

`DISPLAY_LAYOUT` is six bytes: unsigned scale permille followed by signed X and Y offset permille. Scale is clamped to 250-1000 and both offsets to -1000 through 1000. The rectangle is always the 480:640 glasses aspect ratio; no source/content aspect or fit mode crosses the wire. For viewport size `W x H` and scaled rectangle `w x h`, horizontal position is `(W-w)/2 + ((W-w)/2)*x/1000`, while vertical position is `(H-h)/2 + (H/2 + 0.4h)*y/1000`. A reduced frame can move from the left edge to the right edge without crossing either horizontal boundary; a full-size frame has no horizontal travel. Vertically, at least 10% of the frame remains visible.

`MEDIA_KEY` is 13 bytes: nonzero session id, nonzero generation, and one command (`PLAY_PAUSE`, `SEEK_BACKWARD`, or `SEEK_FORWARD`). The phone accepts it only for the current live identity and dispatches the corresponding Android media key. No seek duration or target application is encoded.

## Live lifecycle

The common lifecycle is:

1. `HELLO` / `HELLO_ACK`
2. `LIVE_START(session, generation, sourceKind, title)`
3. `LIVE_READY(session, generation)`
4. `LIVE_CONFIG(session, generation, AVC parameters and CSD)`
5. fragmented `LIVE_SAMPLE` messages and `LIVE_FEEDBACK`
6. optional `LIVE_DISCONTINUITY`
7. `LIVE_STOP`

`sourceKind` is either `PRESENTATION_DISPLAY` or `SCREEN_CAPTURE`. Both modes use the same H.264 payloads and decoder.

Session and generation must match the current receiver state before reassembly, decoder creation, feedback processing, or UI mutation. Older packets are silently discarded. A mode replacement invalidates both values after cleanup and creates a new nonzero session.

The same identity check applies to `MEDIA_KEY`, preventing a late glasses gesture from controlling media after a mode replacement.

## H.264 sample transport

`LIVE_CONFIG` carries width, height, frame rate, bitrate, AVC profile/level, and bounded CSD-0/CSD-1 blobs. A complete encoded access unit is limited to 512 KiB and split into at most 64 fragments. Every fragment repeats session, generation, access-unit id, fragment index/count, total byte count, presentation timestamp, and keyframe/end-of-stream flags.

Reassembly requires ordered, internally consistent fragments. It bounds pending units, pending bytes, fragment count, final byte count, and deadline. Any inconsistency drops that unit and requests a keyframe rather than creating an unbounded or stale decoder input.

## Flow control and diagnostics

Control frames use a bounded priority queue ahead of video. Video fragments belonging to one access unit are queued atomically. When the video queue is full, obsolete non-key access units are discarded; control traffic is never silently converted into video traffic.

`LIVE_FEEDBACK` reports decoder queue delay, decoded/dropped units, and keyframe requests. `DIAGNOSTIC` carries bounded text. `ERROR` carries a stable error code and actionable text. Neither message permits remote route changes or audio behavior.
