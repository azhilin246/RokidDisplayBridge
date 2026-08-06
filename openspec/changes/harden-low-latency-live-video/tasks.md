## 1. Protocol regression and pressure policy

- [x] 1.1 Add DBP1 tests for multiple maximum frames split and coalesced across arbitrary chunks
- [x] 1.2 Replace append-before-parse buffering with one-frame incremental bounded parsing
- [x] 1.3 Add keyframe-wait pressure outcomes and tests to the three-access-unit sender queue

## 2. Glasses decoder hardening

- [x] 2.1 Reassemble fragments before executor submission and bound accepted complete access units
- [x] 2.2 Reject predictive units after pressure until a keyframe and request bounded recovery feedback
- [x] 2.3 Make first decoder failure idempotently release the active session and propagate `LIVE_STOP(ERROR)`
- [x] 2.4 Deduplicate decoder state publication and add unit tests for ingress and transition policies

## 3. Safe low-latency pipeline

- [x] 3.1 Configure realtime encoder priority, requested zero-frame latency, 30 fps Surface cap, zero B-frames, advertised CBR, and 1.5 Mbit/s bitrate
- [x] 3.2 Enable advertised hardware low-latency decoding and replace fixed startup scheduling with immediate non-stale rendering
- [x] 3.3 Add pressure, effective codec, ingress-depth, late-drop, and keyframe-recovery diagnostics without per-frame UI churn

## 4. Verification

- [x] 4.1 Pass protocol, companion, and glasses unit tests including new parser/pressure/recovery coverage
- [x] 4.2 Pass both Android lint and assemble tasks, strict OpenSpec validation, and `git diff --check`
- [ ] 4.3 Install updated APKs on exact Pixel user 0 and RG_glasses serials while preserving available WiredPlayer packages
- [ ] 4.4 Prove External Display and Screen Mirror continuity, bounded depth, recovery, reduced delay, and sustained operation on real devices
