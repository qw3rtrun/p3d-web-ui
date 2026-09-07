# 01 — ASCII character classes and lexer portability

**Goal.** The lexer decides what a letter, a digit and a space are using explicit ASCII comparison,
and `code/core/token/GTokenizer.kt` stops pulling `java.util.stream` into the portable core.

**Depends on:** nothing. **Blocks:** nothing. Small, self-contained, and worth doing first so later
work is written against ASCII-explicit code instead of copying the Unicode predicates.

## Why

`GTokenizer` classifies characters with `Char.isWhitespace()`, `isLetter()` and `isDigit()`, which
accept whole Unicode categories. The wire format is 7-bit ASCII
([spec §1.1](../specs/GCODE_spec.md#11-character-set-and-encoding)), which confines non-ASCII to
comments and quoted strings. The gap is observable:

```
G<CYRILLIC Я>1   →   [GLetter(G), GLetter(Я), GInt(1)]
```

`Я` is lexed as a command letter. Beyond being wrong, these predicates are the kind of hidden
abstraction the module's style rules exist to keep out: they decide something a wire protocol has to
state, and they have no equivalent in JS/TS, C or Rust — a port would have to guess.

The `Stream<Char>` overload is the same argument with less nuance: it pulls `java.util.stream` and
`kotlin.streams.*` into the core for one overload that nothing calls.

## Do

- [ ] Replace the three predicates with private helpers on explicit ranges. Six lines, no locale,
      identical in all four target languages:

      ```kotlin
      private fun isDigit(c: Char) = c >= '0' && c <= '9'
      private fun isUpper(c: Char) = c >= 'A' && c <= 'Z'
      private fun isLower(c: Char) = c >= 'a' && c <= 'z'
      private fun isLetter(c: Char) = isUpper(c) || isLower(c)
      ```

      Call sites: `GTokenizer.kt:77`, `:78`, `:79`, `:140`, `:148`, `:213`.
- [ ] Whitespace needs a decision, not just a range. Today `isWhitespace()` reaches `space()`, which
      only handles `' '`, `'\t'`, `'\n'`, `'\r'` and returns `GUnknown` for anything else — so the
      predicate is already wider than the handler. Make the predicate exactly the four characters
      `space()` handles and let everything else fall through to `GUnknown` at the top level. That is
      behaviour-preserving *and* removes the dead `else` branch inside `space()`.
- [ ] A non-ASCII character outside a comment or a string becomes `GUnknown`, carrying the character
      so `rawText()` still round-trips. Confirm `GЯ1` → `[GLetter(G), GUnknown(Я), GInt(1)]`.
- [ ] Drop `fun parse(gcode: Stream<Char>)` and the `kotlin.streams` imports, or move them to a
      JVM-only adapter file next to the core. Prefer dropping: nothing in the repo calls it, and the
      `Iterable`/`Sequence`/`CharSequence` overloads already cover every caller.
- [ ] **Stray CR in tail comments.** `tailComment()` stops only at `'\n'`, so on CRLF input the `'\r'`
      lands inside the comment text (`;ab\r\n` → `GTailComment("ab\r")`). The text round-trips, so
      this is a content bug, not a fidelity bug. Decide: either stop at `'\r'` as well and let
      `space()` produce the `\r\n` break — which changes `GTailComment.string` and needs the
      round-trip re-checked, since the CR must still be emitted by *something* — or document that
      comment text is raw-to-end-of-line and leave it. Write the decision down either way.

## Verify

- [ ] `GЯ1`, `G1 X1`, tabs, CRLF and the whole `marlin.gcode` corpus behave as before, except for the
      non-ASCII reclassification. The corpus round-trip must stay `true` and the unknown-character
      set must not grow beyond gaining the non-ASCII cases.
- [ ] `GCorpusTest.only the documented lexer gaps produce unknown tokens` — update the expected set
      and its comment if the non-ASCII change adds entries. The corpus has Cyrillic in comments and
      strings, which must **not** be affected: that is the test that proves the change is scoped.
- [ ] The skill's checklist grep comes back clean for this file:

      ```bash
      grep -rn 'isDigit()\|isLetter()\|isWhitespace()\|java\.util\.stream\|kotlin\.streams' \
        gcode/src/main/kotlin/org/qw3rtrun/p3d/g/code/core/
      ```

## Notes

After this file, the only non-`kotlin.*` import left in the core is `java.math.BigDecimal`, which is
[02](./02-number-representation.md).

Original findings: [1.18](./99-completed.md#118-minor--decide-and-document) and the ASCII/stream
entries in [99-completed.md](./99-completed.md).
