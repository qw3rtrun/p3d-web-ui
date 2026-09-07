# 04 — Encoder and checksum verification

**Goal.** The module can emit a wire-ready line and can tell whether a received one is intact. Closes
spec [§8](../specs/GCODE_spec.md#8-checksum-and-crc) and finishes
[§7](../specs/GCODE_spec.md#7-line-numbering)'s syntax half.

**Depends on:** [03](./03-word-and-command-layer.md) — an encoder emits words.
**Blocks:** [05](./05-line-numbering-and-session.md) — the resend protocol reacts to a verified packet.

## Why

Two orphans meet here.

`GCommand.print()` (`GSemantics.kt:42`) is the module's only encoder and it is wrong: `rawText()`
values are concatenated with no separator, so adjacent numbers fuse.

```
GCommand(GLetter('G'), listOf(GInt(1), GInt(2))).print()   →   "G12"
```

That is [1.10](./99-completed.md#110-gcommandprint-can-emit-invalid-g-code--gsemanticskt38-44), and
it is not really a bug to patch — it is a placeholder standing where a real encoder belongs. Nothing
emits `N`/`*` framing at all.

`XorCheckSum` (`code/core/XorCheckSum.kt`) is **correct** — 17 tests including the byte-by-byte
worked example from [spec §8.3](../specs/GCODE_spec.md#83-what-the-checksum-covers) — and has
**zero callers**. It is a finished component wired to nothing.

Together they are what makes the `G.kt` DSL unusable over a serial link, and they are the difference
between the *structural* errors the liner reports today and the *framing* errors of
[spec §9](../specs/GCODE_spec.md#9-error-handling), which the module still cannot detect at all.

## Do

- [ ] **A real command encoder.** Separate words so nothing fuses. Simplest correct rule: always a
      single space between words. Replace `GCommand.print()` with it rather than patching `print()` —
      and decide whether the encoder is a function on `GCommand`, a standalone `GEncoder`, or driven
      by `GDescription`. Note that the descriptor already knows a command's fields and their
      defaults, which is the natural source of truth.
- [ ] **A line/framing encoder**: `GCommand`(s) → `N<n> <payload>*<cs>`. The checksum rules are
      exacting and [§8.3](../specs/GCODE_spec.md#83-what-the-checksum-covers) states them precisely —
      read it before writing this:
      - the XOR runs over **exactly the bytes transmitted before the `*`**, nothing normalised;
      - the `N` and its digits **are** included, so the checksum must be computed *after* the prefix
        is prepended;
      - every space actually sent is included — `N1 G1 X0*x` and `N1 G1X0*y` differ;
      - the terminator and any comment after the `*` are **not** covered;
      - therefore: build the final byte string, *then* checksum it, and never touch the whitespace
        afterwards. Emitting no space before `*` is the convention.
- [ ] **`GPacketLine.verify()`** over `XorCheckSum`. Recompute across the line's own bytes and compare
      with the carried value. Mind that `GPacketLine` decomposes its line and does not store the
      leading `N` token or its terminator, so verification has to reassemble
      `N` + number + payload + `*` — check this reassembles byte-exactly, and if it cannot, that is a
      sign `GPacketLine` needs to keep its raw tokens (see *Notes*).
- [ ] Report a mismatch as a value, consistent with everything else: a framing-error variant carrying
      the line and both checksums (expected vs received), not a boolean and not an exception.
- [ ] Keep `XorCheckSum`'s streaming shape — one byte in, integer state, mask on the way out. It
      already works on a growing serial buffer and must keep doing so; do not add a version that
      buffers the whole line.

## Verify

- [ ] The spec's worked example round-trips both ways: encoding `T0` as line 3 yields `N3 T0*57`, and
      verifying `N3 T0*57` succeeds.
- [ ] Encoding then parsing is the identity on commands, and parsing then encoding is the identity on
      bytes for any line the encoder could have produced.
- [ ] A one-byte corruption anywhere before the `*` is detected. A transposition is **not** — XOR is
      commutative — and a test should state that limitation rather than leave it implied.
- [ ] `GCommand(GLetter('G'), listOf(GInt(1), GInt(2)))` no longer encodes to `G12`. This is the
      assertion that closes 1.10, and it is the one deliberate coverage gap left in the suite.
- [ ] `XorCheckSum` gains a caller — check by grep, since "has zero callers" is how it got here.

## Notes

**CRC16** ([§8.4](../specs/GCODE_spec.md#84-crc16-reprapfirmware)) is deliberately not in scope —
it is [09](./09-deferred-spec-gaps.md). But the digit count distinguishes the algorithms (1–3 digits
XOR, 5 digits CRC16), so leave room for that in whatever type carries the checksum rather than
hard-coding XOR into the signature.

**If verification cannot reassemble the bytes**, the honest fix is for `GPacketLine` to carry its raw
token list alongside the decomposition. The review deliberately did not add that field, because at
the time nothing needed it and a field that gets you 90% of an invariant you cannot state is worse
than no field. This file is where the need would become real — decide with a concrete failing test in
hand, not in advance.
