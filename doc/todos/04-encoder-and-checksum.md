# 04 — Encoder and checksum verification

**Goal.** The module can emit a wire-ready line and can tell whether a received one is intact. Closes
spec [§8](../specs/GCODE_spec.md#8-checksum-and-crc) — including
[§8.4](../specs/GCODE_spec.md#84-crc16-reprapfirmware) CRC16, pulled in from
[09](./09-deferred-spec-gaps.md) — and finishes
[§7](../specs/GCODE_spec.md#7-line-numbering)'s syntax half.

**Depends on:** [03](./03-word-and-command-layer.md) — an encoder emits words. Also on the
`GPacketLine.raw` → `whole` rename: verification reads that field, and the name currently collides
with `GLine.raw()`.
**Blocks:** [05](./05-line-numbering-and-session.md) — the resend protocol reacts to a verified packet.

## Why

Two orphans meet here.

`GCommand.print()` (`GSemantics.kt:57`) is the module's only encoder and it is wrong: `rawText()`
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

## The decision that shapes this item

**`GPacketLine` is constructed only for a packet whose checksum has been verified.** Verification
moves into `GSemanticParser.parseLine`, ahead of the construction; a well-formed checksum field that
does not match the bytes yields `GCheckSumFailedLine` instead. So the type carries the invariant —
holding a `GPacketLine` means the line is intact, with no `verify()` for a caller to forget.

This replaces the lazy `GPacketLine.verify()` sketched in earlier drafts of this file. It is also why
CRC16 is no longer deferred: under this invariant an unverifiable 5-digit checksum could be neither a
`GPacketLine` nor a `GCheckSumFailedLine`, and modelling a third "well formed but unverifiable" line
type costs more than the ~12 lines CRC16 actually takes.

## Do

- [ ] **Pin the CRC16 variant before writing any of it — this is blocking.**
      [§8.4](../specs/GCODE_spec.md#84-crc16-reprapfirmware) gives the polynomial (`0x1021`) and the
      output width (5 zero-padded decimal digits) but **not** the initial value, the bit order, or the
      final XOR. Those three choices are what separate four different algorithms that all answer to
      "CCITT CRC-16 with poly 0x1021", and they do not agree:

      | variant | init | bit order | final XOR | `N3 T0` | `N1 M115` | `N1 G28` |
      |---|---|---|---|---|---|---|
      | XMODEM | `0x0000` | MSB-first | none | `06939` | `30753` | `14291` |
      | CCITT-FALSE | `0xFFFF` | MSB-first | none | `02583` | `35311` | `14787` |
      | KERMIT | `0x0000` | LSB-first | none | `20362` | `27219` | `55583` |
      | X-25 | `0xFFFF` | LSB-first | `0xFFFF` | `33021` | `58915` | `11920` |

      Get ground truth — RepRapFirmware's own CRC16 source, or one captured `N…*<5 digits>` line from
      real firmware — and record the vector *in the spec* next to the XOR worked example, so §8.4 is
      as pinned as §8.2 is. Do not pick a variant by plausibility: an unverifiable guess here rejects
      every genuine line, which is worse than the current state of not checking at all.

- [ ] **`Crc16CheckSum` as a second `CheckSumCalculator`.** Keep `XorCheckSum` and the CRC in
      separate classes behind the existing interface (`XorCheckSum.kt:10`) — one algorithm per class,
      no flags, no mode parameter. Bitwise, 8 shifts per byte; no lookup table, so a port carries no
      static data. Mask to `0xffff` on the way out of `get()`.
      Its `get()` must return `GInt(crc, lexeme = crc padded to 5 digits)`: `GInt` keeps a `lexeme`
      alongside its `int` (`GTokens.kt:100`), and the zero-padding is load-bearing — an unpadded
      `6939` is four digits, which §8.4's width rule does not recognise as a CRC at all.

- [ ] **One selector, dispatching on digit count, not on value.** 1–3 digits → `XorCheckSum`, 5 →
      `Crc16CheckSum`, anything else → unrecognised. Read the width off
      `checksum.value.lexeme.length`; reading it off `int` silently misreads a zero-padded `*00057` as
      two digits. Keep the dispatch in one function so neither calculator learns about the other.

- [ ] **Verify inside `parseLine`, last.** After the pairing check (§7.3) and the field-syntax check
      (§8.1), so `N1 G28*ABC` stays `GMalformedChecksum` and a bare `*ABC` stays
      `GMissingLineNumber` — neither reaches a calculator. The byte range is
      **every token of `whole[0 until starIndex]`** — a slice, not a reassembly. Traced byte-exact
      against `SemanticIterator`:
      - a word absorbs whitespace between its identifier and value (`N 1` → `[N, GSpace, GInt(1)]`),
        so the head field's own bytes are covered;
      - a space *before* `*` is not absorbed by the star word (`following` must be a `GValue`, and
        `*` is a `GIdentifier`, so it is pushed back), and becomes its own `GMeaningless` at
        `starIndex - 1` — included, as §8.3 requires;
      - a space *after* `*` (`N1 G28* 12`) sits *inside* the star element's raw, so excluding the
        whole star element excludes it, which is correct;
      - the terminator is a `GMeaningless` *after* the star element and falls outside the slice —
        independent of the still-dead `stripTerminator`.

- [ ] **`GCheckSumFailedLine`** — a well-formed checksum field that does not match the bytes:

      ```kotlin
      data class GCheckSumFailedLine(
          override val number: GInt,
          val expected: GInt,   // recomputed
          val received: GInt,   // as carried on the wire
          override val payload: List<GSemantic>,
      ) : GError, GOrdered
      ```

      It takes the full element list as `payload`, like every other error type, so it round-trips for
      free and needs no `whole` of its own — only `GPacketLine` is lossy, because only it decomposes
      the line. `GOrdered` because §8.5's `Resend: <n>` needs the number. Deliberately **not**
      `GCheckSumControlled`: consumers matching that interface should get trustworthy lines only.

- [ ] **Compare `.int`, never `GInt` equality.** `GInt` is a data class whose `equals` includes
      `lexeme`, so a recomputed `GInt(57, "57")` is `!=` a carried `GInt(57, "057")` despite being the
      same number. Structural equality here fails every zero-padded line — and every CRC, which is
      zero-padded by definition.

- [ ] **Decide what an out-of-range value is.** §8.2 puts the XOR result in `0`–`255`, so a 1–3 digit
      field above 255 (`*300`) cannot be any line's checksum. That is a malformed field, not a
      mismatch; pick the error deliberately rather than letting it fall out of the comparison.

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

- [ ] Keep the streaming shape of both calculators — one character in, integer state, mask on the way
      out. They already work on a growing serial buffer and must keep doing so; do not add a variant
      that buffers the whole line.

- [ ] **Recompute the suite's fabricated checksums.** 37 test sites expect a `GPacketLine`, and most
      carry an invented checksum that the new invariant turns into a `GCheckSumFailedLine`. Actual XOR
      values:

      | literal | carried | correct |
      |---|---|---|
      | `N1 G28*12` | 12 | **18** |
      | `N0 G28*0` | 0 | **19** |
      | `N2 G1 X10*33` | 33 | **83** |
      | `N2 G1 X10 *33` | 33 | **115** |
      | `G1 X10*45` | 45 | **15** |
      | `N100 G1 X10 *45` | 45 | **112** |
      | `N42 G1 X1 F100*9` | 9 | **0** |
      | `N42 G1 X10.5 F1800*9 ; move` | 9 | **19** |
      | `N999999 G28*255` | 255 | **35** |
      | `G1 N5*10` | 10 | **45** |
      | `N1 M110 N7*125` | 125 | **123** |
      | `N1 G28*12*13` | 13 | **59** |
      | `N1 G28 *12 ;homing` | 12 | **50** |
      | `N1 G28*18`, `N3 T0*57`, `N2 G28*17`, `N1 M110 N1*125` | | already correct |

      The leading-context cases added by the 1.19 re-fix are all fabricated too, and each differs
      because the bytes before `N` are covered (§8.3):

      | literal | carried | correct |
      |---|---|---|
      | `" N1 G28*18"` | 18 | **50** |
      | `"\tN1 G28*18"` | 18 | **27** |
      | `"\t N1 G28*18"` | 18 | **59** |
      | `"(c) N1 G28*18"` | 18 | **80** |
      | `"(c)N1 G28*18"` | 18 | **112** |
      | `"\t(c) N2 G1 X10*33"` | 33 | **24** |
      | `"N 1 G28*18"` | 18 | **50** |
      | `"n1 G28*18"` | 18 | **50** |
      | `"   n1 G28*18"` | 18 | 18 — **correct by coincidence** |

      That last row is a trap, so leave a comment on it when you touch it: three spaces XOR to `0x20`
      and `0x20 ^ 'n'` is `'N'`, so `"   n1 G28"` and `"N1 G28"` have the same checksum. The test
      passes verification for a reason that has nothing to do with what it is testing.

      Each is a decision, not a find-and-replace: a test about *classification* gets a corrected
      checksum, while a test that wants a genuinely bad line keeps its wrong one and switches its
      expectation to `GCheckSumFailedLine`. This is the bulk of the work in this item.

## Verify

- [ ] The spec's worked example round-trips both ways: encoding `T0` as line 3 yields `N3 T0*57`, and
      verifying `N3 T0*57` succeeds.
- [ ] [08](./08-test-and-doc-debt.md)'s three unported vectors pass as fixtures — `N1 M115` → 39,
      `N1 M155 S1` → 97, `N2 M117 Hello World!` → 7 — which closes that item's last checkbox.
- [ ] The pinned CRC16 vector verifies, and a zero-padded 5-digit CRC (`06939`-shaped) is accepted
      rather than read as a 4-digit unknown.
- [ ] Encoding then parsing is the identity on commands, and parsing then encoding is the identity on
      bytes for any line the encoder could have produced.
- [ ] A one-byte corruption anywhere before the `*` is detected. A transposition is **not** — XOR is
      commutative — and a test should state that limitation rather than leave it implied.
- [ ] Whitespace sensitivity is asserted, not implied: `N1 G1 X0` and `N1 G1X0` have different
      checksums, and a leading space changes the answer (` N1 G28` is 50, `N1 G28` is 18).
- [ ] `GCommand(GLetter('G'), listOf(GInt(1), GInt(2)))` no longer encodes to `G12`. This is the
      assertion that closes 1.10, and it is the one deliberate coverage gap left in the suite.
- [ ] Both calculators gain a caller — check by grep, since "has zero callers" is how `XorCheckSum`
      got here.
- [ ] A packet-bearing corpus fixture exists. `GCorpusTest.kt:171` currently asserts `marlin.gcode`
      contains **zero** `GPacketLine`s, so §7 and §8 have no realistic regression net today.

## Notes

**The reassembly question is answered.** Earlier drafts of this file worried that `GPacketLine`
decomposes its line and cannot rebuild the checksummed bytes, and said the honest fix would be to
carry the raw token list. `286461b` added exactly that field (`raw`, being renamed `whole`), so
verification is a slice of it and no reassembly is needed. The field now has a second consumer, which
is the argument it was missing when it was added.

**`add(ch: Char)` is characters, not bytes.** For ASCII the two coincide, and spec §1.1 keeps the
protocol in ASCII, so this is correct for every line Marlin will send. It stops being correct for a
non-ASCII byte before the `*` — a UTF-8 quoted string — where a UTF-16 code unit is not a byte. Both
calculators inherit this from the interface. Document the limit; do not widen the interface for a case
the spec excludes.

**CRC16 is no longer [09](./09-deferred-spec-gaps.md)'s.** Update that file when this one lands.
