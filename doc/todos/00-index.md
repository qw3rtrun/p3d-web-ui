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

`:gcode:test` — **398 tests, 0 failures, 0 skipped**. `./gradlew build` green across every module.

Lexing ([spec §2](../specs/GCODE_spec.md#2-lexical-structure-tokens), §3) and line framing
([§5](../specs/GCODE_spec.md#5-line-block-structure), [§7.3](../specs/GCODE_spec.md#73-pairing-rule))
are correct and fully covered. No test freezes a known bug. What is missing is everything **above**
the line: there is no word→command assembly, no encoder, and no checksum verification, so
[spec §4](../specs/GCODE_spec.md#4-identifiers-field-letters) and
[§8](../specs/GCODE_spec.md#8-checksum-and-crc) are unimplemented in practice even though the types
for them exist.

## The queue

| # | File | What it is | Blocks |
|---|---|---|---|
| 01 | [ascii-and-lexer-portability](./01-ascii-and-lexer-portability.md) | Explicit ASCII character classes, drop the `Stream` overload, stray CR in tail comments | — |
| 02 | [number-representation](./02-number-representation.md) | Decide what a number token holds; remove `BigDecimal` and the `Double` path from the core | 03, 04 |
| 03 | [word-and-command-layer](./03-word-and-command-layer.md) | Wire up `GCommandParser`: words across whitespace, flag params, subcodes | 04 |
| 04 | [encoder-and-checksum](./04-encoder-and-checksum.md) | A real encoder, `N`/`*` framing, `GPacketLine.verify()` over `XorCheckSum` | 05 |
| 05 | [line-numbering-and-session](./05-line-numbering-and-session.md) | Line-number continuity, `M110`, the resend window | — |
| 06 | [decoder-edge-portability](./06-decoder-edge-portability.md) | Replace regex / `Optional` / `commons-lang3` in `marlin/decoder/**` | — |
| 07 | [hygiene-and-naming](./07-hygiene-and-naming.md) | File and property renames, `GTokenizer` as an object, leftover semicolons | — |
| 08 | [test-and-doc-debt](./08-test-and-doc-debt.md) | Port or retire eight orphaned Java suites; refresh `.junie/GCODEK.md` | — |
| 09 | [deferred-spec-gaps](./09-deferred-spec-gaps.md) | Bare rest-of-line strings, RS274 parameters, block delete, line length, CRC16 | — |

## Why this order

**01 first** because it is six lines, depends on nothing, and closes the last correctness gap in the
lexer. Doing it early means every later change is written against ASCII-explicit code rather than
copying the Unicode predicates.

**02 before 03 and 04** because it changes the public shape of `GInt` / `GFloat` / `GDDoubleField`.
Building word assembly and an encoder on `BigDecimal` and then removing it would mean writing those
two twice. This is the one genuinely hard decision in the queue and it wants making before anything
is built on top.

**03 → 04 → 05** is a strict dependency chain: the encoder emits words, so word assembly comes first;
the checksum is computed over encoded bytes, so the encoder comes before verification; the resend
protocol addresses lines by number and needs a verified packet to react to.

**06 through 09 are independent** and can be picked up whenever. 06 and 09 are the largest; 07 and 08
are small and make good filler. Note that 07 deliberately holds a pure rename
(`GLiner.kt` → something honest) that would otherwise muddy a behavioural diff — do it between other
files, not inside one.

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
