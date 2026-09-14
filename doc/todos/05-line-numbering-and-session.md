# 05 — Line numbering and the host session

**Status: done.** [Spec §7.2](../specs/GCODE_spec.md#72-semantics) and
[§8.5](../specs/GCODE_spec.md#85-failure-handling-and-the-resend-protocol) are implemented. The
module can track line-number continuity, handle `M110`, and drive both ends of the resend protocol.

**Depended on:** [04](./04-encoder-and-checksum.md) — a resend is triggered by a failed verification,
and the window frames its lines with the encoder. **Blocks:** nothing.

## What was built

Three files under a new `code/core/session/` package, which is where the module's **only** mutable
state now lives. Everything below it stays a pure function from bytes to tokens to lines; the
subpackage exists to make that boundary visible rather than implied.

- **`GCodeReader`** — the receiving side. One instance is one session, holding `lastLine`. `read(line)`
  returns a `GReceipt`.
- **`GReceipt`** — `GAccepted` / `GDuplicate` / `GRejected`, the last a sealed set of `GOutOfSequence`,
  `GCorrupted`, `GUnchecksummed`, `GUnnumbered`, `GMalformed`, each carrying the firmware's own
  message text and a `resend` number.
- **`GSendWindow`** — the sending side. Numbers and frames outgoing lines, keeps the unacknowledged
  ones, replays them on request, and emits `M110` to resynchronise.

Both are **free of I/O** — no `Mono`, no `Flux`, no coroutine, no stream. A caller drives the reader
from a plain iterator and sends what the window returns. That answers the question this file left
open about where the resend window belongs: **the bookkeeping half is here and the transport half is
not**, which is what lets the session travel with the rest of `code/core` to a port.

## Three outcomes, not two

The interesting discovery, and the one that changed the design. This file's checklist said "a gap
means a line was lost, a repeat means one was duplicated. Report it as a framing-error value". **A
repeat is not an error.** Marlin's `queue.cpp`:

```c
if (gcode_N != serial.last_N + 1 && !M110) {
  // A request-for-resend line was already in transit so we got two - oops!
  if (WITHIN(gcode_N, serial.last_N - 1, serial.last_N)) continue;
  gcode_line_error(F(STR_ERR_LINE_NO), p);
```

`continue` — the line is dropped with no error and no resend request, and the counter does not move.
The reason is a race the resend protocol creates itself: a host that has already retransmitted when
the `Resend:` arrives sends the same line twice, and answering the second copy with another resend
request does not converge. So the tolerance is a **two-wide window below the counter**, and
rejection is `n < last - 1 || n > last + 1`. That is `GDuplicate`, and it is why the receipt type has
three branches.

## Other decisions, each grounded in firmware

- **`M110` is exempt from the continuity check** (`&& !M110` above). It has to be: resynchronising is
  the one thing a host does *because* the sequence is already broken.
- **`M110` sets the counter to its `N` parameter, not to the line's own number.** Marlin takes the
  *second* `N` on the line (`strchr(command + 4, 'N')`), so `N1 M110 N7` leaves the counter at 7.
  With no parameter it falls back to the line number.
- **`M110` is detected from the parsed commands, not by scanning text — a deliberate divergence.**
  Marlin uses `strstr(command, "M110")` over the raw buffer, which does not know where a comment
  starts: on `N2 G28*17 ; M110 N0` it would find the text, skip the continuity check, take the `N0`
  *in the comment* as the new counter, and reset the session from a comment. That follows from how it
  scans rather than from anything in §7.2, so it is not copied. Tested.
- **The checksum is judged before the line number.** §8.5 says so and RepRapFirmware does it — it
  validates at buffer-fill time in `StringParser::Put`, before the line number is looked at anywhere.
  Marlin is the other way round. It costs nothing to follow the spec here because the liner has
  already decided the checksum: a `GPacketLine` is verified by construction (04). Both firmwares
  reject and both ask for the same resend, so only the message differs.
- **The counter starts at 0**, so a session's first transmitted line is `N1` — Marlin initialises
  `last_N` the same way, and it is why a session conventionally opens with `M110 N0`.
- **A full window consumes no line number.** A gap is exactly what the firmware rejects, so letting
  back pressure burn a number would turn it into a protocol fault.
- **A replay is the original bytes**, not a re-encoding. §8.3 makes a checksum a property of the
  bytes, so re-rendering could differ from what was checksummed the first time.
- **The numbering mode** answers this file's "probably a mode": `GNumbering.OPTIONAL` (a file, and
  Marlin's default over a link — an unnumbered line is fine and consumes nothing) or `REQUIRED` (an
  unnumbered command line is refused, which is what Marlin does while saving to SD). A blank or
  comment-only line is accepted under both: §5 says a line number is only consumed by a line that is
  actually transmitted, and there is nothing on such a line to number.

## The blocker found on the way in

**`M110 N7` assembled as a bare `M110` — its argument was being dropped.** `GCommandParser.isStructural`
skipped *every* `N` word, so the one command whose argument is a line number could not be read, and
this item was unimplementable as written. Narrowed to "only the first word can be the line number",
which is also the firmware's rule (Marlin takes a line number only when `N` opens the line, and then
looks for a second one *precisely because* `M110`'s argument is one).

That change exposed a second, quieter bug. The corpus census filtered `N` words out of its expected
value the same way the parser did, so the two agreed while both were wrong. What it was hiding:
`M0 Click to continue` is a bare rest-of-line string that today lexes letter by letter
([09](./09-deferred-spec-gaps.md)), and **every `n` in "continue" was being silently deleted** from
the assembled command. The census now filters by position and the letters survive.

## Spec corrections

Three, all in the same commit as the code:

- **§7.2** gained the duplicate-tolerance window, the `M110` exemption, the second-`N` rule, and the
  reason each exists. It had said a repeat is an error, which is wrong.
- **§8.5** step 2 said "validates the checksum, then the line number" as though that were settled; the
  two firmwares disagree on the order, and it now says so and says what rides on it (nothing but the
  message).
- **§5's fully-decorated example** carried `*118`, which is not the XOR of anything on that line. The
  correct value is 9 — and `XorCheckSumTest` has carried it for that exact payload all along, so the
  spec and the module's own tests had been contradicting each other.

## Verified

`:gcode:test` — **634 tests, 0 failures, 0 skipped**; `:gcode`, `:backend:core`, `:backend:api` and
`:backend:terminal` all build.

- A clean ascending sequence is accepted; a gap rejects and names the last good line; a repeat and
  the line before it are discarded in silence; anything further behind is a real error.
- No rejected or discarded line moves the counter, which is what makes `resend` answerable.
- `M110 N0` resets mid-stream, the line carrying it is numbered normally, and a reset is accepted
  even when wildly out of sequence.
- Blank and comment-only lines consume no line number, in both modes.
- The two halves are tested **against each other**: everything `GSendWindow` emits, `GCodeReader`
  accepts in sequence, and a full lost-line-then-resend loop settles — including the crossed-resend
  race, which terminates only because a duplicate is silent.
- The reader is driven by a plain iterator and holds no I/O, asserted rather than assumed.
- §7.1's two dialect quirks are pinned: a signed line number (`N-1`) lands on `GDuplicate` by the
  same route Marlin takes to `continue`, and a fractional RS274 line number is refused as malformed
  rather than truncated.

TDD throughout — every file was stubbed first and its tests read red before the implementation.

## What is deliberately not here

The transport. `GSendWindow` says *what* to send and *when* it may be sent; opening a port, writing
bytes, reading `ok` and `Resend:` lines back and calling `acknowledge()` is `:backend:terminal`'s
job. Decoding those replies already exists in `marlin/decoder/**` ([06](./06-decoder-edge-portability.md)).
Wiring the three together is an application-layer task and belongs outside this queue.
