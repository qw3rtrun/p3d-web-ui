# 04 — Encoder and checksum verification

**Status: done.** Spec [§8](../specs/GCODE_spec.md#8-checksum-and-crc) is implemented, including
[§8.4](../specs/GCODE_spec.md#84-crc16-reprapfirmware) CRC16 pulled in from
[09](./09-deferred-spec-gaps.md), and [§7](../specs/GCODE_spec.md#7-line-numbering)'s syntax half is
finished. `XorCheckSum` had zero callers; it has two now, and a second algorithm beside it.

**Depended on:** [03](./03-word-and-command-layer.md) and the `GPacketLine.raw` → `whole` rename,
both already in. **Blocks:** [05](./05-line-numbering-and-session.md) — unblocked; the resend
protocol now has a verified packet, and a `GCheckSumFailedLine`, to react to.

## What the spec was wrong about

Two corrections landed with the code, and both were settled by reading firmware rather than by
reasoning. They are the most valuable part of this item.

### The CRC16 variant — `0x1021` alone is not a specification

§8.4 named the polynomial and the output width and nothing else. **Four** algorithms answer to
"CCITT CRC-16 with polynomial 0x1021" and they agree on no input:

| variant | init | bit order | final XOR | `N3 T0` |
|---|---|---|---|---|
| **XMODEM** ← the answer | `0x0000` | MSB-first | none | **`06939`** |
| CCITT-FALSE | `0xFFFF` | MSB-first | none | `02583` |
| KERMIT | `0x0000` | LSB-first | none | `20362` |
| X-25 | `0xFFFF` | LSB-first | `0xFFFF` | `33021` |

Guessing would have rejected every genuine line, which is worse than not checking at all — hence
this file treating the question as blocking. Settled from RepRapFirmware, the only firmware that
accepts the field: `src/Storage/CRC16.h` declares "initial CRC value Zero", `CRC16.cpp` is an
MSB-first table update returning the accumulator unmodified, and `StringParser.cpp` is what wires it
to the `*` field. §8.4 now carries the parameter table, the discriminating vectors and the
citations, so the next reader does not have to repeat the search.

### Where the covered byte range *starts*

§8.3 said the checksum runs over "the bytes transmitted before the `*`" without saying where the
range began, which reads as *from the start of the line*. It is not. **Both** implementations start
at the `N`:

- **Marlin** (`Marlin/src/gcode/queue.cpp`, 2.1.x) advances past leading spaces —
  `while (*command == ' ') command++;` — *before* taking the pointer it checksums from, requires `N`
  to be the first character at that point, and XORs `command[0 .. apos-1]`.
- **RepRapFirmware** (`StringParser.cpp`) accumulates in `AddToChecksum`, whose body is guarded by
  `if (hadLineNumber)` and so does nothing until the `N` is seen.

So indentation is **not** covered. The difference is visible only on an indented line, which is why
it survived: for a line beginning at its `N` the two readings agree byte for byte. On `" N1 G28"`
they do not — 50 counting the leading space, 18 without — and 18 is what firmware computes.

Corroboration worth recording: **the existing fixtures already assumed the firmware rule.**
`" N1 G28*18"`, `"\tN1 G28*18"` and `"(c)N1 G28*18"` all carried 18, which is only correct if
indentation is excluded, and all three passed unchanged the moment verification was switched on.
Had this file's original vector table been applied instead, they would have been "corrected" to
50/27/80 and the module would have been wrong in a way its own tests certified.

Two further facts fell out of the same two sources and are now in §8.3: **the last `*` wins** (Marlin
uses `strrchr`), and **a line whose first non-space character is not `N` carries no checksum at all**
as far as either firmware is concerned.

## What was built

- **`Crc16CheckSum`**, a second `CheckSumCalculator` beside `XorCheckSum` — one algorithm per class,
  no flags, no mode parameter. Bitwise, 8 shifts per byte, no lookup table, so a port carries no
  static data. `get()` returns `GInt(crc, lexeme = five zero-padded digits)`, and the padding is
  load-bearing: an unpadded `6939` is four digits, a width §8.1 does not recognise.
- **`checkSumCalculatorFor(lexeme)`**, one selector dispatching on **digit count, not value**. Read
  off the lexeme because `*00057` is a five-digit CRC field carrying 57, whose `int` looks like two
  digits — and a CRC is zero-padded by definition, so this is the common case, not a corner one.
- **Verification inside `parseLine`**, after the pairing (§7.3) and field-syntax (§8.1) checks, so
  `N1 G28*ABC` stays `GMalformedChecksum` and `*ABC` stays `GMissingLineNumber`. The byte range is
  `semantic.subList(headIndex, starIndex)` — a slice of elements already in hand, never a
  reassembly.
- **`GPacketLine` now means verified by construction.** No `verify()` for a caller to forget.
- **`GCheckSumFailedLine`** — number, recomputed `expected`, carried `received`, and the full element
  list as `payload` so it round-trips for free. `GOrdered` for §8.5's resend; deliberately **not**
  `GCheckSumControlled`.
- **`GEncoder`** — `encode(command)` and `frame(number, command, checksum)`. It produces a `String`,
  not a `List<GToken>`, and that is the design: the checksum covers the bytes as transmitted, so
  whitespace is output rather than something a later stage inserts. `frame` prepends the `N` prefix
  first, checksums the finished string second, appends `*` last, and never touches the whitespace
  afterwards. No space before the marker.
- **`GCommand.print()` is gone**, replaced rather than patched.

## Decisions this file left open

- **A width no algorithm claims** (4, 6+ digits) is `GMalformedChecksum`, not a mismatch — nothing
  has been computed at the point it is decided, so there is nothing to mismatch. RRF rejects the
  same way.
- **A 1–3 digit value above 255** *is* a mismatch, not a malformed field. §8.2 puts the XOR result in
  0–255 so `*300` cannot be any line's checksum, but both firmwares compare the parsed number and
  ask for a resend, and matching firmware behaviour is the point of the exercise.
- **A signed value is not a checksum field.** §8.1 spells it `*<unsigned-int>`; `+18` would otherwise
  be three characters carrying 18 and would verify.
- **Comparison is on `.int`, never `GInt` equality**, which includes the lexeme and would fail every
  zero-padded line.
- **The encoder is standalone, not driven by `GDescription`.** The descriptor is the right source of
  truth for *which* fields a command carries and their defaults — a validation and defaulting
  question, one layer up. Emitting bytes is a separate job, and separating them is what lets the
  encoder handle a command the module has no descriptor for, which today is nearly all of them.

## Finding 1.10, honestly

The Verify list asked for `GCommand(GLetter('G'), listOf(GInt(1), GInt(2)))` to stop encoding as
`G12`. **That reproducer no longer compiles**: [03](./03-word-and-command-layer.md) gave `GCommand` a
head/params shape in which every parameter is a `GWord` and so begins with an identifier, which makes
two adjacent bare numbers unconstructible. So 1.10 was closed by 03's type change, not by this item —
the encoder's contribution is canonical separation and being byte-oriented at all. `GEncoderTest`
pins the live equivalent (two number-bearing words stay two words) as a regression net.

## Verified

Run, not read — `:gcode:test` is **581 tests, 0 failures, 0 skipped**, and `:gcode`,
`:backend:core`, `:backend:api` and `:backend:terminal` all build.

- §8.3's worked example round-trips both ways: `frame(3, T0)` is `N3 T0*57`, and `N3 T0*57` parses to
  a `GPacketLine`.
- §8.4's vector likewise: `N3 T0*06939`, and a zero-padded five-digit field is read as a CRC rather
  than as a four-digit unknown.
- [08](./08-test-and-doc-debt.md)'s three unported vectors pass as fixtures — `N1 M115` → 39,
  `N1 M155 S1` → 97, `N2 M117 Hello World!` → 7 — closing that item's last checkbox. All three passed
  first time, so `XorCheckSum` was right and the vectors were good.
- Encode-then-parse is the identity on commands under both algorithms; parse-then-encode is the
  identity on bytes for lines the encoder could have produced.
- A one-byte corruption anywhere before the `*` is detected — asserted over **every byte of every
  line of the packet corpus**, not one hand-written case. A transposition is **not** detected by XOR,
  and the test says so out loud rather than leaving it implied; the same transposition under a CRC is
  caught, which is what makes §8.4 "strictly stronger" a claim rather than a slogan.
- Whitespace sensitivity is asserted: `N1 G1 X0` and `N1 G1X0` differ, a space before the marker is
  covered and a space after it is not, and indentation changes nothing.
- Both calculators have production callers, checked by grep, since "has zero callers" is how
  `XorCheckSum` got into this file.
- **A packet-bearing corpus exists**: `marlin-packets.gcode`, 28 lines of framed host-to-firmware
  traffic, 22 XOR and 6 CRC. Its checksums were computed **outside this module**, from the spec — a
  fixture generated by `GEncoder` would agree with the verifier by construction and would go on
  agreeing with it through a shared bug.

## Notes for later

**TDD, honestly reported.** `Crc16CheckSum` and `GEncoder` were done red-first against a stub. The
verification code in `parseLine` was not — its tests were written afterwards, so they were checked by
breaking the implementation instead: disabling the comparison reds 7 of them, and starting the
covered range at index 0 rather than at the `N` reds 7 more. One real bug was caught that way —
`@CsvSource` trims by default and was silently stripping the indentation the indented cases exist to
carry, so they were passing without testing anything.

**`add(ch: Char)` is characters, not bytes**, in both calculators. For ASCII the two coincide and
§1.1 keeps the protocol in ASCII, so this is correct for every line Marlin will send. It stops being
correct for a non-ASCII byte before the `*` — a UTF-8 quoted string — where a UTF-16 code unit is not
a byte. Documented on the interface; the interface was not widened for a case the spec excludes.

**255 is unreachable.** §8.2 gives the XOR result the range 0–255, but §1.1 keeps the wire in 7-bit
ASCII, so every covered byte is below `0x80` and so is their XOR. The true maximum for any legal line
is **127**. A fixture asserting "the maximum checksum" with 255 was testing a value no generator
could produce.
