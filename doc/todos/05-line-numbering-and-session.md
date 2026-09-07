# 05 — Line numbering and the host session

**Goal.** The host side can track line-number continuity, handle `M110`, and drive the resend
protocol. Closes [spec §7.2](../specs/GCODE_spec.md#72-semantics) and
[§8.5](../specs/GCODE_spec.md#85-failure-handling-and-the-resend-protocol).

**Depends on:** [04](./04-encoder-and-checksum.md) — a resend is triggered by a failed verification.

## Why

Line numbers exist for transport reliability. Today the module parses them and stops: nothing tracks
whether the number is *last + 1*, which is the only thing a line number is for. Spec §7.2 is
unimplemented, and with it the whole reason `N` and `*` exist.

This is also the first piece of the module that is **stateful**. Everything so far is a pure function
from bytes to tokens to lines; a session has a counter and a send window. Keep that boundary clean:
the state lives here, not in the tokenizer or the liner.

## Do

- [ ] A stateful reader over the line stream, holding the last accepted line number. The name
      `GCodeReader` is free — [99-completed](./99-completed.md) records deleting an empty stub by that
      name in Commit B — and it is the natural fit.
- [ ] **Continuity checking**: the number must be exactly previous + 1. A gap means a line was lost, a
      repeat means one was duplicated. Report it as a framing-error value naming the last good line,
      mirroring the firmware's own message
      (`Error:Line Number is not Last Line Number+1, Last Line: <n>`).
- [ ] **`M110 N<n>`** sets the counter — this is how a host resynchronises or starts a session
      (`M110 N0`). Note the parsing subtlety this depends on
      ([03](./03-word-and-command-layer.md)): in `N1 M110 N7*125` the *first* `N` is the line number
      and the second is `M110`'s parameter. The liner already gets this right by taking only the
      first element; the reader must read the parameter, not the line number.
- [ ] Decide what the reader does with the classifications it receives. `GEmptyLine` consumes no line
      number ([§5](../specs/GCODE_spec.md#5-line-block-structure): "a line number is only consumed by
      a line that is actually transmitted"), and `GMissingChecksum` is benign in a file but an error
      over a link — which is the decision the liner deliberately left to its caller, and this reader
      is that caller. Make it explicit, probably a mode.
- [ ] **The resend window.** Motion commands are buffered and `ok` acknowledges *queuing*, not
      completion, so a host keeps a small window of unacknowledged lines (Marlin's `BUFSIZE`,
      default 4) and rewinds it on `Resend: <n>`. Whether that belongs here or in `:backend:terminal`
      is a real question — the parsing half belongs here, the I/O half does not.

## Verify

- [ ] A clean ascending sequence is accepted; a gap, a repeat and a rewind after `Resend` each produce
      the right outcome.
- [ ] `M110 N0` resets mid-stream, and the line carrying the `M110` is itself numbered correctly.
- [ ] Blank and comment-only lines do not consume a line number.
- [ ] The reader is driven by a plain iterator and holds no I/O — no `Mono`, no `Flux`, no coroutine.
      That belongs to the transport layer.

## Notes

Spec [§7.1](../specs/GCODE_spec.md#71-syntax) notes two dialect quirks worth handling deliberately
rather than discovering later: RS274/NGC allows a *fractional* line number, and Marlin tolerates a
sign after `N` (which is why `N-1` parses as a packet today rather than an error).
