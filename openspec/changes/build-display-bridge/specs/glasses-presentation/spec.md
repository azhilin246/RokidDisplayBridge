## ADDED Requirements

### Requirement: Separate glasses receiver
The glasses APK SHALL use package `com.azhilin.rokid.displaybridge.glasses`, open only the bridge accessory identity, negotiate DBP1, and decode live video without persistent media storage.

#### Scenario: Valid bridge accessory connects
- **WHEN** accessory permission and bridge DBP1 handshake complete
- **THEN** the glasses SHALL advertise video capability and accept the new session bounds

#### Scenario: Active accessory cable detaches
- **WHEN** the active bridge accessory is detached or its transport reaches EOF
- **THEN** the glasses SHALL idempotently release decoder and transport resources, finish the bridge task, and return to the system HOME screen

### Requirement: Hardware TextureView presentation
The glasses SHALL use hardware AVC `MediaCodec` output to a 480x640 `TextureView`, schedule presentation timestamps, and SHALL NOT use `SurfaceView` or an audio decoder/output.

#### Scenario: First valid frame is rendered
- **WHEN** configuration and a decodable access unit arrive for the active session and generation
- **THEN** the decoder SHALL render it on the TextureView and report playing feedback

### Requirement: Bounded surface recovery
The glasses SHALL bound pending configuration/access units, release decoder resources on surface loss, and request a keyframe after TextureView recreation without creating a decoder from stale packets.

#### Scenario: TextureView is recreated
- **WHEN** the surface returns during an active session
- **THEN** the glasses SHALL reconfigure from the current bounded configuration and send keyframe feedback for the current session/generation

### Requirement: Explicit connection states
The glasses UI SHALL represent `NO_CABLE`, `CHARGE_ONLY_CABLE`, `ADB_OWNS_DATA_CABLE`, `WAITING_FOR_BRIDGE`, `CONNECTING`, `READY`, `LOADING`, `PLAYING`, and `ERROR` while keeping overlays from obscuring active video.

#### Scenario: State changes during playback
- **WHEN** handshake, decoder, detach, or error state changes
- **THEN** the appropriate state SHALL be visible without leaving a persistent opaque overlay over playing video

#### Scenario: USB data function is enabled without an accessory
- **WHEN** the cable reports connected/configured and the USB `adb` function is enabled but no active ADB ownership is independently proven
- **THEN** the glasses SHALL show `WAITING_FOR_BRIDGE` rather than `ADB_OWNS_DATA_CABLE`

### Requirement: Material focus-first glasses surface
The fixed 480x640 glasses UI SHALL use a pure-black Material surface with readable typography, large focus targets, and concise gesture guidance, while preserving green-optics contrast and the existing input-routing contract. The application label and main heading SHALL be `Display Bridge`.

#### Scenario: A non-playing state is shown
- **WHEN** the glasses are disconnected, connecting, ready, loading, or in error
- **THEN** the surface SHALL show `Display Bridge`, one non-duplicated state title, detail when it adds information, and a relevant gesture hint, without a DB icon, tinted/card background, duplicate badge, duplicate title, or protocol source-kind text

#### Scenario: Playback overlay opens
- **WHEN** the user double taps during playback
- **THEN** the translucent Material-shaped control surface SHALL preserve video visibility, focus play/pause first, distinguish the focused icon with a high-contrast state layer, and retain play/pause, backward, forward, Exit order

### Requirement: Playback wake policy
The glasses Activity SHALL keep the display awake while the decoder reports `PLAYING`, SHALL issue one bounded wake pulse as soon as the first complete video access unit of every External Display or Screen Mirror session is received, and SHALL release persistent keep-awake when playback stops, disconnects, or enters an error/main state.

#### Scenario: Playback state changes
- **WHEN** decoder state enters or leaves `PLAYING`
- **THEN** the root glasses view's keep-screen-on state SHALL change to match

#### Scenario: A live mode delivers its first frame
- **WHEN** the first complete access unit is received for a new External Display or Screen Mirror session
- **THEN** the glasses SHALL perform one full-display wake pulse before decoder recovery can delay presentation and SHALL NOT repeat it for surface recreation, configuration, feedback, or later frames in the same session

### Requirement: Icon-only playback overlay and coordinate-free input
The playing surface SHALL provide one translucent overlay whose visual and cyclic focus order is play/pause, seek backward, seek forward, and Exit, with no numeric seek interval. Every overlay opening SHALL reset focus to its first play/pause control.

#### Scenario: Overlay is hidden
- **WHEN** the user taps, swipes backward/forward, or double taps using glasses or ring input
- **THEN** the glasses SHALL apply the synchronized hidden-overlay gesture policy, including direction reversal and optional exit confirmation

#### Scenario: Overlay is visible
- **WHEN** the user taps, swipes backward/forward, or double taps using glasses or ring input
- **THEN** the glasses SHALL respectively activate the focused icon, change focused icon cyclically, or hide the overlay

#### Scenario: Exit is activated from the overlay
- **WHEN** the Exit control is activated for an active session
- **THEN** the glasses SHALL send `LIVE_STOP` with user reason, stop local decoding, hide the overlay, and return to `READY`
- **AND** the healthy bridge transport and glasses application task SHALL remain active

### Requirement: Configurable hidden-overlay gestures
While video is playing and the overlay is hidden, the glasses SHALL support swipe actions `NONE`, `SEEK`, `PLAYLIST`, and `FAVORITES`; tap actions `NONE`, `PLAY_PAUSE`, and `SHOW_OVERLAY`; and double-tap actions `SHOW_OVERLAY`, `MAIN_MENU`, and `EXIT_APP`. The visible overlay SHALL retain its fixed navigation gestures.

#### Scenario: Swipe action is selected
- **WHEN** a hidden-overlay previous or next swipe is accepted
- **THEN** direction reversal SHALL first swap previous and next when enabled, after which the glasses SHALL perform no action or send the session-scoped seek, playlist, or favorite command selected by the policy

#### Scenario: Firmware emits a continuous swipe stream
- **WHEN** one physical swipe produces repeated direction keys or paired firmware aliases without a bounded quiet gap
- **THEN** the glasses SHALL emit at most one logical action and every repeated event SHALL extend the latch until the stream becomes quiet

#### Scenario: Double-tap exit confirmation is enabled
- **WHEN** hidden-overlay double tap selects `MAIN_MENU` or `EXIT_APP`
- **THEN** the first double tap SHALL show a bounded confirmation prompt and a second double tap within the confirmation window SHALL perform the selected action

#### Scenario: Main menu is selected
- **WHEN** the confirmed or immediate `MAIN_MENU` action runs for an active session
- **THEN** the glasses SHALL stop only that live mode and return to `READY` while retaining the application and healthy DBP1 transport

### Requirement: Guarded main-screen exit
The glasses SHALL scope its dedicated main-screen application exit to non-playing states and, when second-double-tap confirmation is enabled, require two double taps within a three-second window using a dedicated prompt that cannot be overwritten by connection or decoder status.

#### Scenario: First main-screen double tap
- **WHEN** the user double taps while the glasses are not in `PLAYING`
- **THEN** the application SHALL remain open and show a dedicated instruction to double tap again to exit when confirmation is enabled, or exit immediately when it is disabled

#### Scenario: Confirmation completes or expires
- **WHEN** a second main-screen double tap arrives before timeout
- **THEN** the glasses SHALL call `finishAndRemoveTask()`
- **AND WHEN** the timeout or a lifecycle/surface transition occurs first
- **THEN** the pending exit SHALL be disarmed and its prompt hidden

#### Scenario: Playback double tap
- **WHEN** the user double taps while video is `PLAYING`
- **THEN** only the playback overlay SHALL show or hide and application exit SHALL remain disarmed

### Requirement: Fixed-aspect video placement
The glasses SHALL render video in a horizontally and vertically movable, uniformly resizable rectangle whose aspect ratio is always 480:640. Vertical placement MAY extend beyond the viewport but SHALL leave at least 10% of the frame visible; horizontal placement SHALL keep the complete frame inside the viewport, and status and playback overlays SHALL remain anchored to the full display.

#### Scenario: Layout is applied during playback
- **WHEN** an acknowledged layout changes scale or Y placement
- **THEN** the `TextureView` SHALL update without interpreting the encoded image's internal aspect ratio or exposing fit/zoom/stretch modes

#### Scenario: Content is moved beyond an edge
- **WHEN** an acknowledged Y offset places part of the 3:4 rectangle outside the 480x640 viewport
- **THEN** the glasses SHALL preserve the vertical position while preventing more than 90% of the frame from leaving vertically and SHALL clamp horizontal position before either edge leaves the viewport

### Requirement: Decoder diagnostics return to phone
The glasses SHALL send bounded textual diagnostics and feedback including queue depth, decoded and dropped counts, last presented timestamp, and keyframe requests.

#### Scenario: Decoder loses reference continuity
- **WHEN** fragment loss, surface recreation, or codec recovery requires a sync frame
- **THEN** the glasses SHALL request a keyframe and report diagnostics without terminating a healthy current session
