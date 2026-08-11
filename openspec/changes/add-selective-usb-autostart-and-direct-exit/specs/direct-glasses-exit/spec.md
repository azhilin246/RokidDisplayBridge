## ADDED Requirements

### Requirement: Playback exits close the glasses application
Every glasses action that previously returned from playback to the Display Bridge main screen SHALL instead stop the active bridge session when present and immediately finish the glasses application task without rendering the intermediate `READY` screen.

#### Scenario: Playback overlay Exit is activated
- **WHEN** the user activates the Exit icon in the playback overlay
- **THEN** the glasses SHALL enqueue one `LIVE_STOP(USER)`, perform idempotent local playback cleanup, and call `finishAndRemoveTask()` without showing the Display Bridge main screen

#### Scenario: Legacy main-menu gesture is activated
- **WHEN** synchronized or persisted gesture settings select the legacy `MAIN_MENU` double-tap action
- **THEN** the glasses SHALL apply the same Exit Application behavior, including configured second-double-tap confirmation

#### Scenario: No live session is active
- **WHEN** an exit action is confirmed while no live session is active
- **THEN** the glasses SHALL finish the application task without sending an invalid zero-session stop

### Requirement: Phone gesture settings expose one exit choice
The companion gesture settings SHALL present a single Exit Display Bridge double-tap choice and SHALL normalize a persisted legacy `MAIN_MENU` selection to `EXIT_APP` without changing DBP1 enum ordinals.

#### Scenario: Legacy settings are loaded
- **WHEN** the stored double-tap action is `MAIN_MENU`
- **THEN** the UI and synchronized policy SHALL expose `EXIT_APP` behavior and SHALL NOT display a duplicate return-to-main-screen option
