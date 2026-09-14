# 06 — Decoder edge: replace regex, `Optional` and `commons-lang3`

**Goal.** The reply half of the protocol is hand-rolled scanners that can be reviewed against
[spec §9](../specs/GCODE_spec.md#9-error-handling) and transliterated to a port.

**Depends on:** nothing. The one coupling to [02](./02-number-representation.md) is resolved — see
*The 02 coupling* below. **Blocks:** nothing.

## Why

`gcode/src/main/kotlin/org/qw3rtrun/p3d/g/marlin/decoder/**` is eight files that decode firmware
replies with constructs the module's style rules exclude. The attributions below are per-file and
were verified by grep — an earlier version of this file mis-assigned three of them, so check before
widening the scope.

- **`java.util.regex`** — three files, not four: `OkDecoder.kt:53-57` (`ADVANCED_OK_PATTERN`),
  `TemperatureReportedDecoder.kt:73-77` (`TEMP_REPORT_PATTERN`), `FirmwareReportDecoder.kt:33`
  (`((?<field>[A-Z_]+):)+` — the named group `field` is never read, and a `+` over a group is the
  classic backtracking shape). No equivalent semantics across four languages, hides backtracking
  cost, and — the reason that matters here — a regex cannot be reviewed line by line against a byte
  spec. **`CapabilityReportDecoder` uses no regex**: it is a length/prefix guard plus
  `line.split(":")` (`:11-17`). Line numbers below are as of the crash-path fix recorded under
  *Already fixed*.
- **`java.util.Optional`** — all eight files, *and* the `GEventDecoder` interface itself
  (`GEventDecoder.kt:8-14`). This is the one item that is **not** mechanical: the interface is a
  `fun interface` extending `java.util.function.Function<String, Optional<G>>` and
  `Predicate<String>`, so the JVM types are in the supertype list, not just the return position. See
  *Do* for the ordering that follows from that.
- **`org.apache.commons.lang3.StringUtils`** — one file, not three: `CapabilityReportDecoder.kt:3`,
  for `isNotBlank` (`:24`) and `isNumeric` (`:33`). Both are a two-line loop. `FirmwareReportDecoder`
  and `WaitReceivedDecoder` are clean — `WaitReceivedDecoder` is now four lines of
  `length != 4 || !startsWith(...)`. Note `commons-lang3` arrives on the classpath via
  `buildSrc/.../p3d.java-conventions.gradle`, not through `:gcode`'s own dependencies, so nothing
  currently stops it spreading.
- **Locale-dependent and allocating string ops the skill's deny table names** — missing from every
  earlier version of this file, and the most numerous item here:
  - `ignoreCase = true` — 8 sites in 5 files: `CapabilityReportDecoder.kt:11, 36`,
    `FirmwareReportDecoder.kt:11`, `OkDecoder.kt:13, 16, 45`, `TemperatureReportedDecoder.kt:32`,
    `WaitReceivedDecoder.kt:9`. Kotlin's `ignoreCase` is locale-dependent and has no portable
    equivalent; the wire format is ASCII
    ([§1.1](../specs/GCODE_spec.md#11-character-set-and-encoding)), so these want an ASCII case fold.
  - `.trim()` — `OkDecoder.kt:12`, `FirmwareReportDecoder.kt:22`. Allocates, and trims the Unicode
    whitespace set rather than the four characters
    [§2.1](../specs/GCODE_spec.md#21-whitespace) defines.
  - `line.split(SEPARATOR)` — `CapabilityReportDecoder.kt:14`. Allocates a list only to read three
    fixed positions out of it.
  - `asSequence().map { }.firstOrNull { }` — `CompositeDecoder.kt:10-12`. A three-stage pipeline
    where a `for` over `encoders` with an early return is the in-house idiom.

These are the **edge**, not the portable core, which is why they sat behind the core work in the
queue order. But they are also the reply half of the protocol: a port has to reimplement every one of
them, and today there is no reviewable statement of what they accept.

## The 02 coupling — resolved, leave `:66-69` alone

`TemperatureReportedDecoder.kt:66-69` parses temperatures with `String.toDoubleOrNull()` into
`Map<String, Double>` and hands them to `TemperatureReport`.
[02](./02-number-representation.md) has landed, and it **kept `BigDecimal`** in the core while
deleting only the `Double` *construction path into `GFloat`*. Neither half reaches this decoder:

- it never builds a token, so the deleted `GFloat(Double)` constructor and `Double.toToken()` were
  never on its path;
- `TemperatureReport` lives in `:backend:core` and declares its fields as `Double`, so the type here
  is fixed by a consumer outside this module's scope;
- this is the decoder edge, not the portable core. The core's dependency-free rule is about
  `code/core/**`, and the temperatures being parsed are firmware output, not authored G-code whose
  exact digits have to round-trip.

So `:66-69` stays as it is. Everything else in *Do* — the regexes, `Optional`, `commons-lang3`,
`ignoreCase` — is untouched by 02 and stands unchanged.

## Already fixed: the three `NumberFormatException` crash paths

Done ahead of this file, because malformed input off a serial link reaching a `Flux` as an exception
terminated the printer's event stream. **Only the throwing conversions changed** — the regexes,
`Optional`, `StringUtils` and the `ignoreCase`/`.trim()` sites below are all still there, so
everything in *Do* stands.

- [x] `TemperatureReportedDecoder.kt` — the number pattern was `((?>[0-9]*.)?[0-9]+)` with an
      **unescaped** `.`, so `[0-9]*.` matched any character: `ok T:21.0 /0.0 B:x1 /0.0 @:0 B@:0`
      matched and `x1` reached `toDouble()`. Dot escaped, and both `toDouble()` and the `@` power's
      `toInt()` are now `…OrNull()` that drop the field, so the line decodes to absence
      ([§9](../specs/GCODE_spec.md#9-error-handling)).
- [x] `OkDecoder.kt` — `([0-9]+)` is unbounded, so `ok P9999999999 B1` overflowed `toInt()`. All
      four conversions are `toIntOrNull()` and a value that will not fit an `Int` yields absence
      rather than a truncated queue depth or line number.
- [x] `CapabilityReportDecoder.kt` — `StringUtils.isNumeric` accepted an 11-digit run and `toInt()`
      then overflowed on `Cap:AUTOREPORT_TEMP:99999999999`. `parseEnabled` now returns `Boolean?`,
      `null` meaning "could not be read", and `decode` turns that into absence.

Two adjacent defects were found while doing that and **deliberately left alone**, both verified by
running:

- `TemperatureReportedDecoder`'s `[+-]?` sits *outside* the capture group, so a negative temperature
  loses its sign: `ok T:-5.0 /0.0 B:1.0 /0.0 @:0 B@:0` decodes as `current=5.0`. Wrong value, not a
  crash; fix it with the rewrite in *Do* (a scanner makes the sign part of the number, per
  [§3.1](../specs/GCODE_spec.md#31-numeric-values)).
- `FirmwareReport` in `:backend:core` throws from its **accessors**, not from decoding:
  `extruderCount()` on `EXTRUDER_COUNT:99999999999` throws `NumberFormatException` (its
  `StringUtils.isNumeric` guard admits the value), and `uuid()` on a malformed UUID throws
  `IllegalArgumentException`. Different module and a caller-triggered path, so it is not this file's
  and not `:gcode`'s.

## Where the tests already are

All five wired decoders now have tests, in `gcode/src/test/kotlin/org/qw3rtrun/p3d/g/decoder/` — note
the package is `g.decoder`, **not** `g.marlin.decoder`, which is why they are easy to miss:

| Decoder | Test | Size |
|---|---|---|
| `OkDecoder` | `OkDecoderTest.kt` | 5 parameterized methods, 1 `@Test` |
| `TemperatureReportedDecoder` | `TemperatureReportedDecoderTest.kt` | 2 parameterized methods, 1 `@Test` |
| `FirmwareReportDecoder` | `FirmwareReportDecoderTest.kt` | 1 parameterized method, 2 `@Test` |
| `CapabilityReportDecoder` | `CapabilityReportDecoderTest.kt` | 2 parameterized methods, 4 `@Test` |
| `WaitReceivedDecoder` | **none** | — |
| `CompositeDecoder`, `UnknownStringDecoder`, `GEventDecoder` | **none** | — |

Each of the four covers its malformed-number cases and its no-match cases; none of them covers the
hostile-input pass in *Verify* in full, and `FirmwareReportDecoderTest` carries one explicit
characterisation point (a lower-case `firmware_name:` passes the `ignoreCase` prefix guard and then
matches nothing, so it decodes to absence). `CapabilityReportDecoder` is still the only
`StringUtils` user. And `WaitReceivedDecoder` is **not wired into production at all** —
`PrinterReactor.java:33-39` builds its `CompositeDecoder` from `OkDecoder`,
`TemperatureReportedDecoder`, `CapabilityReportDecoder`, `FirmwareReportDecoder` and
`UnknownStringDecoder`, so a `wait` reply falls through to `UnknownStringDecoder`. Decide whether
`WaitReceivedDecoder` is wired up or deleted before spending a rewrite on it.

## Do

- [ ] **`CapabilityReportDecoder` first.** It is the whole `StringUtils` item, it has no regex to
      unpick, and it has no tests — so it is the smallest end-to-end slice of this file:
      characterise, replace `isNotBlank` / `isNumeric` with ASCII-explicit private helpers, drop
      `split`, drop the two `ignoreCase` sites. Reuse
      [01](./01-ascii-and-lexer-portability.md)'s character classes if 01 made them shareable; if 01
      kept them private to `GTokenizer.kt`, say so here rather than duplicating them silently.
- [ ] **Decide `WaitReceivedDecoder`'s fate** — wire it into `PrinterReactor`, or delete it. One line
      either way, and it decides whether the rest of this file covers seven decoders or eight.
- [ ] **Replace `Optional<T>` with `T?`.** Do the `GEventDecoder` interface as its own commit: the
      `Function` / `Predicate` supertypes (`GEventDecoder.kt:8`) have to go or be re-expressed before
      the eight implementations can change, and `test()`'s default body (`:14`) is written in terms
      of `Optional.isPresent`. `:backend:api` constructs `CompositeDecoder` directly
      (`PrinterReactor.java:33`), so check whether anything there relies on the SAM conversion before
      deleting the supertypes.
- [ ] **Replace each regex with an explicit scanner or a small state machine.** One decoder per
      commit, in this order: `FirmwareReportDecoder` (simplest pattern), `OkDecoder`,
      `TemperatureReportedDecoder` (the largest, and the one entangled with 02). For anything with
      modes, use an `enum class` plus a `when` over `(state, byte)`: it reads as a table, ports as a
      table, and reviews against the spec as a table.
- [ ] **Replace the remaining `ignoreCase` and `.trim()` sites** listed in *Why*: an ASCII case fold
      and an explicit §2.1 trim, both as private helpers.
- [ ] **`CompositeDecoder.kt:10-12`** → a `for` loop with an early return.
- [ ] Consider declaring `:gcode`'s dependencies explicitly rather than inheriting the conventions
      plugin's, so a future `commons-lang3` import fails the build instead of silently working.

## Verify

- [ ] **Characterise before changing.** Each decoder's current behaviour goes into tests *first*,
      while the regex is still there — extending the three suites in `g/decoder/` and adding the two
      missing ones. Without that this is a rewrite with no safety net: the regexes are the only
      specification of what these accept, which is precisely the complaint.
- [ ] A hostile-input pass per decoder: truncated lines, empty lines, unexpected fields, values out
      of range, mixed case, a trailing `\r`. Malformed input yields a value, never an exception, the
      same rule as the core.
- [ ] The grep comes back clean. Note this is wider than the grep earlier versions of this file
      carried, which would have passed with all 8 `ignoreCase` sites still in place:

      ```bash
      grep -rn 'Regex\|java\.util\.regex\|Optional\|StringUtils\|ignoreCase\|\.trim()\|uppercase()\|lowercase()' \
        gcode/src/main/kotlin/
      ```

- [ ] `./gradlew build` green, and the `:gcode:test` count has gone **up** by at least the two new
      suites — a rewrite that leaves the count flat has characterised nothing.

## Notes

There is a real gap in the corpus story here. `marlin.gcode` is a fixture of commands *sent*; there
is no fixture of replies *received*. Capturing one — a real session's `ok`, temperature reports,
`Resend:` lines, capability report — would make this file far safer and would also serve
[05](./05-line-numbering-and-session.md). Consider that the first task. The same item is listed in
[08](./08-test-and-doc-debt.md); do it in whichever file reaches it first and strike it from the
other.

`:gcode` also depends on `:backend:core` for the event types these decoders return, so the module as
a whole is not extractable even after this file. Only `code/core/**` is close to it. That coupling is
noted, not scheduled.
