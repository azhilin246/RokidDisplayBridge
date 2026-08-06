## ADDED Requirements

### Requirement: Arbitrary bounded DBP1 stream chunking
The DBP1 receiver SHALL parse valid frames independently of USB read/write boundaries, including one frame split across many chunks and multiple maximum-sized frames coalesced across chunks, while retaining no more than one bounded incomplete frame.

#### Scenario: Maximum frames cross read boundaries
- **WHEN** two valid maximum-payload frames arrive in chunks that end inside the first frame and continue into the second
- **THEN** both frames SHALL be emitted in order without an oversized-buffer error or unbounded allocation

### Requirement: Keyframe-safe sender pressure
The companion SHALL bound pending video to at most three complete access units and SHALL NOT continue a predictive chain after dropping one of its reference units.

#### Scenario: Sender queue reaches its bound
- **WHEN** a non-key access unit arrives while the bounded sender queue is full
- **THEN** queued predictive video SHALL be discarded atomically, subsequent non-key units SHALL be rejected, and one encoder keyframe request SHALL be raised

#### Scenario: Recovery keyframe arrives
- **WHEN** a keyframe arrives while sender pressure recovery is active
- **THEN** obsolete queued video SHALL be removed, the complete keyframe SHALL be accepted, and normal bounded queueing SHALL resume

### Requirement: Bounded decoder ingress
The glasses SHALL reassemble fragments within existing bounds before submitting complete access units to MediaCodec, and the count of accepted decoder work items SHALL remain bounded.

#### Scenario: Decoder cannot keep pace
- **WHEN** all decoder ingress slots are occupied
- **THEN** the glasses SHALL reject additional predictive units, request a keyframe once, and SHALL NOT grow an executor queue or retain unbounded encoded bytes

#### Scenario: Old work executes after replacement
- **WHEN** a queued work item belongs to a stopped session or superseded generation
- **THEN** it SHALL release its ingress slot and exit before decoder mutation

### Requirement: Terminal decoder cleanup
An unexpected decoder exception SHALL terminate only the active live session through idempotent cleanup, release codec and queued state, report an actionable error, and retain a healthy DBP1 transport for a later session.

#### Scenario: MediaCodec throws during active playback
- **WHEN** input, output, configure, or release processing reports the first unexpected codec failure
- **THEN** the glasses SHALL stop accepting that session, clear bounded decoder state, release the codec, send `LIVE_STOP(ERROR)`, and publish one terminal decoder error

### Requirement: Minimal stable presentation buffering
The bridge SHALL minimize live delay with at most three sender access units, no fixed 150 ms glasses startup buffer, no B-frames, and immediate TextureView rendering of output that is not stale by more than two nominal frame intervals.

#### Scenario: Healthy realtime stream is decoded
- **WHEN** complete access units arrive within the bounded live budget
- **THEN** decoded output SHALL be released for immediate presentation without adding a fixed multi-frame clock delay

#### Scenario: Output is stale after a stall
- **WHEN** decoded output is more than two nominal frame intervals behind the newest complete presentation timestamp
- **THEN** it SHALL be decoded for reference continuity but released without rendering, and the drop SHALL be reported in feedback

### Requirement: Hardware realtime codec configuration
The companion SHALL keep the hardware Surface AVC encoder at 480x640 and at most 30 fps, request realtime priority and zero-frame encoder latency, cap Surface input to 30 fps, retain zero B-frames, prefer advertised CBR, and use a 1.5 Mbit/s bounded bitrate. The glasses SHALL enable standard low-latency decoding only when the selected hardware codec advertises it.

#### Scenario: Optional realtime hint is unsupported
- **WHEN** a selected hardware codec ignores or does not advertise an optional latency feature
- **THEN** the bridge SHALL continue with bounded queues on that hardware codec and SHALL NOT fall back to software or terminate solely because the hint is unavailable

### Requirement: Transition-only playback state and recovery diagnostics
The glasses SHALL publish playback state only when it changes and SHALL report bounded ingress depth, pressure drops, stale presentation drops, codec identity, effective latency hints, and keyframe recovery requests without per-frame UI state churn.

#### Scenario: Sustained playback remains in PLAYING
- **WHEN** successive frames render without a state transition
- **THEN** the glasses SHALL update counters as scheduled but SHALL NOT enqueue a new main-thread `PLAYING` render for every frame
