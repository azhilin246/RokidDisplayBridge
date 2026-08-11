## ADDED Requirements

### Requirement: User-controlled glasses attach launch
The companion SHALL expose a persisted `Open Display Bridge when glasses connect` setting, enabled by default, that controls a dedicated USB attach entry point without disabling the normal launcher Activity or foreground service.

#### Scenario: Setting is enabled
- **WHEN** the user enables the setting and the supported glasses attach in their verified normal USB mode
- **THEN** Android SHALL open the companion Activity and the existing companion-visible connection flow SHALL begin

#### Scenario: Setting is disabled
- **WHEN** the user disables the setting and later attaches the supported glasses
- **THEN** the companion SHALL remain closed until the user launches it manually

#### Scenario: Application restarts
- **WHEN** the companion process or phone restarts after the setting changes
- **THEN** the persisted setting and attach component state SHALL retain the user's choice

### Requirement: Strict target-device filtering
The attach entry point SHALL match only the verified RG_glasses normal-mode VID/PID and SHALL revalidate Rokid/RG_glasses descriptor text before opening the companion. It MUST fail closed for missing device data, generic AOA identities, and unrelated USB devices.

#### Scenario: Unrelated USB device attaches
- **WHEN** storage, an adapter, another Android device, or any USB identity other than the verified glasses identity attaches
- **THEN** Display Bridge SHALL NOT open and SHALL NOT request USB permission

#### Scenario: Matching intent lacks a valid target
- **WHEN** the attach dispatcher receives an explicit or malformed intent without a strictly matching `UsbDevice`
- **THEN** it SHALL finish without opening `MainActivity` or starting `BridgeService`
