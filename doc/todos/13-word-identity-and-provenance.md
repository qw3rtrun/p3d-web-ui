# 13 — Word identity and provenance

**Goal.** A word's identity is its letter and its value. `raw` is provenance — what the wire happened
to spell — and provenance is not identity. While the token types are open, the dead ones go and the
error hierarchy states the one thing a host actually has to decide.

**Depends on:** nothing. **Blocks:** [15](./15-terminal-adopts-gcode.md) — the transport should adopt
types whose equality is trustworthy rather than types it has to work around.

One theme, five edits. The first is a **bug with no test**; the rest are things that only make sense
to do while the same file is open.

## The bug

`GParameterWord` (`code/core/token/GSemantics.kt:13`) is a `data class` whose primary constructor
includes `override val raw: List<GToken>`. So `equals` and `hashCode` compare the tokens the word was
*spelled* with. Not inferred — `javap -c` on the compiled class shows it:

```
54: getfield  #35   // Field raw:Ljava/util/List;
58: getfield  #35   // Field raw:Ljava/util/List;
61: invokestatic    // Intrinsics.areEqual
```

`MarlinRQ.kt:960` keys the 295-entry decoder registry on the head word, and `:973` looks the incoming
command's head up in it:

```kotlin
private val byHead: Map<GParameterWord<*>, GRqDecoder<*>> = ...
fun decode(cmd: GCommand): GRq<*>? {
    val decoder = byHead[cmd.head] ?: return null
```

A head built by the DSL has `raw = listOf(id, value)`. A head that `GSemanticParser` produced from
`G 1 X10` has `raw = [GLetter(G), GSpace, GInt(1)]` — a spelling
[§2.1](../specs/GCODE_spec.md#21-whitespace) permits and which the parser goes out of its way to
support (the space is absorbed into the word's raw, `GSemanticParser.kt:178`). The two words mean the
same command and compare unequal, so the lookup misses and `decode` returns `null` for a line this
module parses perfectly.

The KDoc above `byHead` documents one sensitivity — that a non-canonical `M0105` lexeme does not
resolve — which is deliberate. Whitespace sensitivity is neither documented nor intended.

**Nothing catches it.** Every `decode` test builds its `GCommand` with the DSL (`MarlinRQTest.kt`,
`MarlinCommandsTest.kt`). `MarlinDocExamplesTest` does parse real text, but then calls
`decoders[i].decodeParams(...)` directly and never goes through `byHead`. **No test carries a line
from raw text all the way to `MarlinCommands.decode`** — which is exactly the gap that hid this.

The same pollution is why two places already rebuild a word rather than cast it, each with a comment
explaining the workaround: `GSemanticParser.kt:140-145` and `GCommandParser.kt:91-93`.

## Do

Each item its own commit, tests red first.

- [ ] **Write the failing test.** New `MarlinCommandsDecodeFromTextTest`, carrying a line the whole
      way — `GTokenizer` → `GSemanticParser` → `GCommandParser` → `MarlinCommands.decode` — over at
      least `"G1 X10.5"`, `"G 1 X10.5"` and `"g1 x10.5"`, each expected to yield `LinearMoveG1`.
      **Expected red: the second returns `null`** at `MarlinRQ.kt:973`. Read the failure before
      changing anything; if the first or third is also red, that is a second finding, not this one.
- [ ] **Take `raw` out of identity.** `GParameterWord` and `GFlagWord` (`GSemantics.kt:13`, `:20`)
      become plain classes with hand-written `equals` / `hashCode` / `toString` over `id` and
      `value`. Explicit `equals` is fine in the portable core — a port writes its own comparison
      regardless. `grep -rn '\.copy(' gcode/src/main` confirms nothing uses the data-class `copy`, so
      that is not a loss. The red above goes green and the other 797 stay green.
- [ ] **Fix the two rebuild comments**, which will otherwise mislead the next reader.
      `GSemanticParser.kt:140-145` and `GCommandParser.kt:91-93` must both **keep** their rebuilds —
      they exist to produce the right *type* (`GParameterWord<GInt>`, and a `GNumber`-valued head)
      without an unchecked cast — but the sentences about staying "equal to the one in `whole`" stop
      being the reason once equality ignores `raw`.
- [ ] **Stop allocating two Strings per `isLetter`.** `GIdentifier.isLetter` (`GTokens.kt:31`) is
      `name.length == 1 && asciiFold(name[0]) == asciiFold(l)`, and `GLetter.name` (`GTokens.kt:47`)
      is a *getter* returning `letter.toString()` — so the default reads `name` twice and allocates
      twice, on a path the KDoc four lines above explicitly says was cleaned up for allocating "two
      or three Strings on a path that runs for every element of every line". Override `isLetter` in
      `GLetter` as `asciiFold(letter) == asciiFold(l)`, and make `name` a stored `val`. `GChecksum`
      keeps the default, and with it the documented quirk that `isLetter('*')` is true.

      For scale: `MarlinWords.kt`'s `valueOf(letter)` is a linear scan per field and
      `LinearMoveG1.decodeParams` calls it 12 times, so decoding `G1 X10 Y20 E5 F1800` is on the
      order of 48 `isLetter` calls ≈ 96 String allocations for one line. The same getter is the
      allocation in `GSemanticParser`'s checksum loop (`:122`), once per letter and per space token
      in the covered range.
- [ ] **Optional, same commit: intern the letters.** A `GLetter.of(c)` factory over a 52-entry array
      of the ASCII letters removes one allocation per letter token in the tokenizer's hot loop. A
      static table transliterates to C/Rust/JS unchanged. It makes `GLetter('X') === GLetter('X')`
      true, which nothing depends on.
- [ ] **Delete the dead types.**
      - `GUnquotedString` (`GTokens.kt:139`) — produced nowhere, referenced nowhere, and its
        `rawText()` *adds* quotes, so if it were ever produced it would break the round-trip
        invariant the whole token layer rests on. It is a stub for
        [09](./09-deferred-spec-gaps.md)'s §3.4a; delete it and let 09 introduce a correct one.
      - `GToken.toSeq()` (`GTokens.kt:7`, `:11`) — a `Sequence`-returning API in the layer whose
        style rules exclude sequences, referenced only by `GTokensTest.kt:376-383`. Delete both.
      - **`code/core/GDescription.kt` entirely** (`GDescriptor`, `GDField`, `GDIntField`,
        `GDDecimalField`, `GDFlagField`, `GDStringField`), and `GDescriptionTest.kt` with it. Zero
        production consumers; its test documents it as consumed from Java by `GAwareDecoder`, a class
        that no longer exists. The live registry `MarlinCommands.Info(code, className, letters,
        bareString)` (`MarlinRQ.kt:641`) carries the same information *including* `bareString`, which
        is exactly `GDescriptor.strTail`. Two registries describing one thing, one of them dead.

        **In the same commit**, repoint [09](./09-deferred-spec-gaps.md)'s citation of
        `GDescriptor.strTail` at `MarlinCommands.Info.bareString`, or the evidence behind that
        deferral is lost.

        Bonus: this removes `GDDecimalField.default`, one of the **two** `BigDecimal` liabilities
        [Appendix B](../specs/GCODE_spec.md) names, leaving `GNumber` as the only one — which makes
        [02](./02-number-representation.md)'s recorded decision cleaner rather than dirtier.
- [ ] **State reframe-vs-resend in the types.** `GMalformedChecksum` (`GSemantics.kt:159`) carries a
      non-null `number: GInt` but does not implement `GOrdered`, while `GCheckSumFailedLine` (`:177`)
      does. A consumer that switches on `GOrdered` to answer "resend line n" therefore sees the
      mismatch case and silently misses the garbled-field case, though
      [§8.5](../specs/GCODE_spec.md#85-resend) gives both the same answer. `GMissingChecksum` (`:133`)
      has the same problem, and its `number: GInt?` is nullable only because the constructor allows
      it — `GSemanticParser.kt:93` only ever passes a non-null value.

      Make both implement `GOrdered`, tighten `GMissingChecksum.number` to `GInt` (the `?: "?"` in
      its `toString` goes with it), and write the partition into `GError`'s KDoc at `:116`:

      - **addressable — answer `Resend: n`**: `GCheckSumFailedLine`, `GMalformedChecksum`,
        `GMissingChecksum`;
      - **not addressable — the line carries no usable number, resynchronise with `M110`**:
        `GMissingLineNumber`, `GMalformedLineNumber`, `GNotIdentifierError`.

      `GCodeReader.read` already gets this right by mapping all six onto `GRejected` from session
      state, so nothing changes there — but its branch chain is `if`-based and order-dependent, so
      check that `GMalformedChecksum` is still tested before `GOrdered`.

## Out of scope

- The `GSemanticParser` allocation rework — four passes and roughly 80–100 allocations per
  30-character line, the `ArrayDeque` allocated per line and never pushed to, the `mutableListOf` per
  identifier word even when the word has no spaces, the `Sequence → toList` round-trip. All real, but
  that file is the most bug-prone in the module (`GSemanticParser.kt:52` records TODO 1.8 having been
  fixed "twice now") and there is no production workload to measure against until
  [15](./15-terminal-adopts-gcode.md) lands. Do it after, one commit per change.
- Making `decodeParams` O(params) instead of O(fields × params) by filling a 26-slot array indexed by
  `letter - 'A'`. That changes `tools/marlin/gen_mcommands.py`, not the 295 generated files, and the
  `isLetter` fix above removes most of the cost for a fraction of the risk.

## Verify

- [ ] The new end-to-end test is green, and was red for the documented reason first.
- [ ] `./gradlew :gcode:test` — 797 plus the new cases, 0 failures. Any test that has to be *edited*
      rather than added means a behaviour changed that this note did not intend.
- [ ] `./gradlew build` green across every module. `GSender` and `PrinterController` build words
      through the DSL and are the call sites that would notice an equality change.
- [ ] Before landing: a `protocol-reviewer` pass over `GSemanticsTest` (26 tests) asking whether
      anything asserts word equality *expecting* `raw` to participate. Reading found none, but that
      was one reading.
