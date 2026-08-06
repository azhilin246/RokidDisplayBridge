## Context

DBP1 carries complete H.264 access units fragmented into independently framed messages over an AOA byte stream. The current parser bounds the total append before consuming complete frames, the glasses submit every fragment to an unbounded executor, and sender pressure can remove one predictive frame without invalidating its dependants. Decoder output is also anchored behind a fixed 150 ms presentation buffer while up to twelve access units can wait on the phone.

The Pixel encoder and RG_glasses decoder remain hardware `MediaCodec` instances connected to `Surface`/`TextureView`. Audio remains owned by Android on the phone. The glasses optical output is green, but the hardware AVC path is a compressed Surface pipeline rather than three independent RGB byte streams.

## Goals / Non-Goals

**Goals:**

- Make all byte, frame, access-unit, executor, and presentation backlogs bounded under sustained operation.
- Preserve DBP1 framing across arbitrary USB read boundaries and multiple coalesced frames.
- Recover from sender or decoder pressure only at a clean keyframe boundary.
- Make decoder failures terminal and idempotently release the affected live session while retaining a healthy transport where possible.
- Minimize glass presentation delay without software codecs, CPU copies, or speculative vendor parameters.
- Expose enough pressure and recovery diagnostics to identify the last healthy stage.

**Non-Goals:**

- Changing AOA identity, packages, modes, audio ownership, display geometry, or media-key behavior.
- Adding a GLES color-conversion stage or a monochrome AVC profile whose device support is unproven.
- Trading reference integrity for an arbitrarily small queue or claiming a latency value without device measurement.

## Decisions

### Parse one bounded frame incrementally

`FrameStreamDecoder` will maintain one fixed maximum-frame buffer and an expected frame length. It will copy only the bytes required to finish the current header/frame, emit it, reset, and continue through the same input chunk. This accepts fragmentation and coalescing while never retaining more than one bounded incomplete frame. The alternative of enlarging the append buffer would mask the immediate failure but still permit avoidable copies and ambiguous memory bounds.

### Recover sender pressure at a keyframe boundary

The live sender queue will hold at most three complete access units. Overflow clears the queued predictive tail, rejects non-key input until a keyframe, and emits one recovery request; a newly arriving keyframe replaces obsolete queued video and starts a clean GOP. Complete fragmented access units remain atomic. This prefers a brief recoverable discontinuity over sending corrupt reference chains.

### Reassemble before bounded decode submission

USB fragments will be reassembled synchronously under the existing bounded reassembler. Only complete access units enter the decoder executor. A small ingress gate bounds accepted-but-not-completed units and enters keyframe-wait mode on pressure. Work items recheck session/generation when executed, so stopped or replaced sessions cannot mutate a decoder.

### Treat decoder failure as a live-session terminal event

The first unexpected MediaCodec failure atomically deactivates the decoder, clears bounded state, releases codec resources on its owning worker, publishes one `ERROR`, and causes glasses to send `LIVE_STOP(ERROR)` for the active session. Repeated queued failures are suppressed. Transport remains available for a later new session unless the USB path itself failed.

### Render live output immediately while dropping stale presentation

With encoder B-frames disabled, decoder output order is presentation order. The glasses will decode reference frames but render output immediately; output already more than two frame intervals behind the newest reassembled PTS is released without rendering. This removes the fixed 150 ms clock anchor while preserving decoder reference continuity. Feedback counts decoded, presented-late drops, and keyframe requests even during recovery.

### Use official realtime MediaCodec hints conservatively

The encoder will use realtime priority, requested zero-frame encoder latency, a maximum Surface-input rate of 30 fps, zero B-frames, CBR when advertised, and a 1.5 Mbit/s bound. The decoder will enable the standard low-latency feature only when its selected hardware codec advertises support. Output formats and diagnostics record effective values where available; unsupported optional hints are allowed to be ignored rather than triggering a software fallback.

### Keep color processing out of the critical path

AVC Surface input is not three separately transported RGB channels. A forced grayscale/green conversion would require another render target and GLES pass, adding lifecycle and latency risk for an unmeasured compression benefit. The initial safe optimization is the lower bounded bitrate and smaller queues; any future monochrome mode requires separate hardware measurements and opt-in design.

## Risks / Trade-offs

- [A three-unit queue drops during a transient USB stall] -> Clear dependent video, request an IDR once, and resume only from a keyframe.
- [A codec ignores optional latency keys] -> Inspect advertised capability/output format and continue with the same hardware codec and bounded software queues.
- [1.5 Mbit/s reduces detailed text quality] -> Retain 480x640 and measure on glasses; bitrate can be raised without changing queue/error architecture.
- [Immediate rendering shows burst catch-up] -> Decode stale references but suppress rendering when their PTS is over two frames behind the newest complete unit.
- [A terminal decoder error briefly changes UI twice] -> Deduplicate decoder state publication and make final error rendering authoritative.

## Migration Plan

1. Add regression tests that reproduce coalesced maximum frames and pressure state transitions.
2. Deploy parser and queue hardening before changing latency constants.
3. Deploy bounded decoder ingress and fatal cleanup.
4. Enable official low-latency hints and smaller presentation/sender budgets.
5. Install both APKs on exact serials and compare counters, continuity, and user-observed delay during External Display and Screen Mirror.

Rollback installs the previous Display Bridge APK pair only; no data or protocol migration is required because DBP1 wire fields remain compatible.

## Open Questions

- What sustained bitrate gives the best small-text quality on the RG_glasses optics after the stability fixes: 1.2, 1.5, or 2.0 Mbit/s?
- Does the selected RG_glasses hardware decoder advertise Android's low-latency feature, and what effective encoder latency does the Pixel report?
