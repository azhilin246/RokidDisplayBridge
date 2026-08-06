## 1. Project and isolation

- [x] 1.1 Scaffold independent Gradle modules for protocol, companion, and glasses with the two required application IDs
- [x] 1.2 Replace product labels, icons, actions, channel/notification identifiers, preferences, authorities, tags, and diagnostics with application-neutral Display Bridge names
- [x] 1.3 Implement and test generic bridge AOA identity plus matching glasses filter, distinct from other accessory applications and the superseded player-specific identity
- [x] 1.4 Document the isolation audit and verify no files outside the standalone project changed

## 2. DBP1 video protocol

- [x] 2.1 Rename DBP1 presentation source kind to `PRESENTATION_DISPLAY` while retaining one video-only lifecycle
- [x] 2.2 Implement bounded H.264 fragmentation/reassembly with strict metadata, byte, count, order, pending-unit, and deadline checks
- [x] 2.3 Implement bounded control/video sender queue with obsolete non-key video dropping
- [x] 2.4 Update protocol tests and specification for application-neutral source semantics
- [x] 2.5 Add bounded `MEDIA_KEY` payloads with active session/generation filtering and protocol round-trip/stale tests
- [x] 2.6 Add request-correlated fixed-aspect `DISPLAY_LAYOUT` / acknowledgement payloads and bounds tests
- [x] 2.7 Define and test Y offset as full-viewport center displacement while retaining the existing bounded wire field

## 3. AOA companion and glasses transport

- [x] 3.1 Implement deterministic phone-side USB discovery, permission, AOA negotiation, bulk IO, detach, and asynchronous writes
- [x] 3.2 Implement glasses accessory open/permission, bulk IO, handshake, detach, and bounded diagnostic sending
- [x] 3.3 Add transport/unit tests for selection, partial bulk writes, handshake state, detach, and cleanup
- [x] 3.4 Coalesce phone USB permission requests per enumeration stage, label normal/AOA stages, and automatically connect while the companion is open

## 4. Glasses presentation

- [x] 4.1 Remove player-specific glasses text and fix real-device TextureView startup compatibility
- [x] 4.2 Implement hardware AVC decoder, bounded pending config/access units, scheduled presentation, feedback, and keyframe request
- [x] 4.3 Implement TextureView lifecycle recovery and idempotent decoder/session cleanup
- [x] 4.4 Update glasses tests for neutral identity/text and the real TextureView regression
- [x] 4.5 Keep the glasses screen awake exactly while decoder state is `PLAYING`
- [x] 4.6 Port the four-control playback overlay in play/pause, seek-backward, seek-forward, Exit order; reset focus to play/pause on open; retain tap/swipe/double-tap and ring routing with alias deduplication; make Exit stop only the active bridge mode
- [x] 4.7 Apply acknowledged 3:4 scale/placement to the TextureView while leaving overlays full-screen
- [x] 4.8 Stop inferring `ADB_OWNS_DATA_CABLE` from the enabled USB ADB function or missing accessory permission
- [x] 4.9 Add guarded two-double-tap exit with a dedicated three-second confirmation prompt only on non-playing main states
- [x] 4.10 Establish movable fixed-aspect editor/glasses geometry and Reset as the baseline later refined by the dedicated placement contract
- [ ] 4.11 Verify system accessory dispatch opens the glasses app without ADB and make active cable detach exit idempotently to HOME

## 5. Shared encoder and companion state

- [x] 5.1 Implement hardware Surface-input AVC probe and shared `SurfaceToAvcStream` with bounded 480x640/30 fps/bitrate/keyframes
- [x] 5.2 Rename exclusive mode state to `IDLE`, `EXTERNAL_DISPLAY`, and `SCREEN_MIRROR`
- [x] 5.3 Implement LIVE start/ready/config/sample/feedback/stop flow, counters, keyframe feedback, detach cleanup, and thermal handling
- [x] 5.4 Remove content selection and third-party launch behavior; expose only generic External Display and Screen Mirror actions/diagnostics
- [x] 5.5 Update companion tests for neutral identity/actions, mode exclusion, cleanup, stale feedback, display flags, and counters
- [x] 5.6 Dispatch active-session glasses commands as Android media keys and expose command diagnostics
- [x] 5.7 Add persisted 480x640 frame drag/resize/Reset settings without fit/zoom/stretch modes as the baseline for the dedicated placement workflow

## 6. Generic External Display

- [x] 6.1 Remove all player package queries, URI persistence/grants, launcher code, and player-specific strings from the product
- [x] 6.2 Publish the 480x640 public presentation VirtualDisplay with exact flags and neutral display diagnostics
- [x] 6.3 Keep the display active for any independently launched capable application without bridge-side configuration or launch
- [x] 6.3a Start External Display automatically after an open-companion USB/AOA/DBP1 connection while preserving explicit Screen Mirror switching
- [ ] 6.4 Install both updated APKs and verify all four packages coexist without accessory identity ambiguity
- [ ] 6.5 Prove on Pixel/RG_glasses that an independent capable application selects the display, non-empty frames traverse AOA, video is visible, controls remain on phone, and audio remains phone-owned
- [ ] 6.6 If an application does not use the display, record display visibility and the exact application-capability boundary without changing the bridge into a player or route emulator

## 7. Screen Mirror

- [x] 7.1 Implement a fresh Activity-owned MediaProjection consent launch for every requested session
- [x] 7.2 Implement service ownership with `connectedDevice|mediaProjection`, callback registration, 480x640 encoder display, and video-only behavior
- [x] 7.3 Implement projection stop, service stop, user stop, detach, and mode replacement through the shared idempotent cleanup path
- [x] 7.4 Show the secure/DRM black-content limitation and current Android audio route without capture or route mutation
- [ ] 7.5 Prove on Pixel/RG_glasses that manual consent is required, mirror video is visible, stop ends capture, and audio remains on phone/Bluetooth

## 8. Verification and acceptance

- [x] 8.1 Pass updated protocol, companion, and glasses unit tests including fragmentation, stale-session, generation, bounded-queue, identity, and TextureView regression suites
- [x] 8.2 Pass both lintDebug and assembleDebug tasks, strict OpenSpec validation, and `git diff --check`
- [x] 8.2a Re-run all automatic verification after playback controls and frame-placement changes
- [ ] 8.3 Verify External Display to Screen Mirror and reverse switching without stale decoder resurrection
- [ ] 8.4 Verify USB detach cleanup and reconnect with other accessory packages still installed
- [ ] 8.5 Complete a sustained run with bounded queue depth and no terminal overflow, and record counters/results
- [ ] 8.6 Record exact APK paths, package/version/identity evidence, device serials/builds, and all automatic/manual acceptance results
- [ ] 8.7 Verify on real glasses that the display stays awake, physical glasses/ring input routes correctly, the overlay focus/actions work, and frame placement applies live

## 9. Material You interface

- [x] 9.1 Add a Material 3 dependency and dynamic-color companion theme with a deterministic fallback palette
- [x] 9.2 Replace the companion text dump and button stack with connection, mode, metrics, frame-placement, error, and technical-detail Material surfaces while preserving every action and diagnostic field
- [x] 9.3 Replace the glasses plain state screen and legacy playback container with a fixed-viewport dark Material focus-first surface while preserving TextureView layering and all gesture/input behavior
- [x] 9.4 Add companion and glasses UI contract tests for hierarchy, dynamic/fallback theming, semantic error state, control order, initial focus, and non-obstructive playback layering
- [x] 9.5 Pass unit tests, both lintDebug tasks, both assembleDebug tasks, strict OpenSpec validation, and install both refreshed APKs on their exact target devices
- [ ] 9.6 Verify the refreshed companion and glasses surfaces on real devices without changing bridge mode, audio, accessory, or transport behavior

## 10. Material interface refinement and placement workflow

- [x] 10.1 Refine the glasses UI to pure black, label it `Display Bridge`, remove the DB mark and duplicated state badge/title, and show `WAITING FOR VIDEO` without `PRESENTATION_DISPLAY` after External Display is ready
- [x] 10.2 Refine the companion dashboard with system-bar insets, no brand mark/subtitle or selected-mode chip, Connect/Disconnect in the health card, no duplicate Stop, and live performance inside Technical Details
- [x] 10.3 Move Frame Placement to a dedicated page, inset the viewport within its gesture area, bound vertical placement to 90% off-screen, and hatch clipped regions while preserving live acknowledged editing and Reset
- [x] 10.4 Add persisted named frame favorites with apply, delete, and deterministic reordering
- [x] 10.5 Add an opt-in, cold-start-off, low-rate latest-only actual-content preview with bounded asynchronous cleanup and no live-transport backpressure
- [x] 10.6 Prefer whole-default-display MediaProjection consent on API 34+ and retain the AOA connection while platform-mandated lock termination cleanly ends projection and requires fresh consent
- [x] 10.7 Add/update unit and UI contract tests for refined hierarchy, state wording, insets, connection action, frame bounds/hatching, favorites, preview lifecycle, projection request, and lock-stop handling
- [x] 10.8 Pass protocol/unit/lint/assemble, strict OpenSpec validation, and `git diff --check`, then install both refreshed APKs on the exact Pixel and RG_glasses serials
- [ ] 10.9 Verify both refined interfaces, frame workflow, preview, whole-screen consent, and lock/unlock behavior on real devices without regressing External Display, playback input, audio ownership, or accessory isolation
- [x] 10.10 Establish centered-only frame placement as the baseline later superseded by horizontally movable in-viewport geometry
- [x] 10.11 Add regression tests for ignored horizontal drag, centered resize, legacy nonzero-X normalization, favorites normalization, and centered glasses geometry; then pass protocol/unit/lint/assemble, strict OpenSpec validation, and `git diff --check`
- [x] 10.12 Remove the LIVE_START/TextureView startup deadlock, make LIVE_READY one-shot per session across surface recreation, and add regression tests
- [ ] 10.13 Re-run automatic verification, install the repaired glasses APK, and prove both source VirtualDisplays and preview/live frames on real devices
- [x] 10.14 Add one-shot glasses wake on the first decoded frame of every Screen Mirror and External Display session, with per-session deduplication and regression tests
- [ ] 10.15 Restore overlay Exit by dispatching the focused control directly, retaining physical button clicks through the same action path, and verify LIVE_STOP on-device
- [x] 10.16 Replace centered-only X placement with horizontally movable but fully in-viewport geometry across protocol, editor, persistence, favorites, preview, and glasses
- [ ] 10.17 Trigger a bounded full-display `ACQUIRE_CAUSES_WAKEUP` wake-lock on the first complete access unit, before decoder recovery, and verify both live modes on glasses
- [x] 10.18 Replace both launcher icon sets with the approved display-to-glasses transfer mark: black/mint/green for the companion and green-only transparency for the glasses, including legacy, adaptive, round, and monochrome resources
- [x] 10.19 Verify packaged launcher resources, protocol/unit/lint/assemble, strict OpenSpec validation, and `git diff --check`, then install both refreshed APKs on the exact Pixel and RG_glasses serials

## 11. Configurable glasses gestures

- [x] 11.1 Define and test bounded DBP1 gesture-settings synchronization and session-scoped playlist/favorite commands
- [x] 11.2 Add the persisted Material Gesture Settings page with swipe/tap/double-tap choices, direction reversal, exit confirmation, and enabled-favorite selection
- [x] 11.3 Apply synchronized settings to hidden-overlay glasses input while preserving fixed visible-overlay navigation and the non-playing guarded exit
- [x] 11.4 Dispatch playlist media keys and cycle enabled favorites in current persisted order through the acknowledged layout path
- [x] 11.5 Pass protocol, companion, and glasses tests, both lint/assemble tasks, strict OpenSpec validation, and `git diff --check`
- [ ] 11.6 Install both refreshed APKs on the exact devices and verify every configured gesture, direction reversal, confirmation, and favorite ordering on hardware

## 12. Gesture and favorite interaction refinement

- [x] 12.1 Move swipe controls into a final combined `Swipes & favorites` section and preserve persisted gesture settings
- [x] 12.2 Collapse continuous firmware direction streams to one logical swipe action with bounded quiet-gap rearming and regression tests
- [x] 12.3 Replace favorite up/down actions with persisted direct drag-and-drop ordering
- [x] 12.4 Replace inline favorite creation with a final `Add` action and Material name/X/Y editor using bounded precise values
- [x] 12.5 Pass companion/glasses/protocol tests, both lint/assemble tasks, strict OpenSpec validation, and `git diff --check`
- [ ] 12.6 Install refreshed APKs and verify one-action swipes, drag reorder, precise favorite creation, and gesture cycling on hardware

## 13. Gesture and favorite layout refinement

- [x] 13.1 Rename the final gesture section to plain `Swipes` and remove its enclosing card without changing persisted controls
- [x] 13.2 Fully expand the drag-and-drop favorites list into the outer page without nested scrolling and add regression coverage
- [x] 13.3 Pass companion unit tests, lint/assemble, strict OpenSpec validation, and `git diff --check`
- [ ] 13.4 Install the refreshed companion APK on Pixel user 0 and verify both refined phone surfaces
