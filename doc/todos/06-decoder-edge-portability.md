# 06 — Decoder edge: replace regex, `Optional` and `commons-lang3`

**Goal.** The reply half of the protocol is hand-rolled scanners that can be reviewed against
[spec §9](../specs/GCODE_spec.md#9-error-handling) and transliterated to a port.

**Depends on:** nothing. **Blocks:** nothing. Independent of the whole 02→05 chain and can be picked
up whenever.

## Why

`gcode/src/main/kotlin/org/qw3rtrun/p3d/g/marlin/decoder/**` decodes firmware replies with three
things the module's style rules exclude:

- **`java.util.regex`** — `OkDecoder`, `TemperatureReportedDecoder`, `FirmwareReportDecoder`,
  `CapabilityReportDecoder`. No equivalent semantics across four languages, hides backtracking cost,
  and — the reason that matters here — a regex cannot be reviewed line by line against a byte spec.
- **`java.util.Optional`** on every `decode()` signature. JVM-only and allocating, where Kotlin's
  nullability is native in all four targets.
- **`org.apache.commons.lang3.StringUtils`** for `isNotBlank` / `isNumeric` —
  `CapabilityReportDecoder`, `FirmwareReportDecoder`, `WaitReceivedDecoder`. Both are a two-line
  loop. Note `commons-lang3` arrives on the classpath via
  `buildSrc/.../p3d.java-conventions.gradle`, not through `:gcode`'s own dependencies, so nothing
  currently stops it spreading.

These are the **edge**, not the portable core, so this is lower priority than
[02](./02-number-representation.md). But they are also the reply half of the protocol: a port has to
reimplement every one of them, and today there is no reviewable statement of what they accept.

## Do

- [ ] Replace `Optional<T>` with `T?` across the `decode()` signatures. Mechanical, and worth doing
      first because it is the change that touches every decoder and makes the rest smaller.
- [ ] Replace `StringUtils.isNotBlank` / `isNumeric` with private helpers. Two loops, ASCII-explicit,
      consistent with [01](./01-ascii-and-lexer-portability.md)'s character classes — reuse those
      helpers if they end up somewhere shareable.
- [ ] Replace each regex with an explicit scanner or a small state machine. Take them one decoder at a
      time, each as its own commit. For anything with modes, use an `enum class` plus a `when` over
      `(state, byte)`: it reads as a table, ports as a table, and reviews against the spec as a table.
- [ ] Consider declaring `:gcode`'s dependencies explicitly rather than inheriting the conventions
      plugin's, so a future `commons-lang3` import fails the build instead of silently working.

## Verify

- [ ] **Characterise before changing.** Each decoder's current behaviour over a corpus of real
      firmware replies goes into tests *first*, while the regex is still there. Without that this is a
      rewrite with no safety net — the regexes are the only specification of what these accept, which
      is precisely the complaint.
- [ ] A hostile-input pass per decoder: truncated lines, empty lines, unexpected fields, values out of
      range. Malformed input yields a value, never an exception, same rule as the core.
- [ ] The grep comes back clean:

      ```bash
      grep -rn 'Regex\|java\.util\.regex\|Optional\|StringUtils' gcode/src/main/kotlin/
      ```

## Notes

There is a real gap in the corpus story here. `marlin.gcode` is a fixture of commands *sent*; there
is no fixture of replies *received*. Capturing one — a real session's `ok`, temperature reports,
`Resend:` lines, capability report — would make this file far safer and would also serve
[05](./05-line-numbering-and-session.md). Consider that the first task.

`:gcode` also depends on `:backend:core` for the event types these decoders return, so the module as
a whole is not extractable even after this file. Only `code/core/**` is close to it. That coupling is
noted, not scheduled.
