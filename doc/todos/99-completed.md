# 99 — Completed work: the `:gcode` review record

**This file is not a to-do list.** It is the record of the review that produced
[the numbered files in this directory](./00-index.md), and of the three passes that have been
carried out since. Keep it because the *why* behind the current shape of the module lives here:
every finding was verified by running the module, and the verbatim probe output is preserved so a
later reader can tell what changed and what merely looks changed.

**Scope:** `gcode/src/main/kotlin/org/qw3rtrun/p3d/g/code/**` reviewed against
[`GCODE_spec.md`](../specs/GCODE_spec.md).
**Review date:** 2026-09-07 · **Base:** `90b19aa` (*gcode - fixed bugs part 2*), plus Commits A and B
described below.
**Method:** every finding was verified by compiling and running the module (`:gcode:test`) plus a
temporary probe test, not by reading alone. Probe outputs are quoted verbatim.

Two findings in [section 1](#1-confirmed-bugs) are still **open** and have moved to their own files:
[1.10](#110-gcommandprint-can-emit-invalid-g-code--gsemanticskt38-44) →
[04-encoder-and-checksum.md](./04-encoder-and-checksum.md), and
[1.18](#118-minor--decide-and-document) → [01-ascii-and-lexer-portability.md](./01-ascii-and-lexer-portability.md).
They are left in place here so the numbering of the original review stays intact.

---

## 0. Build / test status

`:gcode:compileTestKotlin` and `:gcode:test` are **green** — **398 tests**, 0 failures, 0 skipped
(37 at the time of the review; see [§3](#3-test-coverage) for the breakdown of the test pass).
`./gradlew build` is green across every module.

**Fixed so far:** [1.6](#16-debug-println-in-the-hot-path--gtokenizerkt90---fixed),
[1.12](#112-crlf-yields-two-line-breaks--gtokenizerkt52-58---fixed),
[1.13](#113-leading-dot-decimals-duplicate-a-digit--gtokenizerkt64-94---fixed),
[1.14](#114-malformed-decimals-throw-out-of-the-lexer--gtokenizerkt74-91---fixed) — the three
lookahead/number bugs in `GTokenizer`, TDD: 22 tests written red first, then the fix.

Then the **number lexeme pass**: [1.1](#11-negative-numbers-do-not-lex--gtokenizerkt27-45---fixed),
[1.4](#14-doubletotoken-destroys-precision--gtokenskt90-gtokenskt108---fixed),
[1.15](#115-tabs-are-unknown-tokens-and-gtab-is-dead--gtokenizerkt48-62---fixed),
[1.16](#116-number-lexemes-are-normalised-on-render--gtokenizerkt64-94-gtokenskt80-96---fixed) and the
open remainder of 1.14, again TDD: 31 tests red first, then the fix. `rawText()` now round-trips the
whole corpus byte for byte, so both characterisation points of [§3](#3-test-coverage) are retired.

Then **Commit A, the `GTokenizer` delimiter pass**:
[1.2](#12-inline-comments-corrupt-on-round-trip--gtokenizerkt147-160---fixed),
[1.7](#17-unterminated-literals-are-lossy-and-silent--gtokenizerkt96-114-gtokenizerkt147-160---fixed),
[1.5](#15-parselines-drops-line-terminators--gtokenizerkt16---fixed),
[1.11](#111-parse-returns-a-constrain-once-sequence--gtokenizerkt11-13---fixed) and the tokenizer half
of [1.17](#117-iterators-do-not-guard-next--gtokenizerkt26-glinerkt25---fixed). TDD: 41 tests
written first, 35 of them red for behavioural reasons, then the fix. The tokenizer is now clear of the
whole lookahead family — **no scanner writes `ch` inside a loop** — and `GTokenizer.kt` is the only
file `GCODE_spec.md` §2 depends on, so lexing is done bar the two §5 portability items.

Then **Commit B, the `GLiner` framing pass** — the current one:
[1.3](#13-gpacketlinetail-is-off-by-one-and-can-crash--glinerkt51-glinerkt56---fixed),
[1.8](#18-packet-detection-is-case--and-position-exact--glinerkt34--fixed),
[1.9](#19-gemptyline-is-unreachable--gsemanticskt19-21-glinerkt37--fixed), the liner half of
[1.17](#117-iterators-do-not-guard-next--gtokenizerkt26-glinerkt25---fixed), and the sentinel
removal of [§4.3](#43-priority-3--model-errors-instead-of-sentinels---done) including the
[§7.3](../specs/GCODE_spec.md#73-pairing-rule) pairing errors. TDD: 38 tests red first, then the fix.
`parsePacket` is gone — every boundary is now derived from element positions, the terminator is
removed by testing for it, and **no input makes the liner throw**.

**Every confirmed bug in [§1](#1-confirmed-bugs) is now fixed except
[1.10](#110-gcommandprint-can-emit-invalid-g-code--gsemanticskt38-44) and
[1.18](#118-minor--decide-and-document)**, both of which are about the semantic layer rather than
lexing or framing, and no test freezes a bug any more. Next step: [§0.1](./00-index.md).

- [ ] **Refresh `.junie/GCODEK.md`** — §1 "Current Status & Blocker Analysis" is stale. It claims
  `:gcode:compileKotlin` fails with unresolved `GCommand` / `GCommandLine` and leaves Phase 1
  unchecked; `GSemantics.kt` now defines both and Phase 1 is effectively complete. *(re-probed)
  Still stale at `90b19aa`: `GCODEK.md:26` states the module fails to compile, `:249` sets Phase 1's
  goal as resolving those errors, and `:361-362` leaves the phase unchecked, while `:gcode:test`
  builds and passes.*

---


---

## 1. Confirmed bugs

Ordered by impact. Every symptom is an observed run result.

> Line numbers in the **headings** are as of the original review and have drifted with every fix;
> they are kept because the whole document anchors to them. Current locations in `GTokenizer.kt`
> after Commit A: `parse` overloads **10-18**, `parseLines` **28**, `GLineCharIterator` **36-63**,
> `GTokenizerIterator.next()` **71**, `space()` **97**, `number()` **128**, `string()` **177**,
> `ident()` **211**, `expression()` **220**, `tailComment()` **244**, `inlineComment()` **269**.
> Current locations in `GLiner.kt` after Commit B: `GLineIterator` **10**, `next()` **14**,
> `nextLine()` **22**, `stripTerminator()` **37**, `elementIndices()` **41**, `isLetter()` **51**,
> `checksumAt()` **55**, `numberAfter()` **61**, `parseLine()` **67**, `GCommandParser` **99**.
> In `GSemantics.kt`: `GEmptyLine` **23**, `GPacketLine` **29**, `GCommand.print()` **42**, and the
> four structural errors at **69**, **75**, **81**, **87**.

### 1.1 Negative numbers do not lex — `GTokenizer.kt:27-45` — ✅ FIXED

*Fixed together with [1.16](#116-number-lexemes-are-normalised-on-render--gtokenizerkt64-94-gtokenskt80-96---fixed):
`number()` consumes an optional leading `[+|-]` and hands the whole lexeme to the token. A sign that
is not followed by a digit or a `.` stays a `GUnknown` and does not swallow the next character.
`+5` is **preserved**, not normalised — `GInt(5, "+5").rawText() == "+5"`. Covered by
`GTokenizerTest.Numbers`: `a negative integer includes its sign`, `a negative real includes its sign`,
`a plus sign is kept in the lexeme`, `a signed number stops at the next word`,
`a sign that does not introduce a number is unknown`, `a sign with a dot but no digit is unknown`,
`a signed number with two decimal points is unknown`, `signed numbers round trip`, plus 3 round-trip
cases and `GCorpusTest.only the documented lexer gaps produce unknown tokens`, from which `-` has
disappeared.*

`-` and `+` fall through the `when` to `GUnknown`:

```
G1 E-5 F1800
→ [GLetter(G), GInt(1), GSpace, GLetter(E), GUnknown(-), GInt(5), GSpace, GLetter(F), GInt(1800)]
```

Spec [§3.1](../specs/GCODE_spec.md#31-numeric-values) requires the optional sign to be part of the number.
Every retraction and relative move in real slicer output is affected — including the fixture already
in the repo (`GTokenizerTest.kt:20`, `G1 E-5 F1800`).

- [x] Lex `[+|-]` as part of `GNumber`. `rawText()` preserves `+5` verbatim — see 1.16 for why the
      lexeme, not the value, decides how a number re-prints.

### 1.2 Inline comments corrupt on round-trip — `GTokenizer.kt:147-160` — ✅ FIXED

*Fixed together with [1.7](#17-unterminated-literals-are-lossy-and-silent--gtokenizerkt96-114-gtokenizerkt147-160---fixed):
`inlineComment()` now keeps two buffers — `raw` for the lexeme and `text` for the content — and
appends to `text` only while `depth > 0`, so the closing paren that drops the depth to zero is never
content. `G1 (feedrate) F1500` round-trips. Covered by `GTokenizerTest.InlineComments`:
`the delimiters frame the comment and are not part of its text`, `an empty inline comment has empty
text`, `nested parentheses are part of the comment text`, `an inline comment between two words round
trips`, plus 4 round-trip cases; and `GLineIteratorTest.comment lines and inline comments are parsed
as simple lines`, whose frozen `GInlineComment("feedrate)")` is now `GInlineComment("feedrate")`.*

`comment.append(ch)` runs *before* `)` decrements `stack`, so the closing paren ends up inside
`GInlineComment.string`, and `rawText()` (`GTokens.kt:46`) appends another one:

```
G1 (feedrate) F1500   →   G1 (feedrate)) F1500      (round-trip == false)
```

Note the inconsistency with `expression()` (`GTokenizer.kt:121-134`), which deliberately *includes*
its `{` `}` delimiters in the token text — which is why `{}` round-trips correctly and `()` does not.

- [x] Don't append the closing delimiter to `GInlineComment.string` (or store delimiters uniformly
      across both token types and make `rawText()` match). *Chose the first: `GInlineComment` keeps
      its `start`/`end` fields and `string` is now just the content.*

### 1.3 `GPacketLine.tail` is off by one and can crash — `GLiner.kt:51`, `GLiner.kt:56` — ✅ FIXED

*Fixed: `parsePacket` is gone; `parseLine` derives every boundary from element positions instead of
arithmetic on `size`. The tail starts after the checksum **value**, and the terminator is removed by
`stripTerminator`, which tests `last() is GLineBreak` rather than indexing to `size - 1`. No input
throws any more — the whole `BufferBoundaries` group is a `no input makes the liner throw`
parameterised sweep over 26 boundary cases including `N*`, `N1*`, `N1 G1*`, unterminated lines,
terminator-only lines and unterminated literals. Tail contents are asserted with and without a
terminator, and for CRLF.*

```kotlin
val tail = tokens.subList(indexOfCheckSum + 1, tokens.size - 1)
```

Two defects:

* it starts **at** the checksum value, so `tail` re-contains it —
  `N100 G1 X10 *45\n` → `tail = [GInt(45)]`;
* `size - 1` assumes a trailing `GLineBreak`. Without one it drops a real token —
  `N1 G28*12 ;c` → `tail = [GInt(12), GSpace]`, the comment is lost.

The `else` branch (`GLiner.kt:56`) has the same assumption and throws:

```
N*     →   EX: java.lang.IllegalArgumentException: fromIndex(2) > toIndex(1)
```

*(re-probed) The crash is wider than the `N*` sentinel line: **any** packet line whose `*` is the last
token throws, through the `if` branch at `GLiner.kt:51`, not the `else`:*

```
N1*        →   EX: java.lang.IllegalArgumentException: fromIndex(3) > toIndex(2)
N1 G1*     →   EX: java.lang.IllegalArgumentException: fromIndex(6) > toIndex(5)
```

*That is the unterminated-last-line case on a serial read and the split-mid-frame case on a socket
read — both routine, both currently an exception out of `GLineIterator.next()`. `N1 G28*12` (no
break, checksum present) does not throw only by accident: `subList(6, 6)` happens to be empty, so
that one line gets the right `tail` for the wrong reason.*

- [x] Start the tail at `indexOfCheckSum + 2`; strip a trailing `GLineBreak` explicitly rather than
      by index arithmetic; guard the short-line cases so no input can throw.
- [x] Name each buffer-boundary case as its own test — empty, no terminator, terminator only, `*`
      last, `N` last, split mid-token — per the skill's framing rule.

### 1.4 `Double.toToken()` destroys precision — `GTokens.kt:90`, `GTokens.kt:108` — ✅ FIXED

*Fixed: the `Double` constructor uses `BigDecimal.valueOf`, which goes through
`Double.toString()` and so yields the shortest decimal that round-trips. Covered by
`GTokensTest.float from a double does not expand the binary representation`.*

*Follow-up, planned in [§4.4](./00-index.md): **delete the `Double` path
altogether**. `valueOf` fixed how a `Double` renders, not the fact that a `Double` has no authored
lexeme to render — the hazard class stays as long as the constructor does.*

`GFloat(float: Double) : this(BigDecimal(float))` uses the exact binary expansion:

```
1.05.toToken().rawText()               → 1.0500000000000000444089209850062616169452667236328125
GFloat(BigDecimal.valueOf(1.05))       → 1.05
```

Any line generated through the `Double` path is unusable.

- [x] Use `BigDecimal.valueOf(double)` (or `double.toString()`) in the `Double` constructor.

### 1.5 `parseLines` drops line terminators — `GTokenizer.kt:16` — ✅ FIXED

*Fixed: `parseLines(gcode, terminator = "\n")` feeds the tokenizer through a new
`GLineCharIterator` (`GTokenizer.kt:36-63`) that re-attaches the terminator to every line except the
last. **The caller owns the terminator** — that was the open question — because a `Sequence<String>`
from `readLines()` no longer carries it, so a file read on Windows and one read on Linux arrive
identically. Covered by `GTokenizerTest.ParseLines`, 8 tests: terminator re-inserted between lines,
lines stay separate token runs, none appended after the last, empty sequence, blank lines preserved,
caller-chosen `\r\n`, CRLF still one break token, and the line count surviving a `GLineIterator`
round trip.*

`flatMap { it.asSequence() }` concatenates the strings without re-inserting `\n`:

```
["G28", "M104 S200"]   →   G28M104 S200
```

Anything fed from `BufferedReader.lineSequence()` / `File.readLines()` silently merges commands into
one line.

- [x] Re-insert the terminator between elements (and decide whether the caller or the tokenizer owns
      the choice of `\n` vs `\r\n`). *Decided: the caller, via a defaulted `terminator` parameter.*

### 1.6 Debug `println` in the hot path — `GTokenizer.kt:90` — ✅ FIXED

`println(str)` inside `number()` fired for every numeric token and polluted test stdout.

- [x] Deleted along with the rest of the `number()` rewrite for 1.13/1.14.

### 1.7 Unterminated literals are lossy and silent — `GTokenizer.kt:96-114`, `GTokenizer.kt:147-160` — ✅ FIXED

*Fixed: `string()` and `inlineComment()` each track the lexeme alongside the decoded content and
return `GUnknown(<full lexeme, opening delimiter included>)` when the literal never closes.
`GUnknown` is already this module's lexical-error variant — `expression()` used it for the same case
and [1.14](#114-malformed-decimals-throw-out-of-the-lexer--gtokenizerkt74-91---fixed) for malformed
numbers — so no new token kind was needed, and the skill's* errors are values *rule holds: the token
carries the offending bytes and `rawText()` reproduces them.*

*`string()` also stopped conflating two cases: it now records whether a closing quote was actually
seen, so `M"a"` is a `GQuotedString` and `M"a` is a `GUnknown`, where both used to yield
`GQuotedString("a")`. The lookahead half of the bug went with the `inlineComment()` rewrite.*

*Covered by the new `GTokenizerTest.UnterminatedLiterals` group, 9 tests — opening paren kept, last
character not repeated, nested and one-level-short comments, opening quote kept, lone quote, doubled
quotes kept verbatim, opening brace kept, and 9 parameterised round-trip cases — plus
`QuotedStrings.a string closed by the last character of the input is terminated`.*

```
M"asd   →   M"asd"     (gains a closing quote)
M(abc   →   Mabcc      (loses the opening paren; GUnknown keeps only the body)
```

Spec [§9](../specs/GCODE_spec.md#9-error-handling) classifies both as lexical errors. At minimum the
`rawText()` fidelity invariant must hold.

*(re-probed) The `(` case is two defects, not one, and the second is the **lookahead bug of
[1.12](#112-crlf-yields-two-line-breaks--gtokenizerkt52-58---fixed)/[1.13](#113-leading-dot-decimals-duplicate-a-digit--gtokenizerkt64-94---fixed)
in its last unfixed home.* `inlineComment()` assigns `ch` **inside** its scanning loop
(`GTokenizer.kt:175`) and only clears it on the `)` exit (`GTokenizer.kt:178`), so on the unterminated
path the final character stays in the lookahead and is emitted a second time:

```
M(abc   →   [GLetter(M), GUnknown(abc), GLetter(c)]      ← the trailing 'c' is a duplicate
(a(b    →   [GUnknown(a(b), GLetter(b)]                  ← same, and nesting is lost too
```

`expression()` has the identical loop shape but clears `ch` unconditionally on the way out
(`GTokenizer.kt:152`), which is why it is clean and round-trips:

```
M{abc   →   [GLetter(M), GUnknown({abc)]                 →  reprint M{abc  ✓
```

So the fix is the skill's rule applied once more — a local `current`, `ch` written exactly once on
exit — and it makes `inlineComment()` and `expression()` the same shape.

`string()` is the third scanner and is lossy in the other direction: it drops the delimiters
entirely, so both `M"a"` and `M"a` yield `GQuotedString("a")` and the unterminated one re-prints with
a quote it never had. A bare `"` at end of input yields `GQuotedString("")`.

- [x] Preserve the original text in the degraded token, and surface the condition (dedicated token
      kind or diagnostic) instead of silently repairing it. *Surfaced as `GUnknown`, the existing
      lexical-error variant, rather than a new token kind.*
- [x] Fix the `inlineComment()` lookahead in the same pass as [1.2](#12-inline-comments-corrupt-on-round-trip--gtokenizerkt147-160---fixed) —
      it is the same six lines, and 1.2 is the terminated half of this bug.
- [x] *Follow-on, same commit:* `expression()` and `tailComment()` were rewritten into the same shape
      even though both were already correct, so that **no scanner in the file writes `ch` inside a
      loop**. That invariant — not any single fix — is what closes the 1.12/1.13/1.14/1.7 family.
      Behaviour is unchanged for both, including the stray CR of [1.18](#118-minor--decide-and-document).

### 1.8 Packet detection is case- and position-exact — `GLiner.kt:34` — FIXED

*Fixed: the line is classified from its **elements** - the tokens that are neither separators nor
comments - so leading whitespace cannot change the answer, and the letter is matched with an explicit
ASCII comparison (`letter == 'N' || letter == 'n'`). Not `equals(ignoreCase = true)`: the skill rules
it out as locale-dependent, and `GCommandParser.isCommand` was using it, so that was fixed in the same
pass. Covered by `GLineIteratorTest.PacketDetection`, 5 parameterised cases plus the first-element
rule and whitespace between `N` and its number.*

`isPacket` requires `tokens[0] == GLetter('N')`:

```
n1 g28*12      → GSimpleLine
" N1 G28*12"   → GSimpleLine
```

Spec [§2.2](../specs/GCODE_spec.md#22-case) — RS274/NGC and RepRapFirmware ≥ 1.19 are case-insensitive,
Marlin optionally so; leading whitespace is a separator ([§2.1](../specs/GCODE_spec.md#21-whitespace)).

- [x] Skip leading `GWhitespace` and compare the letter case-insensitively. *Done by classifying
      from elements rather than from `tokens[0]`, with an explicit ASCII fold.*

### 1.9 `GEmptyLine` is unreachable — `GSemantics.kt:19-21`, `GLiner.kt:37` — FIXED

*Fixed, and the type kept: spec section 5 makes the distinction real. A line whose elements are empty -
nothing but whitespace and/or comments - is a `GEmptyLine`, and it keeps its tokens so the line still
reproduces its input. The dead `tokens.isEmpty()` branch is gone. Covered by
`GLineIteratorTest.EmptyLines`, 11 tests. Note the reclassification: a comment-only line is now
`GEmptyLine`, not `GSimpleLine` - 111 of the 414 corpus lines are empty by this rule.*

`nextLine()` always returns at least one token, so `tokens.isEmpty()` never holds:

```
"\n\n"   →   [GSimpleLine, GSimpleLine]
```

- [x] Either classify a line whose tokens are only separators/comments as `GEmptyLine`, or delete the
      type. (Spec [§5](../specs/GCODE_spec.md#5-line-block-structure) treats such a line as a no-op, so the
      distinction is worth keeping.) *Kept and classified.*

### 1.10 `GCommand.print()` can emit invalid G-code — `GSemantics.kt:38-44`

`rawText()` values are concatenated with no separator, so adjacent numbers fuse:

```
GCommand(GLetter('G'), listOf(GInt(1), GInt(2))).print()   →   "G12"
```

It is also the only encoder in the module — nothing emits `N` / `*` framing
([§7](../specs/GCODE_spec.md#7-line-numbering), [§8](../specs/GCODE_spec.md#8-checksum-and-crc)).

- [ ] Insert a separator when two value tokens would otherwise abut (or always separate words with a
      space), and add a real line encoder — see [§4.4](./00-index.md).

### 1.11 `parse()` returns a constrain-once sequence — `GTokenizer.kt:11-13` — ✅ FIXED

*Fixed: the `Iterable`, `Sequence` and `CharSequence` overloads return `Sequence { GTokenizerIterator(...) }`
(`GTokenizer.kt:15-17`), so re-iterability is inherited from the source instead of being destroyed by
`constrainOnce()`. `parseLines` is built the same way. The bare `Iterator<Char>` overload stays
single-use, which is a property of the source and is asserted as such. Covered by the new
`GTokenizerTest.Reiteration` group, 5 tests.*

`Iterator.asSequence()` is `constrainOnce()`, so a returned `Sequence` can be consumed exactly once:

```kotlin
val t = tokenizer.parse(src)
t.count()      // ok
t.toList()     // IllegalStateException: This sequence can be consumed only once.
```

- [x] Return `Sequence { GTokenizerIterator(...) }` so the result is re-iterable for the
      `CharSequence` / `Iterable<Char>` overloads (a `Sequence<Char>` / `Iterator<Char>` source
      cannot be, and that's fine — document the asymmetry). *A `Sequence<Char>` source turned out to
      be re-iterable whenever the source sequence itself is, so it got the same treatment; only the
      `Iterator<Char>` overload is inherently single-use.*

### 1.12 CRLF yields two line breaks — `GTokenizer.kt:52-58` — ✅ FIXED

*Fixed by clearing the lookahead when the CR/LF pair is consumed. Covered by
`GTokenizerTest.Separators` (6 CRLF/CR cases), 5 CRLF round-trip cases, and
`GCorpusTest.no line break is counted twice`, which asserts one break token per `\n` over the raw
corpus.*

`space('\r')` returns `GLineBreak("\r\n")` but leaves the consumed `'\n'` in the `ch` lookahead
field, so the next call emits a second `GLineBreak("\n")`:

```
marlin.gcode (415 CRLF lines)  →  GLineIterator produces 703 lines
whole-file rawText             →  8745 chars out of 8454 in  (one extra \n per CRLF)
```

Every line is counted twice on Windows-authored files and on serial input that uses CRLF, which also
breaks line numbering and any framing built on it (spec §7). Same root cause as
[1.13](#113-leading-dot-decimals-duplicate-a-digit--gtokenizerkt64-94---fixed): a lookahead that is
not cleared.

- [x] Clear `ch` when returning the `\r\n` break.

### 1.13 Leading-dot decimals duplicate a digit — `GTokenizer.kt:64-94` — ✅ FIXED

*Fixed by clearing the lookahead after the first fractional digit is consumed. Covered by
`GTokenizerTest.Numbers`: `a decimal may start with a dot` and
`a leading dot decimal does not duplicate its first digit`.*

When `number()` starts on `'.'`, it reads the first digit into `ch`, appends it to the buffer and
never clears `ch` — so the digit is emitted a second time as its own token:

```
X.5   →  [GLetter(X), GFloat(0.5), GInt(5)]      reprint: X0.55
```

Real files hit this — `marlin.gcode` lines `G92 .1 ;TODO` and `M851 X0.20 Y.40` are the only two
lines in the corpus that fail round-trip. They still fail, but now only because `GFloat` renders
`.1` back as `0.1` — the lexeme question of [1.16](#116-number-lexemes-are-normalised-on-render--gtokenizerkt64-94-gtokenskt80-96---fixed).

- [x] Clear the lookahead after consuming the first fractional digit.

### 1.14 Malformed decimals throw out of the lexer — `GTokenizer.kt:74-91` — ✅ FIXED

*Fixed: `number()` now counts decimal points and returns the lexeme as a `GUnknown` instead of
handing it to `BigDecimal`. An integer that does not fit an `Int` is treated the same way rather than
being silently truncated by `BigDecimal.toInt()`. Covered by
`a number with two decimal points is an unknown token`,
`a malformed number does not stop the rest of the line`, `a malformed number round trips`,
`an integer too large for Int is an unknown token` and
`the largest representable integer is still a number`.*

A second decimal point is appended to the buffer and passed to `BigDecimal`, which throws:

```
X1.2.3   →  EX: java.lang.NumberFormatException: Character array contains more than one decimal point.
```

Spec [§9](../specs/GCODE_spec.md#9-error-handling) wants a *lexical error* token, not an unhandled
exception escaping the tokenizer.

- [x] Reject a second `.` at lex time (emit an unknown/error token) and never let `BigDecimal` throw
      through the iterator.
- [x] Closed with [1.16](#116-number-lexemes-are-normalised-on-render--gtokenizerkt64-94-gtokenskt80-96---fixed):
      `X1.` keeps the lexeme `1.` and stays a `GFloat`. Spec
      [§3.1](../specs/GCODE_spec.md#31-numeric-values) lists `2.` among the valid real forms, so `GUnknown`
      would have been wrong — the digit count, not the dot position, decides. Covered by
      `a trailing dot is a real, lexeme included`.

### 1.15 Tabs are unknown tokens and `GTab` is dead — `GTokenizer.kt:48-62` — ✅ FIXED

*Fixed: `space()` maps `'\t'` to `GTab`. Covered by `GTokenizerTest.Separators`:
`a tab is a tab token`, `a tab separates two words`, `repeated tabs are separate tokens`,
`tabs and spaces can be mixed`, plus a round-trip case.*

`space()` only handles `' '`, `'\n'` and `'\r'`; everything else falls through to `GUnknown`, so:

```
G1\tX1   →  [GLetter(G), GInt(1), GUnknown(\t), GLetter(X), GInt(1)]
```

`GTab` is therefore unreachable — the second dead token type after `GEmptyLine`
([1.9](#19-gemptyline-is-unreachable--gsemanticskt19-21-glinerkt37--fixed)). Spec
[§2.1](../specs/GCODE_spec.md#21-whitespace) classifies tab as whitespace.

- [x] Map `'\t'` to `GTab`.

### 1.16 Number lexemes are normalised on render — `GTokenizer.kt:64-94`, `GTokens.kt:80-96` — ✅ FIXED

*Decision: **carry the lexeme**. `GNumber` now declares `val lexeme: String` and implements
`rawText()` from it; `GInt(int, lexeme = int.toString())` and
`GFloat(float, lexeme = float.toString())` default it to the canonical form, so every existing call
site — the `G.kt` DSL, `XorCheckSum`, the `GLiner` sentinels — is unchanged. The lexeme is a
constructor parameter of a data class and therefore part of token identity: `GInt(1) != GInt(1, "01")`
and `GFloat("0.5") != GFloat(".5")`, which is the same trade the scale-sensitive `GFloat` equality
already made. Value comparison goes through `int` / `float.compareTo`. Covered by 6 `GTokensTest`
tests and, on the lexer side, `a leading dot decimal keeps its lexeme and its value`,
`leading zeros are kept in the lexeme`, `the lexeme is part of number identity`, plus 6 round-trip
cases and `GCorpusTest.each line round trips`, whose quarantine list is now empty.*

`GInt` holds an `Int` and `GFloat` a `BigDecimal`, so a lexeme that is not in canonical form does not
come back byte-identical:

```
G01    →  [GLetter(G), GInt(1)]        reprint: G1
X.5    →  [GLetter(X), GFloat(0.5)]    reprint: X0.5
X1.    →  [GLetter(X), GFloat(1)]      reprint: X1
```

The values are all numerically right (spec [§3.1](../specs/GCODE_spec.md#31-numeric-values),
[§4.1](../specs/GCODE_spec.md#41-command-letters)) — it is the `rawText()` round-trip invariant that does
not hold. These three lexemes are the only remaining round-trip gap in the corpus.

- [x] Decided: carry the original lexeme in `GInt`/`GFloat`. Round-trip holds unconditionally; the
      equality change is documented on `GNumber`.

### 1.17 Iterators do not guard `next()` — `GTokenizer.kt:26`, `GLiner.kt:25` — ✅ FIXED

*Both halves guarded. `GTokenizerIterator.next()` throws `NoSuchElementException("no more tokens")`
for every source overload (Commit A); `GLineIterator.next()` throws
`NoSuchElementException("no more lines")` before touching the token source (Commit B), so the
tokenizer's own exception can no longer surface from the liner. Covered by
`GTokenizerTest.IteratorContract` (String, List and Sequence sources) and
`GLineIteratorTest.empty tokens iterator has no elements and throws on next` /
`next after the last line throws`.*

Neither `GTokenizerIterator.next()` nor `GLineIterator.nextLine()` checks `hasNext()`; they call
through to the source, so the exception type depends on the input overload — a `String`/`CharSequence`
source raises `StringIndexOutOfBoundsException` where a `List` source raises `NoSuchElementException`.
`Iterator.next()` is specified to throw the latter.

*(re-probed) Observed, on empty input:*

```
GTokenizer.parse("".iterator()).next()          →  StringIndexOutOfBoundsException: Index 0 out of bounds for length 0
GTokenizer.parse(emptyList<Char>()).next()      →  NoSuchElementException
GLineIterator(parse("".iterator())).next()      →  StringIndexOutOfBoundsException  (propagated from the tokenizer)
```

*The skill's rule — exceptions are reserved for programmer error, and `next()` past the end is exactly
that — so this is the one place the module **should** throw, just not this type and not
source-dependently.*

- [x] Guard `GTokenizerIterator.next()` and throw `NoSuchElementException` regardless of source.
- [x] Guard `GLineIterator.next()` the same way.

### 1.18 Minor / decide-and-document

- [ ] On CRLF input `tailComment()` stops only at `'\n'`, so the `'\r'` lands *inside* the comment
      text (`;ab\r\n` → `GTailComment("ab\r")`). The text still round-trips, but the comment content
      carries a stray CR.
- [ ] `Char.isLetter()` accepts any Unicode letter, so `GЯ1` lexes `Я` as a `GLetter`. Spec
      [§1.1](../specs/GCODE_spec.md#11-character-set-and-encoding) confines non-ASCII to comments and
      quoted strings.

---


---

## 2. Conformance with `GCODE_spec.md`

Well covered: balanced `{}` expressions, `""`-doubling quoted strings
([§3.4b](../specs/GCODE_spec.md#34-string-values), the RepRapFirmware rule), both comment forms, `\r\n` vs
lone `\r` handling, and `rawText()` round-tripping as an explicit design invariant.

| Spec section | Status |
|---|---|
| §3.1 signed numbers | ✓ sign, leading zeros, leading and trailing dot all lex and round-trip |
| §4.1 subcodes (`G29.1`) | ✗ lexed as `GFloat(29.1)`; no subcode concept on `GCommand` |
| §3.2 value-less flag params | ~ representable as tokens, but nothing groups them into words/commands |
| §3.4a bare rest-of-line strings | ✗ `M117 Hello World` → one `GLetter` per character; `M30 /path/to/f.gco` → `GUnknown(/)`, letters, `GUnknown(.)` |
| §7.2 line-number continuity, `M110` | ✗ absent — needs a stateful reader, [§0.1](./00-index.md) step 5 |
| §7.3 `N`/`*` pairing errors | ✓ `GMissingChecksum` / `GMissingLineNumber`, reported not rejected |
| §8.2 checksum verify / generate | ✗ `XorCheckSum` is **correct** (`N3 T0` → `GInt(57)`) but has **zero callers** |
| §8.4 CRC16 | ✗ absent |
| §1.3 line-length limit | ✗ absent |
| §2.1 whitespace-insensitive word assembly | ✗ no word assembly at all (see below) |
| §2.2 case-insensitivity | ~ packet detection folds ASCII case; word assembly does not exist yet |
| §3.5 `[…]` / `#param` (RS274) | ✗ absent (deferrable) |
| §5 no-op lines (blank / comment-only) | ✓ `GEmptyLine` |
| §5 block delete `/` | ✗ absent (deferrable) |
| §9 error reporting | ~ lexical errors are values: malformed numbers and unterminated literals are `GUnknown` carrying the offending bytes, and the lexer no longer throws. At the line level `GError` / `GNotIdentifierError` are still never constructed and framing errors surface as `GInt(-1)` sentinels |

**The word→command layer is disconnected.** `GCommandParser.parseLine` (`GLiner.kt:65`) is `private`
in a class with no other members, so nothing can call it — meaning `GCommandLine` and
`GNotIdentifierError` are never produced anywhere in the codebase. `GCodeReader` (`GLiner.kt:5-11`) is
an empty stub with its body commented out. Spec [§4](../specs/GCODE_spec.md#4-identifiers-field-letters) and
[§5](../specs/GCODE_spec.md#5-line-block-structure) are therefore unimplemented in practice, even though the
types for them exist.

---


---

## 3. Test coverage

**Current state — 398 tests, all green** (`:gcode:test`), up from 37. Test sources now mirror the
production packages: everything for `core.token` lives in
`src/test/kotlin/org/qw3rtrun/p3d/g/code/core/token/`.

| Suite | Tests | Covers |
|---|---|---|
| `GTokensTest` | 45 | token model: `rawText()` fidelity per kind, the number lexeme (identity, defaults, `Double` precision), quoted-string escaping, sealed-hierarchy membership (comments/separators are *not* `GElement`), `toSeq()`, `toToken()` conversions, scale-sensitive `GFloat` equality |
| `GTokenizerTest` | 181 | one nested group per token kind (letters, numbers incl. signed, non-canonical and malformed ones, quoted strings, tail/inline comments, expressions, separators incl. tabs and LF/CRLF/CR, checksum marker, unknown chars), unterminated literals, iterator contract, sequence re-iteration, `parseLines`, all five input overloads, whole-line integration, 37 round-trip cases |
| `GLineIteratorTest` | 83 | line splitting, packet recognition and the four structural errors, plus four nested groups added in Commit B: `BufferBoundaries` (26 no-throw cases, tail with/without terminator, CRLF, `*`-last, `N`-last), `EmptyLines` (spec §5 no-ops), `PacketDetection` (case and leading whitespace, N-must-be-first, whitespace inside the N word), `NothingIsLost` (the lines reassemble into the input) |
| `GSemanticsTest` | 26 | `GCommand.print()`, both constructors, line-type hierarchy, exhaustive `when` guard over `GLine`, `GCheckSumValue`, error messages |
| `GCorpusTest` | 10 | `marlin.gcode` (415 lines, parsed as checked out): tokenizes without failing, expected token kinds present, liner line count, one break token per `\n`, per-line round-trip, the exact set of characters the lexer still does not understand, and the line-kind census (301 simple, 111 empty, 2 `GMissingChecksum`) |
| `XorCheckSumTest` | 17 | spec §8.2/§8.3 including the byte-by-byte worked example, known-line values, masking, order independence, streaming contract |
| `GDescriptionTest` | 11 | descriptor/field defaults, `optional`, mixed field types, equality |
| `GTest` | 9 | DSL: every `G`/`M`/`T` overload, parameter appending, emission order and count |
| decoder tests | 16 | unchanged (`Ok`, `TemperatureReported`, `FirmwareReport`) |

Removed in the refactor: `code/token/GTokenizerTest.kt` and `code/token/GCodeReaderTest.kt` — wrong
package, and between them 5 of 6 tests asserted nothing (`GCodeReaderTest` also dispatched on
`GCommandLine`/`GError`, which nothing produces). Their inputs are preserved as assertions in the new
suites; the two real-slicer corpora live on as round-trip cases in `GTokenizerTest`.

**Deliberate gaps — the list is now empty for lexing and framing.** Commit A closed
inline-comment *text* (1.2), `parseLines` (1.5), unterminated literals (1.7) and sequence
re-iteration (1.11); Commit B closed `tail` contents (1.3), case/whitespace-tolerant packet
detection (1.8) and `GEmptyLine` production (1.9). The one item still uncovered on purpose is
adjacent-value `print()` ([1.10](#110-gcommandprint-can-emit-invalid-g-code--gsemanticskt38-44)),
which is waiting on the real encoder of [§4.4](./00-index.md).

**Characterisation points** are marked in the tests and must be revisited with the fixes:

- [x] `GCorpusTest.each line round trips` — the quarantine list is empty as of
      [1.16](#116-number-lexemes-are-normalised-on-render--gtokenizerkt64-94-gtokenskt80-96---fixed);
      every line of the corpus now reproduces itself byte for byte
- [ ] `GCorpusTest.only the documented lexer gaps produce unknown tokens` pins the unknown-character
      set. `-` is gone with [1.1](#11-negative-numbers-do-not-lex--gtokenizerkt27-45---fixed); what is
      left (`. / \ ~ ! : | # ' ,`) all comes from the §2 gaps — bare rest-of-line strings and RS274
      parameters. *(re-probed) Confirmed unchanged over the corpus: `! # ' , . / : \ | ~`, and the
      whole-file `rawText()` round-trip is `true`.*
- [ ] `GLineIteratorTest.a negative line number is indistinguishable from the missing-number sentinel`
      pins the ambiguity that [1.1](#11-negative-numbers-do-not-lex--gtokenizerkt27-45---fixed)
      created: `N-1` and a missing `N` value both report `GInt(-1)`. It must change with
      [§4.3](#43-priority-3--model-errors-instead-of-sentinels---done)

**No test freezes a bug any more.** All three characterisation points are retired:

- [x] `GLineIteratorTest.kt:222` — `GInlineComment("feedrate)")` locked in
      [1.2](#12-inline-comments-corrupt-on-round-trip--gtokenizerkt147-160---fixed); unfrozen in
      Commit A, the assertion now reads `GInlineComment("feedrate")`
- [x] `GLineIteratorTest.kt:116` — `tail == listOf(GInt(45))` locked in
      [1.3](#13-gpacketlinetail-is-off-by-one-and-can-crash--glinerkt51-glinerkt56---fixed); the tail
      of that line is now empty
- [x] `GLineIteratorTest.kt:88` — blank line as `GSimpleLine` locked in
      [1.9](#19-gemptyline-is-unreachable--gsemanticskt19-21-glinerkt37--fixed); it is a
      `GEmptyLine`
- [x] `a negative line number is indistinguishable from the missing-number sentinel` — retired with
      [§4.3](#43-priority-3--model-errors-instead-of-sentinels---done); the test now asserts the two are
      different types

**Still untested, because unreachable or unimplemented:** `GCommandParser` (private, no callers),
`GCodeReader` (empty stub), checksum verification/generation at line level (does not exist).
`GTokenizer.parseLines` came off this list in Commit A.

**Deleted coverage.** Commit `f0d0944` removed **11 Java test suites** with no Kotlin replacement:

```
gcode/src/test/java/org/qw3rtrun/p3d/g/GTest.java
gcode/src/test/java/org/qw3rtrun/p3d/g/code/core/GAwareDecoderTest.java
gcode/src/test/java/org/qw3rtrun/p3d/g/code/core/GCoreCodecTest.java
gcode/src/test/java/org/qw3rtrun/p3d/g/code/core/GCoreDecoderTest.java
gcode/src/test/java/org/qw3rtrun/p3d/g/code/core/GLineCodecTest.java
gcode/src/test/java/org/qw3rtrun/p3d/g/code/core/XorCheckSumTest.java
gcode/src/test/java/org/qw3rtrun/p3d/g/code/decoder/CommandDescriptorTest.java
gcode/src/test/java/org/qw3rtrun/p3d/g/encoder/SetHotendTemperatureEncoderTest.java
gcode/src/test/java/org/qw3rtrun/p3d/g/decoder/{FirmwareReport,Ok,TemperatureReported}DecoderTest.java   ← these 3 were ported
```

GCODEK.md phases 4–6 list all of them as *migrate*, so the loss of the first eight looks
unintentional rather than a decision.

- [ ] Decide per suite: port to Kotlin, or record why it is obsolete.
      `XorCheckSumTest` and `GAwareDecoderTest` cover code that is still live.

**Fixture now in use.** `gcode/src/test/resources/marlin.gcode` (415 lines, 8.5 KB of real
Marlin-flavoured G-code) is driven by `GCorpusTest`. Designing that test is what surfaced bugs
[1.12](#112-crlf-yields-two-line-breaks--gtokenizerkt52-58---fixed) and
[1.13](#113-leading-dot-decimals-duplicate-a-digit--gtokenizerkt64-94---fixed).

---


---

## 4. Improvement plan — the completed priorities

Priorities 4.4 (wire up the orphans) and 4.5 (hygiene) are still open and have been split into
the numbered files; see [00-index.md](./00-index.md).

### 4.1 Priority 1 — stop corrupting data

The lookahead bugs shared one root cause: `ch` served both as "current character" and as
"pushed-back character" and was not always cleared. `number()` now keeps one `current` variable and
writes the pushed-back character to `ch` exactly once, on the way out.

*(re-probed) `inlineComment()` is the last scanner that still writes `ch` inside its loop, so
[1.7](#17-unterminated-literals-are-lossy-and-silent--gtokenizerkt96-114-gtokenizerkt147-160---fixed) belongs
to this same root cause and is promoted into Priority 1 alongside 1.2. What remains splits into two
commits by file — see [§0.1](./00-index.md).*

**Commit A — `GTokenizer` — done:**

- [x] Fix [1.2](#12-inline-comments-corrupt-on-round-trip--gtokenizerkt147-160---fixed) inline-comment delimiter
- [x] Fix [1.7](#17-unterminated-literals-are-lossy-and-silent--gtokenizerkt96-114-gtokenizerkt147-160---fixed)
      unterminated literals — same function as 1.2, and the last unfixed lookahead site
- [x] Fix [1.5](#15-parselines-drops-line-terminators--gtokenizerkt16---fixed) `parseLines`
- [x] Fix [1.11](#111-parse-returns-a-constrain-once-sequence--gtokenizerkt11-13---fixed) constrain-once sequence
- [x] Guard [1.17](#117-iterators-do-not-guard-next--gtokenizerkt26-glinerkt25---fixed) `GTokenizerIterator.next()`
- [x] Update the frozen assertion at `GLineIteratorTest.kt:222` (1.2)
- [x] *Not planned, done anyway:* `expression()` and `tailComment()` rewritten into the same shape,
      so the no-`ch`-in-a-loop invariant holds for every scanner in the file. Behaviour unchanged.

**Commit B — `GLiner`:**

- [ ] Fix [1.3](#13-gpacketlinetail-is-off-by-one-and-can-crash--glinerkt51-glinerkt56---fixed) `tail` bounds + crash
- [ ] Fix [1.8](#18-packet-detection-is-case--and-position-exact--glinerkt34--fixed) case/whitespace-tolerant packet detection
- [ ] Fix [1.9](#19-gemptyline-is-unreachable--gsemanticskt19-21-glinerkt37--fixed) `GEmptyLine`
- [ ] Guard [1.17](#117-iterators-do-not-guard-next--gtokenizerkt26-glinerkt25---fixed) `GLineIterator.next()`
- [ ] Replace the `GInt(-1)` sentinels — [§4.3](#43-priority-3--model-errors-instead-of-sentinels---done)
- [ ] Update the frozen assertions at `GLineIteratorTest.kt:116` (1.3) and `:88` (1.9)

**Already done:**

- [x] Fix [1.12](#112-crlf-yields-two-line-breaks--gtokenizerkt52-58---fixed) CRLF double line break
- [x] Fix [1.13](#113-leading-dot-decimals-duplicate-a-digit--gtokenizerkt64-94---fixed) leading-dot digit duplication
- [x] Fix [1.14](#114-malformed-decimals-throw-out-of-the-lexer--gtokenizerkt74-91---fixed) multi-dot crash
- [x] Remove [1.6](#16-debug-println-in-the-hot-path--gtokenizerkt90---fixed) the `println`
- [x] Fix [1.1](#11-negative-numbers-do-not-lex--gtokenizerkt27-45---fixed) signed numbers
- [x] Fix [1.4](#14-doubletotoken-destroys-precision--gtokenskt90-gtokenskt108---fixed) `BigDecimal.valueOf`
- [x] Fix [1.15](#115-tabs-are-unknown-tokens-and-gtab-is-dead--gtokenizerkt48-62---fixed) tab → `GTab`
- [x] Decide and implement
      [1.16](#116-number-lexemes-are-normalised-on-render--gtokenizerkt64-94-gtokenskt80-96---fixed)
      the number lexeme question


### 4.2 Priority 2 — make regressions visible

Done in the test pass (242 tests, all green — see [§3](#3-test-coverage)):

- [x] Round-trip tests: 19 parameterised cases, two real slicer blocks, and per-line round-trip over
      the whole `marlin.gcode` corpus
- [x] `println`-only test bodies replaced with assertions; the misplaced `code/token` suites removed
- [x] Bare `assert` replaced with `assertEquals`/`assertTrue` so failures show a diff
- [x] `XorCheckSumTest` added — 17 tests including the byte-by-byte spec §8.3 example
- [x] `GTokensTest`, `GSemanticsTest`, `GDescriptionTest`, `GCorpusTest` added; `GTest` (DSL) and
      `GLineIteratorTest` extended with edge and negative cases

Remaining:

- [ ] Port or retire the eight orphaned Java suites listed above
- [x] Add tests for `parseLines` — 9, added with the 1.5 fix in Commit A
- [ ] Add tests for `GCommandParser` and line-level checksums as those are wired up (4.3, 4.4)


### 4.3 Priority 3 — model errors instead of sentinels — ✅ DONE

The sentinels are gone by construction: `GPacketLine` now only ever represents a **well-formed**
packet, so `number` and `checksum.value` are non-null `GInt` because there is no case left in which
they could be absent. Everything else is a typed error carrying the whole line.

| Input | Was | Now |
|---|---|---|
| `N100 G1 X10 *45` | `GPacketLine` | `GPacketLine` — unchanged |
| `N-1 G28*12` | `GPacketLine(number = GInt(-1))` | `GPacketLine(number = GInt(-1, "-1"))` — spec §7.1 tolerates the sign |
| `N G28*12` | `GPacketLine(number = GInt(-1))` — collided with the above | `GMalformedLineNumber` |
| `N100 G1 *ABC` | `GPacketLine(checksum = GInt(-1))` | `GMalformedChecksum(number = GInt(100))` |
| `N*45 G1` | `GPacketLine(-1, -1)` | `GMalformedLineNumber` |
| `N100 G1 X10` | `GSimpleLine` | `GMissingChecksum(number = GInt(100))` |
| `G1 X10*45` | `GSimpleLine` | `GMissingLineNumber` |

- [x] Replace `GInt(-1)` in `GLiner.kt:43,50,57` with nullable fields or dedicated `GError` subtypes.
      *Chose dedicated subtypes over nullables — a nullable `number` would have pushed the same
      "is it real or is it missing" question onto every caller, which is what the sentinel already
      did. The one nullable that remains is `GMissingChecksum.number`, because `N` alone genuinely
      has no number to report and the pairing error is the more useful thing to say about it.*
- [x] Emit the [§7.3](../specs/GCODE_spec.md#73-pairing-rule) errors: `N` without `*`, `*` without `N`.
      *Reported, not rejected: over a link these are structural errors, but in a file `N` without a
      checksum is benign and common — the corpus fixture has two. The liner names the structure and
      leaves the severity to the caller, which is documented on `GMissingChecksum` and pinned by
      `GCorpusTest.the liner classifies every corpus line and loses nothing`.*
- [x] The characterisation point `a negative line number is indistinguishable from the
      missing-number sentinel` is retired — the test now asserts the two are different types.

Still open at the line level: **framing** errors as opposed to structural ones — checksum mismatch
(needs [§4.4](./00-index.md)'s `GPacketLine.verify()`) and line-number
discontinuity (spec [§7.2](../specs/GCODE_spec.md#72-semantics), needs a stateful reader). Both are
[§4.4](./00-index.md) work, not sentinel work.


---

## Appendix — probe output

Verbatim results from the temporary probe test (deleted after the review):

```
roundtrip inline comment      | false
inline comment raw            | G1 (feedrate)) F1500
unterminated string raw       | M"asd"
unterminated inline raw       | Mabcc
negative value tokens         | [GLetter(G), GInt(1), GSpace, GLetter(E), GUnknown(-), GInt(5), GSpace, GLetter(F), GInt(1800)]   (1.1, fixed)
double toToken rawText        | 1.0500000000000000444089209850062616169452667236328125   (1.4, fixed)
BigDecimal valueOf rawText    | 1.05
GFloat 1.0 == 1.00            | false
parseLines loses breaks       | G28M104 S200
packet N* no newline          | EX: java.lang.IllegalArgumentException: fromIndex(2) > toIndex(1)
packet lowercase n            | GSimpleLine
packet leading space          | GSimpleLine
packet tail no newline        | [GInt(12), GSpace]
print two ints                | G12
bare string M117              | [GLetter(M), GInt(117), GSpace, GLetter(H), GLetter(e), GLetter(l), ...]
bare filename M30             | [GLetter(M), GInt(30), GSpace, GUnknown(/), GLetter(p), ..., GUnknown(.), GLetter(g), ...]
xor of 'N3 T0'                | GInt(57)
subcode G29.1                 | [GLetter(G), GFloat(29.1), GSpace, GLetter(S), GInt(1)]
GLineBreak toString           | GLineBreak
GEmptyLine ever produced      | [GSimpleLine, GSimpleLine]
```

### Re-probe, 2026-09-07 against `90b19aa`

Every open finding re-run on the current tree. Unchanged results are omitted; these are the ones that
added or corrected information above.

```
1.3 tail with checksum + newline   | [GInt(int=45, lexeme=45)]                       (unchanged, still off by one)
1.3 tail no newline                | [GInt(int=12, lexeme=12), GSpace]               (unchanged, comment still lost)
1.3 'N1*'                          | EX: IllegalArgumentException: fromIndex(3) > toIndex(2)    ← new
1.3 'N1 G1*'                       | EX: IllegalArgumentException: fromIndex(6) > toIndex(5)    ← new
1.3 'N1 G28*12' (no break)         | tail=[]                                          (right answer, wrong reason)
1.7 unterminated inline tokens     | [GLetter(M), GUnknown(abc), GLetter(c)]          ← new: 'c' duplicated
1.7 nested unterminated inline     | [GUnknown(a(b), GLetter(b)]                      ← new
1.7 unterminated expr tokens       | [GLetter(M), GUnknown({abc)]                     ← new: expression() is clean
1.7 string closed at EOF           | [GLetter(M), GQuotedString(a)]                   ← indistinguishable from M"a
1.7 bare quote at EOF              | [GQuotedString()]                                ← new
1.17 next past end (String src)    | EX: StringIndexOutOfBoundsException: Index 0 out of bounds for length 0
1.17 next past end (List src)      | EX: NoSuchElementException
1.17 GLineIterator past end        | EX: StringIndexOutOfBoundsException              ← new
corpus unknown lexemes             | [!, #, ', ,, ., /, :, \, |, ~]                   (unchanged; '-' still gone)
corpus whole-file round trip       | true
:gcode:test                        | 295 tests, 0 failures, 0 skipped
```

Core import audit (`low-level-protocol-dev` review checklist, run over `code/core/**`):

```
non-kotlin imports   | GDescription.kt:3 BigDecimal · GTokenizer.kt:3 BigDecimal · GTokenizer.kt:4 java.util.stream.Stream · GTokens.kt:3 BigDecimal
unicode predicates   | GTokenizer.kt:28,29,30,91,99,142
streams              | GTokenizer.kt:4,5,6
stray ';'            | GTokenizer.kt:36,131,147,149,150,173,175,176 · GTokens.kt:56,60
kotlin.math.min      | GLiner.kt:3 — IN USE at GLiner.kt:56, not dead as previously recorded
```

### Commit A verification, 2026-09-07

After the `GTokenizer` delimiter pass. Every line below is run output, not a reading.

```
1.2 inline roundtrip            | G1 (feedrate) F1500
1.2 inline token                | [GInlineComment(string=feedrate, start=(, end=))]
1.7 unterminated inline         | [GLetter(M), GUnknown("(abc")]
1.7 unterminated string         | [GLetter(M), GUnknown("\"asd")]
1.7 lone quote                  | [GUnknown("\"")]
1.7 string closed at EOF        | [GLetter(M), GQuotedString(a)]        (was indistinguishable from M"a)
1.5 parseLines                  | G28<LF>M104 S200            (was G28M104 S200)
1.11 sequence consumed twice    | [GLetter(G), GInt(1)]                 (was IllegalStateException)
1.17 next past end, String src  | NoSuchElementException: no more tokens
1.17 next past end, List src    | NoSuchElementException: no more tokens
1.18 CRLF tail comment          | GTailComment("ab<CR>")                  (deliberately unchanged)
corpus whole-file round trip    | true
corpus unknown lexemes          | [!, #, ', ,, ., /, :, \, |, ~]        (unchanged)
:gcode:test                     | 336 tests, 0 failures, 0 skipped
./gradlew build                 | SUCCESSFUL
```

Post-fix checklist greps over `code/core/**`:

```
non-kotlin imports   | unchanged - GDescription.kt:3, GTokenizer.kt:3, GTokenizer.kt:4, GTokens.kt:3
ch written in a loop | none; every write is at scan entry or on exit
stray ';'            | 10 -> 3 (GTokenizer.kt:85, GTokens.kt:56,60), the rest went with the rewrites
```

The corpus does **not** exercise 1.2: `marlin.gcode` contains zero `()` comments. The inline-comment
fix is covered by unit tests only, which is worth knowing if that fixture is ever taken as the sole
regression net.

### Commit B verification, 2026-09-07

After the `GLiner` framing pass. Run output, not a reading.

```
1.3 N*                    | [GMalformedLineNumber]                 (was IllegalArgumentException)
1.3 N1*                   | GMalformedChecksum                     (was IllegalArgumentException)
1.3 N1 G1*                | GMalformedChecksum                     (was IllegalArgumentException)
1.3 tail, with break      | []                                     (was [GInt(45)])
1.3 tail, no break        | [GSpace, GTailComment(c)]              (was [GInt(12), GSpace] - comment lost)
1.8 lowercase n           | GPacketLine                            (was GSimpleLine)
1.8 leading space         | GPacketLine                            (was GSimpleLine)
1.9 blank lines           | [GEmptyLine, GEmptyLine]               (was [GSimpleLine, GSimpleLine])
1.9 comment-only line     | GEmptyLine                             (was GSimpleLine)
1.17 next past end        | NoSuchElementException: no more lines  (was StringIndexOutOfBounds)
7.3 N without star        | GMissingChecksum                       (was GSimpleLine)
7.3 star without N        | GMissingLineNumber                     (was GSimpleLine)
corpus line count         | 414                                    (unchanged)
corpus line kinds         | {GSimpleLine=301, GEmptyLine=111, GMissingChecksum=2}
corpus errors             | [line number 100 has no checksum, line number 101 has no checksum]
corpus payload non-empty  | true
:gcode:test               | 398 tests, 0 failures, 0 skipped
./gradlew build           | SUCCESSFUL
```

Post-fix checklist greps over `code/core/**`:

```
non-kotlin imports    | unchanged - GDescription.kt:3, GTokenizer.kt:3, GTokenizer.kt:4, GTokens.kt:3
                      | kotlin.math.min is gone with parsePacket
ignoreCase / trim     | none; both GLineIterator and GCommandParser fold ASCII explicitly
size-relative index   | one, GLiner.kt:38, and it is guarded by an explicit `is GLineBreak` test -
                      | which is what the framing rule asks for rather than what it forbids
GCodeReader           | deleted
```

**Two reclassifications a reader should know about**, neither a regression:

* a comment-only or whitespace-only line is now `GEmptyLine`, not `GSimpleLine` — 111 of the corpus's
  414 lines. Spec §5 calls such a line a no-op.
* `N` without `*` is now `GMissingChecksum`, not `GSimpleLine` — spec §7.3. The two corpus lines that
  hit this (`N100 M110`, `N101 M110 N100`) are legal in a file and only an error over a link, which
  is why the liner reports the structure instead of rejecting the line.

