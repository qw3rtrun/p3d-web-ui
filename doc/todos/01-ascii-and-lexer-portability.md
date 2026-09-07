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

- [x] Replace the three predicates with private helpers on explicit ranges. Six lines, no locale,
      identical in all four target languages:

      ```kotlin
      private fun isDigit(c: Char) = c >= '0' && c <= '9'
      private fun isUpper(c: Char) = c >= 'A' && c <= 'Z'
      private fun isLower(c: Char) = c >= 'a' && c <= 'z'
      private fun isLetter(c: Char) = isUpper(c) || isLower(c)
      ```

      Call sites: `GTokenizer.kt:77`, `:78`, `:79`, `:140`, `:148`, `:213`. `:213` was `ident()`,
      whose whole body was the predicate; with the class narrowed it reduced to `GLetter(current)`
      plus the lookahead clear, so it is inlined into the `next()` dispatch and the method is gone.
      It had no callers outside the file.
- [x] Whitespace needs a decision, not just a range. Today `isWhitespace()` reaches `space()`, which
      only handles `' '`, `'\t'`, `'\n'`, `'\r'` and returns `GUnknown` for anything else — so the
      predicate is already wider than the handler. Make the predicate exactly the four characters
      `space()` handles and let everything else fall through to `GUnknown` at the top level. That is
      behaviour-preserving *and* removes the dead `else` branch inside `space()`.
- [x] A non-ASCII character outside a comment or a string becomes `GUnknown`, carrying the character
      so `rawText()` still round-trips. Confirm `GЯ1` → `[GLetter(G), GUnknown(Я), GInt(1)]`.
- [x] Drop `fun parse(gcode: Stream<Char>)` and the `kotlin.streams` imports, or move them to a
      JVM-only adapter file next to the core. Prefer dropping: nothing in the repo calls it, and the
      `Iterable`/`Sequence`/`CharSequence` overloads already cover every caller.
- [x] **Stray CR in tail comments.** `tailComment()` stops only at `'\n'`, so on CRLF input the `'\r'`
      lands inside the comment text (`;ab\r\n` → `GTailComment("ab\r")`). The text round-trips, so
      this is a content bug, not a fidelity bug. Decide: either stop at `'\r'` as well and let
      `space()` produce the `\r\n` break — which changes `GTailComment.string` and needs the
      round-trip re-checked, since the CR must still be emitted by *something* — or document that
      comment text is raw-to-end-of-line and leave it. Write the decision down either way.

      **Decided: stop at `'\r'`.** [§1.2](../specs/GCODE_spec.md#12-end-of-line) makes both
      characters of a CRLF part of the terminator, so neither is comment content, and `tailComment()`
      now agrees with `space()` on which characters end a line instead of recognising a smaller set.
      `space()` turns the CR it stopped on into `GLineBreak("\r\n")`, or into `GUnknown` when it is a
      lone CR — the same rule that already applied outside a comment — so the byte is still emitted
      and `;ab\r\n` round-trips. Consequence worth knowing: a lone CR *inside* a comment now ends the
      comment text and the rest of the line is lexed as code. That input is already malformed under
      §1.2 and it does not occur in the corpus; the alternative would need two characters of
      lookahead in `tailComment()`, which the one-slot rule rules out.

      This changed `GTailComment.string` for all 125 tail comments of the CRLF fixture. `no line
      break is counted twice` still passes (one `GLineBreak("\r\n")` replaces one `GLineBreak("\n")`,
      so the count is still 414) — verified by running, not by reasoning. The coverage hole this item
      sat in is closed by two new `GCorpusTest` cases: `no comment text carries a stray carriage
      return` and `the whole corpus round trips byte for byte including its terminators`, the latter
      because the pre-existing round-trip test splits with `corpus.lines()` and so never saw a CR.

## Verify

- [x] `GЯ1`, `G1 X1`, tabs, CRLF and the whole `marlin.gcode` corpus behave as before, except for the
      non-ASCII reclassification. The corpus round-trip must stay `true` and the unknown-character
      set must not grow beyond gaining the non-ASCII cases.
- [x] `GCorpusTest.only the documented lexer gaps produce unknown tokens` — update the expected set
      and its comment if the non-ASCII change adds entries. The set did not change: it is all ASCII
      and stayed `["!", "#", "'", ",", ".", "/", ":", "\\", "|", "~"]`.

      Correction to this item as written: the corpus has **no Cyrillic** and no non-ASCII quoted
      string. Its only non-ASCII is in tail comments — `’` (U+2019) on line 140 and `µ` (U+00B5) on
      lines 380-382 — which is still enough to prove the change is scoped, and is now pinned by
      `the corpus still carries the non ascii characters of its comments`. The quoted-string half of
      the [§1.1](../specs/GCODE_spec.md#11-character-set-and-encoding) carve-out has no corpus
      coverage at all, so it is pinned in `GTokenizerTest` instead. Scoping is also asserted from the
      other side, by `no token outside a comment or a string carries a non ascii character`.
- [x] The skill's checklist grep comes back clean for this file:

      ```bash
      grep -rn 'isDigit()\|isLetter()\|isWhitespace()\|java\.util\.stream\|kotlin\.streams' \
        gcode/src/main/kotlin/org/qw3rtrun/p3d/g/code/core/
      ```

## Notes

After this file, the only non-`kotlin.*` import left in the core is `java.math.BigDecimal`, which is
[02](./02-number-representation.md).

Two things a follow-up should know.

**The digit leak was two-sited.** `text.toIntOrNull()` (`GTokenizer.kt`, the integer branch of
`number()`) and `BigDecimal(text)` (the decimal branch) both accept the whole Unicode `Nd` category:
`"١".toIntOrNull()` is `1` and `BigDecimal("١.٢")` is `1.2`. Narrowing `isDigit` closes the tokenizer
path by construction — a non-ASCII digit never reaches either call — so no second fix was needed, but
the assumption is now load-bearing and there is a comment at the `toIntOrNull` call saying so. 02
must not relax it.

**The helpers are file-private, as specified above.** [06](./06-decoder-edge-portability.md) (its
lines 33-35) wants the same predicates at the decoder edge. They are `private` top-level in
`GTokenizer.kt`, so 06 has to either promote them to `internal` or write its own four lines; that is
06's call to make, not this file's.

Original findings: [1.18](./99-completed.md#118-minor--decide-and-document) and the ASCII/stream
entries in [99-completed.md](./99-completed.md).
