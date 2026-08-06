## Why

Long-running live sessions can currently collapse after valid USB chunks are misclassified, decoder work outruns an unbounded executor, or one codec failure leaves production running. The same buffering policy deliberately permits several hundred milliseconds of latency, so reliability must be hardened before safely reducing delay.

## What Changes

- Make DBP1 stream parsing accept arbitrarily chunked and coalesced sequences of valid bounded frames without retaining unbounded undecoded bytes.
- Bound glasses-side ingress before MediaCodec and make overload recover through a fresh keyframe instead of accumulating work or decoding a broken reference chain.
- Converge codec, transport, surface, stop, and error paths on idempotent cleanup or bounded recovery.
- Replace the twelve-frame sender backlog and fixed 150 ms presentation buffer with a small live-oriented budget while preserving complete access-unit fragmentation.
- Apply supported Android realtime encoder/decoder hints, cap Surface-input frame rate, retain zero B-frames, and lower the bounded AVC bitrate without adding a software encoder or extra GLES conversion stage.
- Add diagnostics and regression tests for coalesced maximum frames, bounded ingress, pressure recovery, state-transition deduplication, and low-latency configuration.
- Keep the encoded image application-neutral and full-frame. The green optical display is not treated as three independently removable RGB channels; no monochrome preprocessing stage is added to the critical path.

## Capabilities

### New Capabilities

- `low-latency-live-video`: Reliable bounded DBP1 streaming, keyframe-safe overload recovery, idempotent decoder failure handling, and minimal-buffer hardware AVC presentation.

### Modified Capabilities

None. The original project capabilities remain under the active `build-display-bridge` change; this focused capability adds hardening and latency acceptance without changing application isolation, audio ownership, or mode semantics.

## Impact

- Protocol Kotlin stream parser and live frame queue.
- Companion hardware AVC configuration, sender pressure response, feedback, counters, and diagnostics.
- Glasses ingress scheduling, MediaCodec lifecycle, output timing, feedback, and UI state publication.
- Protocol, companion, and glasses tests plus real Pixel/RG_glasses sustained-operation and latency evidence.
- No unrelated file, package, identity, or installed application is changed.
