# `:gcode` — work queue

Each numbered file is one self-contained piece of work: a goal, why it is worth doing, the checklist,
and how to know it is finished. **The numbers are an execution order**, chosen so that no file has to
be reopened because a later one changed a type underneath it. Files can be done one at a time, and
each should land as its own commit with its tests green.

The record of what has already been done — the original review, every fixed bug with its verified
before/after, the conformance and coverage tables — is in
[99-completed.md](./99-completed.md). The syntax authority is
[`../specs/GCODE_spec.md`](../specs/GCODE_spec.md); the style rules for this module are the
`low-level-protocol-dev` skill (`.agents/skills/low-level-protocol-dev/SKILL.md`).

## Status

`:gcode:test` — **654 tests, 0 failures, 0 skipped**. `./gradlew build` green across every module.

Lexing ([spec §2](../specs/GCODE_spec.md#2-lexical-structure-tokens), §3) and line framing
([§5](../specs/GCODE_spec.md#5-line-block-structure), [§7.3](../specs/GCODE_spec.md#73-pairing-rule))
are correct and fully covered, and as of 01 the character classes are ASCII-explicit
([§1.1](../specs/GCODE_spec.md#11-character-set-and-encoding)). No test freezes a known bug — three
did until the `GSemantic` refactor's classification regressions were re-fixed
([1.19](./99-completed.md#119-classification-regressions-from-286461b--gsemanticparserkt--fixed)).

The layer **above** the line is now in too: 03 built word→command assembly
([spec §4](../specs/GCODE_spec.md#4-identifiers-field-letters)) and 04 the encoder and checksum
verification ([§8](../specs/GCODE_spec.md#8-checksum-and-crc), CRC16 included), so a `GPacketLine`
means *verified by construction* and the module can both emit a wire-ready line and tell whether a
received one is intact. Two spec errors were found and corrected while doing it — the CRC16 variant
was underspecified, and §8.3 was wrong about where the covered byte range starts; both were settled
against Marlin's and RepRapFirmware's source, and [04](./04-encoder-and-checksum.md) records them.

The **session** layer is in as well ([05](./05-line-numbering-and-session.md)): continuity, `M110`
and both ends of the resend protocol, in a new `code/core/session/` package that holds the module's
only mutable state. Everything the spec describes is now implemented except the gaps
[09](./09-deferred-spec-gaps.md) lists.

What is left is **cleanup, not capability**: 06–09. None of them blocks anything, and none is on a
critical path.

## The queue

| # | File | What it is | Blocks |
|---|---|---|---|
| ~~01~~ | [ascii-and-lexer-portability](./01-ascii-and-lexer-portability.md) | ~~Explicit ASCII character classes, drop the `Stream` overload, stray CR in tail comments~~ **done — `c92bfe6`** | — |
| ~~02~~ | [number-representation](./02-number-representation.md) | ~~Decide what a number token holds.~~ **done** — decided to *keep* `BigDecimal` and record it as the core's one admitted portability liability; dropped the `Double` path, renamed `GFloat.float` → `value` and `GDDoubleField` → `GDDecimalField` | — |
| ~~03~~ | [word-and-command-layer](./03-word-and-command-layer.md) | ~~`GCommandParser`~~ **done** — commands, flag params, subcodes, structural-field skip. Two items were deferred to 04/05 | — |
| ~~04~~ | [encoder-and-checksum](./04-encoder-and-checksum.md) | ~~A real encoder, `N`/`*` framing, checksum verification~~ **done** — `GEncoder`, `Crc16CheckSum` (XMODEM, pinned from RRF source), verification inside `parseLine`, `GCheckSumFailedLine`, a packet-bearing corpus. Corrected §8.3 and §8.4 | — |
| ~~05~~ | [line-numbering-and-session](./05-line-numbering-and-session.md) | ~~Line-number continuity, `M110`, the resend window~~ **done** — `GCodeReader`, `GSendWindow`, a three-branch receipt type (a repeat is *not* an error). Corrected §5, §7.2 and §8.5 | — |
| 06 | [decoder-edge-portability](./06-decoder-edge-portability.md) | Replace regex / `Optional` / `commons-lang3` / `ignoreCase` in `marlin/decoder/**` | — |
| 07 | [hygiene-and-naming](./07-hygiene-and-naming.md) | File and property renames, `GTokenizer` as an object, leftover semicolons | — |
| 08 | [test-and-doc-debt](./08-test-and-doc-debt.md) | Retire one island of dead Java classes. ~~Port three `XorCheckSum` vectors~~ — done in 04 | — |
| 09 | [deferred-spec-gaps](./09-deferred-spec-gaps.md) | Bare rest-of-line strings, RS274 parameters, block delete, line length. ~~CRC16~~ — done in 04 | — |
| 10 | [command-dsl](./10-command-dsl.md) | The `code/dsl` writing facade — `G.kt` and `GWords.kt`, host-side beside the portable core. **`:gcode` half done** — `GBlock`, per-letter parameter words, line builders, 303/303 corpus lines expressible. Replacing `G.java` needs three `:backend` files | — |

## Why this order

**01 first** — done in `c92bfe6`. It depended on nothing and closed the last correctness gap in the
lexer, so every later change is now written against ASCII-explicit code rather than copying the
Unicode predicates.

**02 before 03 and 04** because it owned the public shape of `GInt` / `GFloat` / `GDDecimalField`.
That block is now lifted: 02 decided to **keep `BigDecimal`** and write the portability liability
down — on `GNumber`, in the file, and in Appendix B — rather than change the representation and pay a
call-site churn for portability the module cannot yet spend. The shape is settled and will not move
under 03 or 04. The replacement a port should reach for (`mantissa × 10^-scale`) is recorded in both
places, so the decision is half-made for whoever starts one.

One correction to the original ordering rationale, since a dev who checks it will otherwise conclude
the dependency was fake: **04 does not build on the number type.** The encoder consumes `rawText()`,
which by design returns the `lexeme` — a plain `String` — and never touches the numeric value. The
real 02→04 coupling was `GDescription`: `GDDecimalField.default: BigDecimal?` is what 04 proposes to
drive the encoder from, and 02 owned that type. The ordering stood; only the stated reason was wrong.

**03 → 04 → 05** was a strict dependency chain: the encoder emits words, so word assembly came
first; the checksum is computed over encoded bytes, so the encoder came before verification; the
resend protocol addresses lines by number and needs a verified packet to react to. All three are in,
and the order paid off — 05 drives its resends from 04's `GCheckSumFailedLine` and frames its lines
with 04's encoder, neither of which existed when the chain was written down.

**Pick up 06–09 in any order** — they are independent of each other and nothing blocks them. 07 and
08 are small; 06 and 09 are the large ones. Note that 07 deliberately holds a pure rename
(`GLiner.kt` → something honest) that would otherwise muddy a behavioural diff — do it between other
files, not inside one. 06's one-line coupling to 02 (`TemperatureReportedDecoder` parses `Double`)
was a note about which file yields rather than a blocker, and 02 resolved it: the decoder keeps its
`Double`, because it is outside the portable core and reads firmware output rather than authored
G-code.

## Conventions

Every file in this queue follows the `low-level-protocol-dev` skill:

- TDD — tests written and run **red first**, and the failures read before the fix. A compile error is
  not a red.
- Malformed input yields a variant carrying the offending bytes, never a thrown exception. Exceptions
  are for programmer error only (`next()` past the end).
- `rawText()` round-trip fidelity is an invariant; a new token kind ships with its round-trip case in
  the same commit.
- Findings are verified by running and quoting real output, not by reading.
- When observable behaviour changes, [`GCODE_spec.md` Appendix B](../specs/GCODE_spec.md) and this
  queue change in the same commit.
