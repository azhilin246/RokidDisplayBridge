## ADDED Requirements

### Requirement: Generic public presentation VirtualDisplay
External Display SHALL create a 480x640 `VirtualDisplay` with `PUBLIC`, `PRESENTATION`, and `OWN_CONTENT_ONLY`, without `AUTO_MIRROR`, `SECURE`, or MediaProjection.

#### Scenario: Display is created
- **WHEN** External Display starts with a ready encoder surface
- **THEN** the display SHALL appear in `getDisplays(DISPLAY_CATEGORY_PRESENTATION)` and diagnostics SHALL record display id, flags, mode, size, density, refresh rate, and cross-process availability evidence

### Requirement: Application-neutral bridge behavior
The companion SHALL NOT select content, launch or configure a third-party application, store player settings, depend on a player package, or advertise a specific application in its name, settings, AOA identity, or actions.

#### Scenario: External Display becomes ready
- **WHEN** the public presentation display is active
- **THEN** the user MAY open any application and an application that supports external presentation displays MAY render there without bridge-side integration

#### Scenario: Display is ready but video has not arrived
- **WHEN** External Display handshake and VirtualDisplay creation are complete but no decodable access unit has arrived
- **THEN** the glasses SHALL show `WAITING FOR VIDEO` and SHALL NOT expose `PRESENTATION_DISPLAY` or call this state `LOADING`

### Requirement: Open-companion cable default
While the companion Activity is open, the bridge SHALL automatically discover an already attached or newly attached Rokid USB device, request only the USB permissions Android still requires, complete AOA and DBP1 handshake, and start External Display when the exclusive mode remains `IDLE`.

#### Scenario: Cable connects while companion is open
- **WHEN** the USB/AOA connection reaches DBP1 ready state and no mode was selected manually
- **THEN** External Display SHALL start without requiring Connect or Start External Display button presses

#### Scenario: User selects Screen Mirror
- **WHEN** the user explicitly completes fresh Screen Mirror consent before or after automatic External Display startup
- **THEN** the automatic default SHALL NOT override Screen Mirror and the normal exclusive-mode replacement lifecycle SHALL apply

### Requirement: Real independent-application proof
External Display SHALL be considered hardware-proven only when an independently installed capable application selects the display, produces non-empty encoder frames, video crosses AOA and is rendered on the glasses, its controls remain on the phone, and audio remains phone-owned.

#### Scenario: Only the display exists
- **WHEN** the VirtualDisplay is discoverable but no independent application renders onto it
- **THEN** the display infrastructure SHALL remain available but the real-frame acceptance task SHALL remain incomplete

#### Scenario: A capable application renders
- **WHEN** encoder, transport, decoder feedback, and the user's glasses observation all confirm real video
- **THEN** the bridge SHALL record generic External Display as hardware-proven without adding application-specific behavior

### Requirement: Evidence-based application limitation
If an application does not use the display, diagnostics SHALL determine whether the display was published and visible while treating display selection as that application's capability.

#### Scenario: Application lacks external-display support
- **WHEN** evidence shows the display is public and discoverable but the application does not select it
- **THEN** the bridge SHALL report that boundary without emulating another route, embedding a player, or changing application settings
