## ADDED Requirements

### Requirement: Video-only live protocol
The bridge SHALL negotiate one bounded DBP1 `LIVE_*` video capability with source kinds `PRESENTATION_DISPLAY` and `SCREEN_CAPTURE`, and MUST NOT define or send audio configuration or samples.

#### Scenario: Either source starts
- **WHEN** a source obtains the exclusive producer slot
- **THEN** it SHALL use the same start, ready, video config, video sample, feedback, discontinuity, stop, error, and diagnostic contract

### Requirement: Hardware Surface encoder
The companion SHALL use a hardware-accelerated AVC encoder with a Surface input, output no more than 480x640 at no more than 30 fps, use bounded bitrate and periodic keyframes, and SHALL NOT use software or CPU pixel-copy fallback.

#### Scenario: Encoder starts
- **WHEN** a compatible hardware encoder is configured
- **THEN** the producer SHALL send effective video configuration before samples and expose codec, dimensions, bitrate, frame rate, and profile/level diagnostics

#### Scenario: Hardware encoder is unavailable
- **WHEN** no hardware Surface-input AVC encoder starts successfully
- **THEN** the bridge SHALL remain connected and report an actionable unsupported-codec error without starting a live session

### Requirement: Bounded fragmentation and queueing
The protocol SHALL bound frame payload, access-unit bytes, fragment count, pending reassemblies, reassembly deadline, control depth, and video depth; control SHALL take priority over video and obsolete non-key video SHALL be dropped under pressure.

#### Scenario: Large access unit is sent
- **WHEN** one encoded access unit exceeds the negotiated frame payload
- **THEN** it SHALL be fragmented in order and either reassembled within all bounds or rejected without unbounded allocation

#### Scenario: USB sender is backpressured
- **WHEN** pending video reaches its queue limit
- **THEN** the sender SHALL preserve control traffic, discard bounded obsolete video, update drop counters, and request or preserve recovery from a keyframe

### Requirement: Session and generation isolation
Every live configuration, sample, feedback, discontinuity, and stop SHALL be correlated to a nonzero session id and generation where applicable.

#### Scenario: Late packet arrives
- **WHEN** a packet belongs to an ended session or superseded generation
- **THEN** the receiver SHALL silently discard it before reassembly or decoder mutation and SHALL NOT report a terminal stale-session error

### Requirement: Idempotent exclusive lifecycle
The companion SHALL allow only `IDLE`, `EXTERNAL_DISPLAY`, or `SCREEN_MIRROR` and SHALL serialize producer stop, sample stop, `LIVE_STOP`, source release, encoder release, queue clear, identity replacement, and new-mode start.

#### Scenario: Mode is switched
- **WHEN** the user starts one mode while the other is active
- **THEN** the old producer and all resources SHALL be fully invalidated before the new session begins

### Requirement: Session-scoped phone media controls
The glasses SHALL send `PLAY_PAUSE`, `SEEK_BACKWARD`, `SEEK_FORWARD`, `PLAYLIST_PREVIOUS`, `PLAYLIST_NEXT`, `FAVORITE_PREVIOUS`, or `FAVORITE_NEXT` commands correlated to the current nonzero session and generation; the companion SHALL silently reject stale commands. Accepted playback and playlist commands SHALL dispatch paired Android media-key down/up events, while favorite commands SHALL resolve against the phone-owned enabled favorite order and use the acknowledged display-layout path.

#### Scenario: Glasses control arrives for the active stream
- **WHEN** a tap, swipe, or overlay activation produces a control for the current session and generation
- **THEN** the phone SHALL dispatch the corresponding play/pause, rewind, fast-forward, previous, or next Android media key without targeting a particular application or choosing a seek interval, or apply the requested enabled frame favorite

#### Scenario: Late glasses control arrives
- **WHEN** a media command belongs to an ended session or superseded generation
- **THEN** the phone SHALL discard it without affecting the current media session or reporting a terminal protocol error

### Requirement: Bounded gesture policy synchronization
DBP1 SHALL define request-correlated `GESTURE_SETTINGS` and acknowledgement messages containing only validated gesture enum values and bounded boolean flags.

#### Scenario: Handshake completes or settings change
- **WHEN** the glasses connection becomes ready or the phone saves gesture settings
- **THEN** the phone SHALL send the complete current policy and the glasses SHALL atomically apply and acknowledge it

#### Scenario: Gesture policy is malformed
- **WHEN** a payload has an unknown enum value, reserved flag, or invalid length
- **THEN** it SHALL be rejected without changing the last valid gesture policy

### Requirement: Acknowledged fixed-aspect display layout
The protocol SHALL retain a complete display layout containing 250-1000 permille uniform scale and normalized X/Y fields, plus a request-correlated acknowledgement of the effective values. X SHALL move a reduced frame between the left and right edges while keeping the complete rectangle inside the viewport. Y MAY place the rectangle beyond a viewport edge but SHALL keep at least 10% of its height visible.

#### Scenario: Display layout changes
- **WHEN** the companion submits a valid layout
- **THEN** the glasses SHALL apply a 3:4 video rectangle whose horizontal edges remain inside the viewport, with the shared vertical 90%-overscan bound, and acknowledge the effective X/Y layout without depending on encoded content dimensions
