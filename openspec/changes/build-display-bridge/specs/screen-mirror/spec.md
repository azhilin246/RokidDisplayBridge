## ADDED Requirements

### Requirement: Fresh explicit projection consent
Screen Mirror SHALL request a fresh successful MediaProjection consent result for every session and SHALL NOT persist or reuse consent data.

#### Scenario: User starts Screen Mirror
- **WHEN** the user activates the separate Screen Mirror action
- **THEN** the Android system consent UI SHALL open and no capture resource SHALL start before approval
- **AND** on API 34+ the request SHALL use `MediaProjectionConfig.createConfigForDefaultDisplay()` so only whole-default-display capture is offered when the platform honors that configuration

#### Scenario: Consent is denied
- **WHEN** the user cancels or denies consent
- **THEN** the bridge SHALL remain connected in IDLE without creating an encoder session or display

### Requirement: Foreground projection ownership
The companion service SHALL declare and run with foreground service types `connectedDevice|mediaProjection` while projection is active and SHALL own the projection, display, encoder, queue, and stop lifecycle.

#### Scenario: Projection stops
- **WHEN** `MediaProjection.Callback.onStop`, USB detach, service destruction, notification stop, or user Stop occurs
- **THEN** all projection and live resources SHALL be released idempotently and the mode SHALL leave SCREEN_MIRROR

#### Scenario: Phone is locked on a platform that auto-stops projection
- **WHEN** Android terminates MediaProjection during lock and invokes `onStop()`
- **THEN** the projection and live session SHALL end cleanly while the AOA connection remains available
- **AND** after unlock the companion SHALL require a new explicit Screen Mirror action and fresh consent rather than reusing the stopped token

### Requirement: Bounded video-only screen capture
Screen Mirror SHALL render the consented source to the shared encoder surface at no more than 480x640, 30 fps, and bounded bitrate with periodic keyframes and generation replacement on reconfiguration.

#### Scenario: Decoder requests recovery
- **WHEN** feedback requests a keyframe or reports missing reference data
- **THEN** the companion SHALL request an encoder sync frame without requesting new projection consent

### Requirement: Platform capture limitations are preserved
The companion UI SHALL explain that secure, protected, and DRM content can be black and SHALL NOT attempt to bypass platform capture policy.

#### Scenario: Protected content is omitted
- **WHEN** Android supplies black or omitted pixels for protected content
- **THEN** the bridge SHALL transmit the platform result and keep the limitation visible in diagnostics

### Requirement: Audio stays on the phone
Screen Mirror MUST NOT use AudioPlaybackCapture, an AAC encoder, audio protocol samples, glasses `AudioTrack`, route switching, Bluetooth control, or hidden volume APIs.

#### Scenario: Phone plays audio during mirroring
- **WHEN** an application emits media audio while Screen Mirror is active
- **THEN** Android SHALL retain normal phone/Bluetooth routing and the bridge SHALL transmit video only
