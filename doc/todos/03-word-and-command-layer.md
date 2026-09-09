# 03 — The word→command layer

**Goal.** A parsed line exposes its commands and parameters, not just its tokens. Spec
[§4](../specs/GCODE_spec.md#4-identifiers-field-letters) and
[§5](../specs/GCODE_spec.md#5-line-block-structure) become implemented rather than merely typed.

**Depends on:** [02](./02-number-representation.md) — parameter values are numbers.
**Blocks:** [04](./04-encoder-and-checksum.md) — an encoder emits words, so words have to exist.

## Why

`GCommandParser.parseLine` is `private` in a class with no other members and no callers. Nothing in
the codebase can reach it, which means **nothing ever produces a `GCommandLine` or a
`GNotIdentifierError`** — both types exist and neither is ever constructed. The line layer stops at
structure (`GSimpleLine`, `GPacketLine`, the four structural errors) and the word layer is a
disconnected fragment.

Everything above this in the queue is blocked on it. It is also the reason
[1.10](./99-completed.md#110-gcommandprint-can-emit-invalid-g-code--gsemanticskt38-44) cannot be
fixed sensibly on its own: `GCommand.print()` is a placeholder standing where a real encoder belongs.

## Decide first

**Where does word assembly live?** `GLineIterator` currently returns *structure* and is a pure
regrouping of the token stream — it adds no interpretation. Word assembly is a second, separate pass
over one line's tokens.

Recommendation: keep them separate. `GLineIterator` stays as it is, and `GCommandParser` becomes a
public function from a line's tokens to a `GCommandLine`. That composes better (a caller that only
wants framing does not pay for parsing), keeps each pass testable alone, and leaves `GLineIterator`
matching its current tests. The alternative — having the liner produce `GCommandLine` directly —
collapses two concerns into one type and makes the packet/command distinction awkward.

## Do

- [ ] Make `GCommandParser` reachable: `parseLine` public, or a top-level function. Decide whether it
      takes `List<GToken>` or a `GLine`; taking the line lets it work on a `GPacketLine`'s payload
      without the caller unpacking it.
- [ ] **Word assembly across whitespace** ([spec §2.1](../specs/GCODE_spec.md#21-whitespace)). A word
  is a letter plus its value, and whitespace between them is a separator, not a boundary:
      `X 10`, `X10` and `X  10` are the same word. The current `parseLine` filters to `GValue` and
  pairs positionally, which gets this right by accident — make it deliberate and test it.
- [ ] **Value-less flag parameters** ([§3.2](../specs/GCODE_spec.md#32-flag-value-less-parameters)) —
      a letter with no following value is a flag, not a letter waiting for a number. `G28 X Y` is
      three words, two of them flags.
- [ ] **Subcodes** ([§4.1](../specs/GCODE_spec.md#41-command-letters)) — `G29.1` currently lexes as
      `GLetter(G)` + `GFloat(29.1)` and there is no subcode concept on `GCommand`. Decide whether the
      subcode is a field on `GCommand` or a distinct identifier type, then make the round-trip work:
      whatever it becomes must re-emit `29.1`, not `29` and `.1`.
- [ ] `GCommand.head` is typed `GIdentifier`, which admits `GChecksum` as a command head. Tighten it
      to `GLetter`. Small, and it belongs here rather than in the hygiene file because this is the
      change that makes `GCommand` real.
- [ ] Errors as values, as everywhere else: a line whose first element is not a command letter is a
      `GNotIdentifierError` carrying the line. It already exists — this is the file that finally
      constructs it.

## Verify

- [ ] `GCommandParser` has tests at all — it currently has none, because it was unreachable. Cover:
      one command, several commands on a line (`G1 X1 M104 S200`), flags, subcodes, whitespace
      variants, a leading non-letter, and an empty line.
- [ ] Word assembly over the whole `marlin.gcode` corpus does not throw and does not produce
      `GNotIdentifierError` for any line that is really a command. Add a corpus census like the line
      one, so the command/error ratio cannot drift silently.
- [ ] Round-trip: for every corpus line, the assembled commands re-emit the line's payload. This is
      the property that catches a subcode or a flag being quietly dropped.

## Notes

Bare rest-of-line strings (`M117 Hello World`, `M30 /path/f.gco`) are **not** in scope here — they
need the command letter to decide how the rest of the line lexes, which is a bigger change. They are
[09](./09-deferred-spec-gaps.md).
