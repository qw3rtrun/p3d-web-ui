# 14 — The reply decoder reads a line once

**Goal.** `decode` is the primitive. A reply line off the printer is parsed once, not twice, and a
decoder that changes its mind between "does this match" and "here is the value" becomes impossible
rather than merely unlikely.

**Depends on:** nothing. **Blocks:** [15](./15-terminal-adopts-gcode.md) — the transport should adopt
the interface this note produces, not the one that exists today.

**This one is on a live path.** `PrinterReactor.java:152` calls `MarlinRsDecoder.INSTANCE.decode(line)`
for every line the printer sends, in production, today. Unlike the parser and the session layer
(see [15](./15-terminal-adopts-gcode.md)), the reply decoders are adopted — so this is real cost on
real traffic, not a hypothetical.

## The problem

`protocol/GRs.kt:9`:

```kotlin
interface GRsDecoder<out T : GRs<out T>> {
    fun match(line: String): Boolean
    fun decodeParams(line: String): T

    fun decode(line: String): T? {
        return if (match(line)) decodeParams(line) else null
    }
}
```

`match` and `decodeParams` are the abstract members and `decode` is derived from both, so the full
work happens twice. It is worst exactly where the work is largest — `marlin/event/TemperatureRs.kt`
calls `TemperatureFields.scan(line)` in `match` (`:94`, `:128`, `:143`) and again in `decodeParams`
(`:99`, `:133`), and one `scan` builds two `HashMap`s, a `Pair` and a `BigDecimal` per field, plus an
`uppercase()` per key, and runs a `Matcher` across the line.

Then the dispatch multiplies it: `MarlinRsDecoder.decode` walks 8 decoders, the last of which
(`BaseRsDecoder`) walks 7 more, and `OkRsDecoder` (`protocol/OkRs.kt`) calls `SimpleOkRs.match` twice
on top of that. A bare `ok` — by far the most common line on the link — costs on the order of 15
`match` calls, several `line.trim()` allocations and two regex matcher constructions before it
resolves to a value.

There is a correctness edge under it too: `decodeParams` re-validates with `require`, so the day
`match` and `decodeParams` disagree the result is an exception thrown out of the middle of a Reactor
pipeline instead of the `null` that `PrinterReactor.java:152-153` is written to handle.

## Do

- [ ] **Write the counting test, red first.** A `GRsDecoder` test double that increments a counter on
      each `TemperatureFields.scan`, asserting that
      `MarlinRsDecoder.decode("ok T:210.00 /210.00 B:60.00 /60.00")` triggers **exactly one** scan.
      Expected red: it is two today. A second case over a bare `"ok"` counting total `match` calls
      across the chain makes the dispatch cost visible and pins it against regression.
- [ ] **Invert the interface** (`protocol/GRs.kt:9-16`):

      ```kotlin
      interface GRsDecoder<out T : GRs<out T>> {
          fun decode(line: String): T?
          fun match(line: String): Boolean = decode(line) != null
          fun decodeParams(line: String): T = requireNotNull(decode(line)) { "..." }
      }
      ```

      Then rewrite each implementation to compute once and return `null` instead of failing `match`.
      Mechanical across the 15 files that currently `override fun match(` — `protocol/BaseRS.kt`,
      `BusyRS.kt`, `CommentRS.kt`, `ErrorRS.kt`, `OkRs.kt`, `ResendRS.kt`, `StartRS.kt`, `WaitRS.kt`,
      and `marlin/event/EchoRs.kt`, `EndstopRs.kt`, `FirmwareInfoRs.kt`, `MarlinRs.kt`,
      `PositionRs.kt`, `SdStatusRs.kt`, `TemperatureRs.kt`.
- [ ] **Collapse the two dispatchers** to `firstNotNullOfOrNull { it.decode(line) }` once their
      members return values rather than booleans. `BaseRsDecoder` and `MarlinRsDecoder` then do one
      pass over their decoders with no double work, and `OkRsDecoder`'s doubled `SimpleOkRs.match`
      disappears on its own.
- [ ] **Rewrite [06](./06-decoder-edge-portability.md) against the surviving files.** It targets
      `marlin/decoder/**`, a directory deleted in `d34d0a3`. Its complaint — regex, `Optional`,
      `commons-lang3`, `ignoreCase` — is still valid, but the sites are now `protocol/**` and
      `marlin/event/**`. Either repoint it and note the overlap with this file, or fold it into this
      one and mark 06 superseded. Do not leave it citing a path that no longer exists.

## Trade-off

`match` becomes as expensive as `decode` for a caller that only wanted a boolean. In practice the
only such callers are `MarlinRsTest` and `BaseRsTest`, whose partition assertions ("no line is
claimed by two decoders") keep working unchanged. If a real hot caller for a cheap `match` ever
appears, the answer is a `matchPrefix` on the specific decoder, not a second full parse of the line.

Cheap to reverse, and well covered: 436 + 321 lines of decoder tests pin the behaviour, so a
regression shows up as a failure rather than as drift.

## Verify

- [ ] The counting test is green and was red at two scans first.
- [ ] `./gradlew :gcode:test` — 797 plus the new cases, 0 failures. In particular the partition tests
      in `MarlinRsTest` and `BaseRsTest`, which are the ones that would catch a decoder that started
      claiming a line it used to decline.
- [ ] `./gradlew build` green — `PrinterReactor` and `PrinterReactorDispatchTest` are the live
      consumers of `MarlinRsDecoder` and must not need editing.
- [ ] No implementation still overrides both `match` and `decodeParams`:

      ```bash
      grep -rn 'override fun match(' gcode/src/main/kotlin/
      ```
