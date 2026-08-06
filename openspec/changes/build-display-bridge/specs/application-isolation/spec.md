## ADDED Requirements

### Requirement: Independent Android application identities
The system SHALL provide companion package `com.azhilin.rokid.displaybridge.companion` and glasses package `com.azhilin.rokid.displaybridge.glasses`, and SHALL NOT modify or replace unrelated installed applications.

#### Scenario: All applications are installed
- **WHEN** both bridge APKs are installed beside unrelated phone and glasses applications
- **THEN** package manager SHALL report distinct package names and each SHALL retain its own application data and declarations

### Requirement: Unique Android runtime namespaces
The bridge SHALL use the application label `Display Bridge` for both new APKs and unique icons, foreground service, notification channel and id, preferences, intent and broadcast actions, FileProvider authority if present, permissions, diagnostics, log tags, and protocol state.

#### Scenario: Runtime identifiers are audited
- **WHEN** packaged manifests, resources, and constants are inspected
- **THEN** every bridge authority, channel, action, preference, diagnostic, and protocol namespace SHALL be owned by the Display Bridge packages

### Requirement: Device-appropriate coordinated launcher icons
The companion launcher icon SHALL show the Display Bridge video-to-glasses transfer mark on a solid black background using pale mint-white outlines and green transfer pixels. The glasses launcher icon SHALL show the same mark using only green shades on a transparent background. The transfer pixels SHALL grow toward the video display and shrink toward the glasses, and both glasses lenses SHALL have equal visible width.

#### Scenario: Launcher assets are packaged
- **WHEN** the companion and glasses APK resources and manifests are inspected
- **THEN** each APK SHALL expose its assigned legacy launcher artwork plus adaptive, round, and monochrome variants where supported, without sharing the old placeholder icon

### Requirement: Unique Android Open Accessory identity
The companion SHALL publish manufacturer `Azhilin`, model `Rokid Display Bridge`, description `Universal external display and screen mirror bridge for Rokid Glasses`, version `1.0`, URI `https://azhilin.com/rokid-display-bridge`, and serial `rokid-display-bridge-v1`; the glasses filter SHALL match only this bridge identity.

#### Scenario: Bridge accessory is attached
- **WHEN** the phone enters AOA mode with the bridge identity
- **THEN** Android SHALL target only the bridge glasses application and SHALL NOT launch the WiredPlayer glasses application or show an ambiguous chooser

#### Scenario: Bridge accessory attaches without glasses ADB
- **WHEN** Android recognizes the unique bridge accessory and glasses debugging is unavailable
- **THEN** the system `USB_ACCESSORY_ATTACHED` dispatch SHALL open the bridge glasses Activity without any ADB command

#### Scenario: Unrelated accessory is attached
- **WHEN** the phone enters AOA mode with a different accessory identity
- **THEN** Android SHALL NOT launch the bridge glasses application
