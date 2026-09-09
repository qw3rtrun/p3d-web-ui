# 07 — Hygiene and naming

**Goal.** Names say what things are, and the leftovers of the Java→Kotlin migration are gone.

**Depends on:** nothing. **Blocks:** nothing.

Small, mechanical, and each item is independent. Best used as filler *between* the larger files —
especially the file rename, which would muddy any behavioural diff it landed inside.

## Do

- [x] **`code/core/token/GLiner.kt` is gone.** It held `GLineIterator` and `GCommandParser` and
      nothing called `GLiner`. Resolved by both routes at once: `GCommandParser` moved to its own
      file (it has a real job now, per [03](./03-word-and-command-layer.md)), and `GLineIterator` was
      **merged into `GSemanticParser`**, which is now a single `Iterator<GLine>` doing grouping and
      classification. That also removed the `GSemanticParser()` allocated per line. Call sites
      renamed (34) and `GLineIteratorTest.kt` → `GSemanticParserTest.kt`.
- [x] **`GParameter` and `GFlag` deleted.** Each had exactly one implementation and was named nowhere
      else, so `GParameterWord` and `GFlagWord` now implement `GWord` directly, in `GSemantics.kt`
      beside it. This also removed the `sealed class` / `sealed interface` split between the two, and
      the `out` variance on `GParameterWord` — the annotation the style rules forbid outright — which
      turned out to be unnecessary: the module compiles without it.
- [ ] **Drop the `GLineBreak.toString()` override** (`GTokens.kt`). It hides the data-class output —
      `GLineBreak("\r\n").toString()` prints `"GLineBreak"` — which is exactly the information you
      want in a failing assertion. Removing it may make some test failure messages longer and all of
      them more useful.
- [ ] **Make `GTokenizer` an `object` or top-level functions.** It is stateless; every instance is
      interchangeable. All the state lives in `GTokenizerIterator`, which is correct as a class.
      Touches every test's `private val tokenizer = GTokenizer()`, so it is mechanical but wide.
- [ ] **Drop the stray `;` line terminators** left from the migration. Down to three after the
      tokenizer rewrites: `GTokenizer.kt:85` and `GTokens.kt:56,60`. Re-grep rather than trusting
      those line numbers:

      ```bash
      grep -rn ';$' gcode/src/main/kotlin/org/qw3rtrun/p3d/g/code/core/
      ```

## Already done — do not redo

Recorded here because the original hygiene list mixed done and undone items:

- [x] `GLineIterator.tokens` is private (a public, already-consumed iterator is a trap).
- [x] `GNumber`'s KDoc documents that `GFloat` equality is scale-sensitive
      (`GFloat("1.0") != GFloat("1.00")`) — correct for round-trip fidelity, surprising for value
      comparison, so it is written down rather than fixed.
- [x] The `kotlin.math.min` import in `GLiner.kt` is gone; it went with the `parsePacket` rewrite.
      (An earlier version of this list wrongly called it unused — it was in use until then.)

## Moved elsewhere

Two items that used to live on the hygiene list belong with the work that changes the same types, so
that each public shape changes once:

- **Rename `GFloat.float`** → [02-number-representation.md](./02-number-representation.md).
- **Tighten `GCommand.head` to `GLetter`** → [03-word-and-command-layer.md](./03-word-and-command-layer.md).

## Verify

- [ ] `./gradlew build` green. That is the whole bar — none of these should change behaviour, and any
      test that needs editing beyond a mechanical rename means an item was not as cosmetic as it
      looked.
