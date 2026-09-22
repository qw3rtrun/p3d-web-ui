# 15 — `:backend:terminal` adopts the module

**Goal.** The line framing, the reply parsing and the resend protocol on the wire are the ones in
`:gcode`, tested against the spec, rather than the second implementation living in
`:backend:terminal`.

**Depends on:** [13](./13-word-identity-and-provenance.md) and
[14](./14-reply-decoder-single-pass.md) — adopt the types once they are worth adopting, not twice.
**Blocks:** the `GSemanticParser` allocation work, which has nothing to measure against until this
lands.

**This is the highest-value file in the queue, and it is not `:gcode` work.** Everything 01–14 argues
about — verified-by-construction packets, the `GDuplicate` non-error, the `M110` exemption, §8.3's
exact covered byte range — is currently unreachable from an actual printer. Read the scope note at
the bottom before starting: the third commit is not `protocol-dev`'s.

## What is actually adopted, and what is not

State it precisely, because the easy version of this claim is wrong. `:backend` **does** use the
command layer and the reply decoders in production:

- `PrinterController.java`, `PrinterState.java` — `marlin/command/**`
- `PrinterReactor.java:152` — `MarlinRsDecoder.INSTANCE.decode(line)`, per received line
- `GSender.java` — `GEncoder`, `GBlock`, `GCommand`, the DSL, `MarlinG`

What has **zero production consumers** is the line layer and the session layer: `GTokenizer`,
`GSemanticParser`, `GPacketLine`, `GCodeReader`, `GSendWindow`. `GSender.java:18` even has a comment
saying it deliberately does not number or window its output "because that is `GCodeReader` /
`GSendWindow`'s job (todo 05)" — and then nothing does it.

So the gap is the transport: framing bytes into lines, classifying what comes back, and answering
`Resend:`.

## The three things wrong in `:backend:terminal`

**1. The framer allocates per byte.** `HostTerminal.java:79-84`:

```java
.flatMap(s -> Flux.fromStream(s.chars().mapToObj(i -> (char) i).map(Object::toString)))
.bufferUntil("\n"::equals)
.map(l -> String.join("", l).trim())
```

One boxed `Integer`, one boxed `Character` and one 1-char `String` **per received byte**, plus a
`List` and a `String.join` per line, plus a `Flux` subscription per inbound chunk. At 250 kbit/s that
is on the order of 30 000 short-lived objects per second to produce roughly 500 lines.
`GTokenizer`'s `GLineCharIterator` does the same job with no allocation per character.

**2. `Replay.java` is a second protocol implementation.** `Replay.parse` (`:11-23`) re-implements
`wait` / `ok` / `Error:` / `busy:` / `Resend:` / `rs:` / `!!` / `fatal:`, with its own advanced-`ok`
regex, duplicating `protocol/OkRs.kt`, `ErrorRS.kt`, `BusyRS.kt`, `ResendRS.kt`, `WaitRS.kt` line for
line — without the spec citations and without the 321 lines of tests. Note it is *also* the copy that
is wired to the serial port, while the tested one is wired to `PrinterReactor`. Two decoders, one
link.

**3. The resend protocol is a no-op.** `HostTerminal.java:65-70`:

```java
void onResend(int lineNumber) {
    // TODO Unsupported yet
}

void onWait() {
    // TODO Unsupported yet
}
```

`GSendWindow` implements both ends of that protocol with 17 tests behind it.

## Do

Three commits, each green on its own, in this order.

- [ ] **Replace the framer.** Feed the inbound `Flux<String>` through `GTokenizer` /
      `GSemanticParser` line splitting instead of the per-character `flatMap`. If the full parser is
      too large a step for one commit, the minimal version — `bufferUntil` over the raw string rather
      than over boxed characters — is a strict improvement and can land first. Keep `trim()`
      behaviour identical for now; changing what a line *is* belongs in its own commit with its own
      test.
- [ ] **Delete `Replay`.** Route received lines through `MarlinRsDecoder.decode` (the single-pass one
      from [14](./14-reply-decoder-single-pass.md)) and map the returned `GRs` into the existing
      `Replay.Message` sink shape so the consumers downstream do not move in the same commit. Then
      remove `msg/Replay.java` and its regex. Port any case `Replay` handles that the `:gcode`
      decoders do not — check `!!` and `fatal:` specifically — as a **test against the `:gcode`
      decoder first**, since that is a spec gap in this module if it is real.
- [ ] **Put the outbound side behind `GSendWindow`,** and wire `onResend(n)` to `resendFrom(n)` and
      `onWait()` to whatever the session layer says waiting means. **Do not start this one until the
      concurrency contract below is written down.**

## The concurrency contract — decide before commit 3

`GCodeReader.lastLine` and `GSendWindow`'s two lists are the module's only mutable state.
`GCodeReader`'s KDoc says so, proudly, and then never says *who may touch them*. That was harmless
while nothing called them. It stops being harmless the moment they sit between a Reactor inbound
`Flux` and an outbound `Publisher`, which are two different threads by default.

Confinement is almost certainly the right answer — one window per `HostTerminal`, every mutation on
the outbound serialisation point — but it has to be **stated in the KDoc as a contract**, not left as
"it is only called from one place". Write it before the code depends on it.

Two smaller repairs in `GSendWindow` belong in the same commit, since they are the same read:

- `acknowledge()` (`code/core/session/GSendWindow.kt:69`) takes no argument and drops the oldest
  entry, even though Marlin's advanced `ok` carries the line number and `AdvancedOkRs.lineNumber`
  already decodes it. One dropped or one spurious `ok` shifts the window by one permanently, and
  every later `resendFrom` then replays the wrong lines — silently, because `resendFrom` (`:94`)
  returns `emptyList()` rather than complaining. Make it `acknowledge(number: Int? = null): Boolean`:
  with a number, drop through it and report whether it was held; without, keep today's FIFO
  behaviour. The default argument keeps all 17 existing tests source-compatible.
- The parallel `ArrayList<Int>` / `ArrayList<String>` (`:40-41`) should be one list of a
  `GSent(number, text)` record. Parallel arrays are a portability hazard and a correctness one —
  nothing enforces that they stay the same length. While there, `reset(to: Int): String?` (`:120`)
  never returns null, so the type lies.

## Scope — who does what

The first two commits are protocol work and can go to `protocol-dev`. The third is not: it changes a
Reactor pipeline with a replay sink and a cancel path in a module `protocol-dev` does not own, and it
depends on a design decision (the confinement contract) that should be written down first. Treat the
third as its own design note once 13 and 14 have landed — the right interface to adopt is the one
they produce, not the one that exists today.

## Verify

- [ ] `./gradlew build` green across every module after each of the three commits, independently.
- [ ] `msg/Replay.java` is gone, and `grep -rn 'Pattern\|Matcher' backend/terminal/src/main` finds no
      reply parsing left behind it.
- [ ] A `HostTerminal` test that feeds a split chunk boundary (`"o"`, `"k\nok\n"`) and asserts two
      replies — the per-character framer got that right by accident and a replacement must get it
      right on purpose.
- [ ] A test that answers a `Resend: 12` with the actual lines 12..n from the window, which is the
      whole point of the third commit and the thing that has never once run.
