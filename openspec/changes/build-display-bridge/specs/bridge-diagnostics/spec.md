## ADDED Requirements

### Requirement: Actionable companion status
The companion UI SHALL separately show glasses connection, active AOA identity, handshake, encoder, current mode, actionable error, diagnostics, thermal status, frame rate, bitrate, sent frames, dropped frames, queue depth, and current public Android audio output route when available.

#### Scenario: Bridge is idle or active
- **WHEN** transport, encoder, source, thermal, route, or counters change
- **THEN** the UI SHALL update from service-owned state without rendering a live frame preview

### Requirement: Separate user actions
The companion SHALL expose Connect/Disconnect, Start External Display, Start Screen Mirror, and Frame Placement as distinct actions and SHALL NOT expose a duplicate Stop action, content selection, player launch/settings, unrelated player-specific controls.

#### Scenario: User changes modes
- **WHEN** a main action is selected
- **THEN** the current and requested modes SHALL remain visually distinct and only one mode SHALL be active

### Requirement: Material You companion hierarchy
The companion SHALL use a Material 3 dynamic-color interface on supported Android versions, with a deterministic fallback palette, rounded tonal surfaces, accessible touch targets, correct status/navigation-bar insets, and a hierarchy that separates connection health, mode actions, Frame Placement navigation, errors, and expandable technical details containing live metrics.

#### Scenario: Companion opens in any bridge state
- **WHEN** the main screen renders on the phone
- **THEN** connection SHALL be readable before technical details, External Display and Screen Mirror SHALL remain distinct actions whose selected styling communicates the current mode without a duplicate mode heading, the connection action SHALL become a red Disconnect when connected, and every required diagnostic field SHALL remain available

#### Scenario: Bridge reports an actionable error
- **WHEN** `BridgeSnapshot.error` is non-null
- **THEN** the error SHALL appear in a dedicated semantic error surface while ordinary diagnostics remain available separately

### Requirement: Dedicated frame placement settings
The companion SHALL expose a dedicated persisted 480x640 frame-placement page with a 3:4 frame, aspect-locked resize, horizontal drag constrained fully inside the viewport, vertical drag allowing at most 90% off-screen placement, Reset, diagonal hatching for clipped portions, and no Fit, Zoom, Stretch, or content-aspect settings. The viewport SHALL be inset within a larger gesture area so reset resize handles remain reachable.

#### Scenario: User edits frame placement
- **WHEN** the frame is dragged, resized, or reset
- **THEN** the companion SHALL retain bounded X, scale, and Y geometry, submit a complete display-layout request, and report the matching acknowledgement

### Requirement: Opt-in low-rate placement preview
The frame-placement page SHALL provide a `Preview Content` toggle that is off after every cold process start and, while enabled in an active session, displays bounded low-rate latest-only actual video without blocking or changing the live transport.

#### Scenario: Preview lifecycle changes
- **WHEN** preview is disabled, the page closes, the session stops, or the process cold-starts
- **THEN** no preview frame SHALL be retained or produced and all preview resources SHALL be released

### Requirement: Named frame favorites
The frame-placement page SHALL let the user apply or delete a favorite, reorder favorites by direct drag-and-drop in a fully expanded list without nested scrolling, and create one from a final `Add` action whose Material modal accepts a non-blank name and precise bounded X/Y values initialized from the current frame. Order and geometry SHALL persist in bridge-owned storage.

#### Scenario: Favorite is applied or reordered
- **WHEN** the user applies or reorders a favorite
- **THEN** applying SHALL clamp X/Y to the shared layout bounds, use the normal acknowledged layout path, and drag-and-drop ordering SHALL remain stable after reopening the page

#### Scenario: Favorite list grows
- **WHEN** favorites are added
- **THEN** every row SHALL remain fully measured, the list SHALL expand the outer page, and no nested favorites scrollbar SHALL appear

#### Scenario: Favorite is added precisely
- **WHEN** the user activates the final `Add` row and submits a valid name, X, and Y
- **THEN** the favorite SHALL be appended, enabled for gesture cycling by default, and created/applied with the bounded numeric layout

### Requirement: Media-control diagnostics
The companion SHALL report the last accepted or stale-rejected glasses media or favorite command and the Android media key or frame favorite applied, without inspecting or changing the target application's settings.

#### Scenario: Glasses activates a playback control
- **WHEN** an active-session media command reaches the phone
- **THEN** diagnostics SHALL identify the command and key code while Android remains responsible for active media-session routing

### Requirement: Phone-owned glasses gesture settings
The companion SHALL provide a dedicated Material gesture-settings page that presents tap and double-tap controls first and a final plain `Swipes` section containing swipe action, direction reversal, and the enabled subset of current frame-placement favorites without an enclosing card around that section.

#### Scenario: Gesture settings are saved
- **WHEN** the user changes a gesture option or favorite selection
- **THEN** the companion SHALL persist the complete policy and synchronize a bounded configuration to the connected glasses

#### Scenario: Favorites change
- **WHEN** a favorite is created, deleted, or reordered
- **THEN** a new favorite SHALL be enabled by default, deleted favorites SHALL disappear from gesture selection, and gesture cycling SHALL follow the current persisted favorite order

### Requirement: Diagnostic evidence channel
The system SHALL emit stable package-specific log and diagnostic tags for display discovery, encoder output, USB send, decoder feedback, detach, projection stop, and cleanup.

#### Scenario: External-display proof fails
- **WHEN** no real frames from an independent capable application reach the glasses
- **THEN** logs and dumpsys evidence SHALL identify the last proven stage rather than reporting generic failure

#### Scenario: USB permission is needed before and after AOA re-enumeration
- **WHEN** Android reports no permission for a normal USB device or its re-enumerated AOA device
- **THEN** diagnostics SHALL name the corresponding stage and SHALL NOT issue duplicate simultaneous requests for the same enumerated device

### Requirement: Real-device acceptance remains evidence-gated
OpenSpec device tasks SHALL remain incomplete until exact-serial package, accessory, external-display, mirror, switch, detach, and sustained-operation evidence exists, including the user's visual confirmation where ADB cannot observe the glasses image.

#### Scenario: Build passes without glasses hardware
- **WHEN** unit, lint, assembly, and strict validation pass but the glasses serial is unavailable
- **THEN** automatic tasks MAY complete but hardware acceptance tasks SHALL remain unchecked with the blocker recorded
