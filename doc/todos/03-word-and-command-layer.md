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

- [x] **`GCommandParser` is reachable.** Two public overloads: `parse(words: List<GWord>)` and
      `parse(line: GLine)`, the latter being `parse(line.meaningful())` so a caller never unpacks a
      `GPacketLine` by hand. Stateless, so one instance is interchangeable.
- [x] **Word assembly across whitespace** — already done by `SemanticIterator` in `286461b`: a word
      absorbs the whitespace between its identifier and value, so `X10`, `X 10` and `X  10` are one
      word. Now deliberate rather than accidental, and covered by a parameterised test including
      `G 1 X10` (space inside the *command* word) and a leading tab.
- [x] **Value-less flag parameters** (spec 3.2) — `G28 X Y` is one command with two `GFlagWord`
      parameters. The corpus has **236** flag words, so this was never optional.
- [x] **Subcodes** (spec 4.1) — decided: the subcode stays *on the command number*, and
      `GCommand.head` is widened from `GParameterWord<GInt>` to `GParameterWord<GNumber>`, so `G29.1`
      is one command word whose value is `GFloat("29.1")`. Round-trip is free because the word keeps
      its lexeme and its raw tokens. A separate `subcode` field was rejected: splitting `29.1` into
      `29` + `1` means re-deriving the text to re-emit it, and `29.10` vs `29.1` would not survive.
      The accepted grammar is checked explicitly against spec 4.1 - `<unsigned-int>[.<unsigned-int>]`
      - so `G-1` and `G29.` are not commands.
- [ ] **`GCommand.head` tightened to `GLetter`** — still open, deliberately. `head` is now a
      `GParameterWord<GNumber>` whose `id` is a `GIdentifier`, so `GChecksum` is still admissible by
  type; the parser refuses it, but nothing in the type system does. Making it structural means
      `GRQ` carrying `letter: GLetter` and `code: GNumber` as separate fields, which is a change
  to what the encoder emits from — so it belongs with [04](./04-encoder-and-checksum.md), not here.
- [ ] **`GNotIdentifierError` is still never constructed** — and this item should be dropped rather
      than done. It asks for "a line whose first element is not a command letter" to be an error, but
      spec [4.1](../specs/GCODE_spec.md#41-command-letters) says the opposite: with
      `GCODE_MOTION_MODES` an axis letter may legally start a line, inheriting the last motion command
      (`X10 Y10` continues the previous `G0`/`G1`). Reporting that as an error would be wrong.
      Resolving it needs the *previous* line, i.e. session state, which is
      [05](./05-line-numbering-and-session.md). Such a line reports no command today, with a test
      saying so. The type also does not fit: `GNotIdentifierError.head` is a `GValue`, and since
      `286461b` moved `GLetter` out of the value hierarchy a letter cannot be put in it.

## Verify

- [x] `GCommandParser` has tests: **30** in `GCommandParserTest`, over one command, several commands,
      flags, subcodes, whitespace variants, structural fields, a bare command letter, a signed
      command number, a comment-only line and an empty word list.
- [x] Corpus census, so the ratio cannot drift silently: every corpus line carrying a word carries
      **exactly one command, with three pinned exceptions** - `G53 G0 X0 Y0 Z0` and `G53 G1 X20`
      (two each; `G53` is a modal prefix, so splitting is correct per spec 4.3) and
      `M815 G0 X0 Y0|G0 Z10|M300 S440 P50` (four; see *Notes*).
- [x] Round-trip: for every corpus line, the assembled commands re-emit its **parametric** words -
      structural `N` and `*` excluded on both sides, since spec 4 says they belong to the line and not
      to a command.

## Findings from doing it

**`T` heads a command only until one has started.** Spec 4.1 makes `T` a command letter and spec 4.2
lists it as a conventional parameter letter, and the corpus settles which wins where: **40 lines**
use `T` as a parameter of a `G` command - `G29 T`, `G12 P1 S1 T3`, `G26 C P T3.0`. Splitting at every
`T` misparses all forty. The rule is `head == null`, not "index 0", so `N1 T0` still finds its
command after the structural `N` is skipped.

**The `N` skip was too wide, and [05](./05-line-numbering-and-session.md) found it.** The rule below
skipped *every* `N` word, which drops `M110`'s argument — the one command whose parameter is a line
number — so `M110 N7` assembled as a bare `M110` and 05 was unimplementable until it was narrowed to
"only the first word can be the line number". That is also the firmware's rule. The corpus census had
been filtering `N` out of its expected value the same way, so it agreed with the parser while both
were wrong, and was hiding a second bug: every `n` in `M0 Click to continue` was being silently
deleted from the assembled command. The note below stands as the reason the skip exists at all.

**`N` and `*` had to be skipped here, not just left out of the line shape.** A `GPacketLine` keeps
them out of `payload`, which made the skip look unnecessary - but a line that is not a well-formed
packet keeps its words. `N100 M110` (corpus line 173) is a `GMissingChecksum` whose words are
`[N100, M110]`, and without the skip the line number became a *parameter* of `M110`, which re-emitted
the line as `M110N100` - the words in the wrong order. Caught by the round-trip census, not by any hand-written case.

**`D` is not treated as a command letter.** Spec 4.1 lists it (Marlin debug builds) and spec 4.2 also
lists it as a parameter letter - diameter, PID `D`. Treating it as a command would misread the
parameter use, which is the common one, so it is left out and this note is the record of the choice.

## Notes

Bare rest-of-line strings (`M117 Hello World`, `M30 /path/f.gco`) are **not** in scope here — they
need the command letter to decide how the rest of the line lexes, which is a bigger change. They are
[09](./09-deferred-spec-gaps.md).

That deferral has one visible consequence, pinned as a characterisation point in the corpus census:
`M815 G0 X0 Y0|G0 Z10|M300 S440 P50` (corpus line 371) parses as **four** commands. `M815`'s argument
is a bare rest-of-line string, so the `G0`/`G0`/`M300` inside it are not commands at all — but until
[09](./09-deferred-spec-gaps.md) lands, `|` lexes as an unknown token and those words split the line
like any others. The test names the line, so the day 09 fixes it the census will say so.
