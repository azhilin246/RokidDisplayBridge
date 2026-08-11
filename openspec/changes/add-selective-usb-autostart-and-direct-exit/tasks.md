## 1. Selective companion USB autostart

- [x] 1.1 Add strict RG_glasses normal-mode USB identity matching, filtered attach metadata, and a fail-closed no-display dispatcher Activity
- [x] 1.2 Add persisted enabled-by-default autostart settings with independent component enable/disable synchronization
- [x] 1.3 Add the Material phone toggle and connect it to persisted dispatcher state
- [x] 1.4 Add companion tests for defaults, persistence, component state, exact target matching, malformed intents, unrelated devices, and dashboard behavior

## 2. Direct glasses exit

- [x] 2.1 Collapse legacy main-menu input routing into Exit Application while retaining DBP1 enum compatibility
- [x] 2.2 Make overlay and configured exits send a valid active-session stop, skip the READY screen, and finish the task immediately
- [x] 2.3 Remove the duplicate phone main-screen gesture choice and normalize persisted legacy settings
- [x] 2.4 Update glasses and companion gesture tests for direct exit, confirmation, no-session behavior, and migration

## 3. Verification

- [x] 3.1 Pass protocol, companion, and glasses unit tests
- [x] 3.2 Pass both Android lint and assemble tasks, strict OpenSpec validation, and `git diff --check`
- [ ] 3.3 Install refreshed APKs on exact devices and verify selective attach launch plus every direct-exit path on hardware
