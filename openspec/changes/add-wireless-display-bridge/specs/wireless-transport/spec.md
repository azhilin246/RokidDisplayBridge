## ADDED Requirements

### Requirement: User-selected wireless DBP1 transport
The companion and glasses applications SHALL support DBP1 over a local Wi-Fi Direct TCP connection while retaining USB/AOA as an independently selectable transport.

#### Scenario: Wireless connection becomes ready
- **WHEN** the user starts wireless mode on the phone and the manually launched glasses receiver discovers and joins the matching Display Bridge service
- **THEN** the applications SHALL complete pairing or reconnect authentication, derive fresh directional traffic keys, and perform the normal DBP1 HELLO over encrypted authenticated records

### Requirement: Encrypted wireless DBP1 records
Every DBP1 frame carried by the wireless transport SHALL be protected by an AEAD record layer with independent phone-to-glasses and glasses-to-phone keys and monotonically increasing nonces.

#### Scenario: Wireless frame is sent
- **WHEN** either peer sends a DBP1 frame after the wireless handshake
- **THEN** it SHALL encrypt and authenticate the complete encoded frame before writing it to TCP

#### Scenario: A record is modified, replayed, reordered, or oversized
- **WHEN** the receiver detects an invalid tag, unexpected sequence number, invalid length, or protocol marker
- **THEN** it SHALL close the wireless transport without delivering the record to DBP1

#### Scenario: USB remains available
- **WHEN** wireless support is configured but the user selects USB
- **THEN** the existing AOA discovery, permission, DBP1, video, input, and detach behavior SHALL remain available

### Requirement: Single transport ownership
The bridge SHALL allow only one transport connection and one live producer to own current DBP1 session state.

#### Scenario: User changes transport
- **WHEN** the user explicitly replaces an active USB or wireless transport
- **THEN** the bridge SHALL stop the producer, clear bounded queues, invalidate session and generation, close the old connection, authenticate the new connection, and start a new DBP1 handshake in that order

#### Scenario: Cable arrives during wireless playback
- **WHEN** USB is attached while an authenticated wireless session is active
- **THEN** the bridge SHALL retain the active wireless session until the user explicitly changes transport or the session ends

### Requirement: Bounded Wi-Fi Direct discovery and TCP lifecycle
Wireless discovery, group formation, connection, handshake, IO, and cleanup SHALL be bounded and SHALL expose actionable state without selecting an unrelated peer.

#### Scenario: Matching service is discovered
- **WHEN** Wi-Fi Direct service discovery reports peers
- **THEN** the glasses SHALL connect only to a service matching the Display Bridge service identity and supported protocol version

#### Scenario: Wireless setup cannot complete
- **WHEN** permission is absent, Wi-Fi Direct is unavailable, group formation times out, another group conflicts, TCP connect times out, authentication fails, or the socket closes
- **THEN** the bridge SHALL release partial wireless resources and report the specific stage and reason

#### Scenario: TCP video experiences sustained backpressure
- **WHEN** socket writes exceed the bounded stall deadline or sender pressure cannot drain
- **THEN** the bridge SHALL stop or reconnect the wireless transport rather than retaining an unbounded stale video backlog

### Requirement: Local-only network scope
The wireless bridge SHALL use the selected Wi-Fi Direct network for peer traffic and SHALL NOT require an Internet route, cloud service, account, analytics endpoint, or router.

#### Scenario: Phone VPN is active
- **WHEN** the phone has an active VPN while the Wi-Fi Direct connection is established
- **THEN** Display Bridge SHALL bind wireless sockets to the Wi-Fi Direct network so DBP1 traffic remains on the local peer link

### Requirement: Wireless diagnostics and recovery
The companion SHALL report transport type, discovery/group state, authenticated peer state, TCP connection state, socket pressure, and recovery reason without exposing secrets.

#### Scenario: Diagnostic snapshot is shown
- **WHEN** wireless mode is connecting, active, or recovering
- **THEN** diagnostics SHALL identify the wireless stage and bounded counters while omitting peer keys, traffic keys, nonces, authentication tags, and private captured content

#### Scenario: Phone network policy toggle recreates the peer link
- **WHEN** an already authenticated wireless session loses its TCP socket or Wi-Fi Direct group while the paired applications remain active
- **THEN** both applications SHALL automatically rebuild discovery, group formation, authenticated TCP, and DBP1 without a glasses tap or repeat pairing
- **AND** an active External Display or Screen Mirror source SHALL resume with a fresh live session while retaining its existing virtual display or MediaProjection authorization

#### Scenario: Automatic recovery attempt fails transiently
- **WHEN** Wi-Fi Direct or TCP setup reports a transient failure during automatic recovery
- **THEN** the application SHALL clean partial resources and retry with bounded backoff until the user explicitly stops, disconnects, forgets the peer, changes transport, or exits
