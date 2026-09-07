# `:gcode` Kotlin Review & TODO

**Scope:** `gcode/src/main/kotlin/org/qw3rtrun/p3d/g/code/**` reviewed against
[`GCODE_spec.md`](./GCODE_spec.md).
**Date:** 2026-09-07 · **Base:** `f0d0944` (*gcode - agentic migration to K*) plus the uncommitted
working-tree changes to `GSemantics.kt`, `GLiner.kt`, `XorCheckSum.kt`, `GLineIteratorTest.kt`.
**Method:** every finding below was verified by compiling and running the module (`:gcode:test`) plus a
temporary probe test, not by reading alone. Probe outputs are quoted verbatim. Findings 1.12–1.18 came
out of writing the test suites described in [§3](#3-test-coverage).

---

## 0. Build / test status

`:gcode:compileTestKotlin` and `:gcode:test` are **green** — **295 tests**, 0 failures, 0 skipped
(37 at the time of the review; see [§3](#3-test-coverage) for the breakdown of the test pass).

**Fixed so far:** [1.6](#16-debug-println-in-the-hot-path--gtokenizerkt90--fixed),
[1.12](#112-crlf-yields-two-line-breaks--gtokenizerkt52-58--fixed),
[1.13](#113-leading-dot-decimals-duplicate-a-digit--gtokenizerkt64-94--fixed),
[1.14](#114-malformed-decimals-throw-out-of-the-lexer--gtokenizerkt74-91--fixed) — the three
lookahead/number bugs in `GTokenizer`, TDD: 22 tests written red first, then the fix.

Then the **number lexeme pass**: [1.1](#11-negative-numbers-do-not-lex--gtokenizerkt27-45--fixed),
[1.4](#14-doubletotoken-destroys-precision--gtokenskt90-gtokenskt108--fixed),
[1.15](#115-tabs-are-unknown-tokens-and-gtab-is-dead--gtokenizerkt48-62--fixed),
[1.16](#116-number-lexemes-are-normalised-on-render--gtokenizerkt64-94-gtokenskt80-96--fixed) and the
open remainder of 1.14, again TDD: 31 tests red first, then the fix. `rawText()` now round-trips the
whole corpus byte for byte, so both characterisation points of [§3](#3-test-coverage) are retired.
Everything else in [§1](#1-confirmed-bugs) is still open, and no test depends on the remaining buggy
behaviour except the three assertions listed in [§3](#3-test-coverage).

- [ ] **Refresh `.junie/GCODEK.md`** — §1 "Current Status & Blocker Analysis" is stale. It claims
  `:gcode:compileKotlin` fails with unresolved `GCommand` / `GCommandLine` and leaves Phase 1
  unchecked; `GSemantics.kt` now defines both and Phase 1 is effectively complete.

---

## 1. Confirmed bugs

Ordered by impact. Every symptom is an observed run result.

### 1.1 Negative numbers do not lex — `GTokenizer.kt:27-45` — ✅ FIXED

*Fixed together with [1.16](#116-number-lexemes-are-normalised-on-render--gtokenizerkt64-94-gtokenskt80-96--fixed):
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

Spec [§3.1](./GCODE_spec.md#31-numeric-values) requires the optional sign to be part of the number.
Every retraction and relative move in real slicer output is affected — including the fixture already
in the repo (`GTokenizerTest.kt:20`, `G1 E-5 F1800`).

- [x] Lex `[+|-]` as part of `GNumber`. `rawText()` preserves `+5` verbatim — see 1.16 for why the
      lexeme, not the value, decides how a number re-prints.

### 1.2 Inline comments corrupt on round-trip — `GTokenizer.kt:147-160`

`comment.append(ch)` runs *before* `)` decrements `stack`, so the closing paren ends up inside
`GInlineComment.string`, and `rawText()` (`GTokens.kt:46`) appends another one:

```
G1 (feedrate) F1500   →   G1 (feedrate)) F1500      (round-trip == false)
```

Note the inconsistency with `expression()` (`GTokenizer.kt:121-134`), which deliberately *includes*
its `{` `}` delimiters in the token text — which is why `{}` round-trips correctly and `()` does not.

- [ ] Don't append the closing delimiter to `GInlineComment.string` (or store delimiters uniformly
      across both token types and make `rawText()` match).

### 1.3 `GPacketLine.tail` is off by one and can crash — `GLiner.kt:51`, `GLiner.kt:56`

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

- [ ] Start the tail at `indexOfCheckSum + 2`; strip a trailing `GLineBreak` explicitly rather than
      by index arithmetic; guard the short-line cases so no input can throw.

### 1.4 `Double.toToken()` destroys precision — `GTokens.kt:90`, `GTokens.kt:108` — ✅ FIXED

*Fixed: the `Double` constructor uses `BigDecimal.valueOf`, which goes through
`Double.toString()` and so yields the shortest decimal that round-trips. Covered by
`GTokensTest.float from a double does not expand the binary representation`.*

*Follow-up, planned in [§4.4](#44-wire-up-or-delete-the-orphans): **delete the `Double` path
altogether**. `valueOf` fixed how a `Double` renders, not the fact that a `Double` has no authored
lexeme to render — the hazard class stays as long as the constructor does.*

`GFloat(float: Double) : this(BigDecimal(float))` uses the exact binary expansion:

```
1.05.toToken().rawText()               → 1.0500000000000000444089209850062616169452667236328125
GFloat(BigDecimal.valueOf(1.05))       → 1.05
```

Any line generated through the `Double` path is unusable.

- [x] Use `BigDecimal.valueOf(double)` (or `double.toString()`) in the `Double` constructor.

### 1.5 `parseLines` drops line terminators — `GTokenizer.kt:16`

`flatMap { it.asSequence() }` concatenates the strings without re-inserting `\n`:

```
["G28", "M104 S200"]   →   G28M104 S200
```

Anything fed from `BufferedReader.lineSequence()` / `File.readLines()` silently merges commands into
one line.

- [ ] Re-insert the terminator between elements (and decide whether the caller or the tokenizer owns
      the choice of `\n` vs `\r\n`).

### 1.6 Debug `println` in the hot path — `GTokenizer.kt:90` — ✅ FIXED

`println(str)` inside `number()` fired for every numeric token and polluted test stdout.

- [x] Deleted along with the rest of the `number()` rewrite for 1.13/1.14.

### 1.7 Unterminated literals are lossy and silent — `GTokenizer.kt:96-114`, `GTokenizer.kt:147-160`

```
M"asd   →   M"asd"     (gains a closing quote)
M(abc   →   Mabcc      (loses the opening paren; GUnknown keeps only the body)
```

Spec [§9](./GCODE_spec.md#9-error-handling) classifies both as lexical errors. At minimum the
`rawText()` fidelity invariant must hold.

- [ ] Preserve the original text in the degraded token, and surface the condition (dedicated token
      kind or diagnostic) instead of silently repairing it.

### 1.8 Packet detection is case- and position-exact — `GLiner.kt:34`

`isPacket` requires `tokens[0] == GLetter('N')`:

```
n1 g28*12      → GSimpleLine
" N1 G28*12"   → GSimpleLine
```

Spec [§2.2](./GCODE_spec.md#22-case) — RS274/NGC and RepRapFirmware ≥ 1.19 are case-insensitive,
Marlin optionally so; leading whitespace is a separator ([§2.1](./GCODE_spec.md#21-whitespace)).

- [ ] Skip leading `GWhitespace` and compare the letter case-insensitively.

### 1.9 `GEmptyLine` is unreachable — `GSemantics.kt:19-21`, `GLiner.kt:37`

`nextLine()` always returns at least one token, so `tokens.isEmpty()` never holds:

```
"\n\n"   →   [GSimpleLine, GSimpleLine]
```

- [ ] Either classify a line whose tokens are only separators/comments as `GEmptyLine`, or delete the
      type. (Spec [§5](./GCODE_spec.md#5-line-block-structure) treats such a line as a no-op, so the
      distinction is worth keeping.)

### 1.10 `GCommand.print()` can emit invalid G-code — `GSemantics.kt:38-44`

`rawText()` values are concatenated with no separator, so adjacent numbers fuse:

```
GCommand(GLetter('G'), listOf(GInt(1), GInt(2))).print()   →   "G12"
```

It is also the only encoder in the module — nothing emits `N` / `*` framing
([§7](./GCODE_spec.md#7-line-numbering), [§8](./GCODE_spec.md#8-checksum-and-crc)).

- [ ] Insert a separator when two value tokens would otherwise abut (or always separate words with a
      space), and add a real line encoder — see [§4.4](#44-wire-up-or-delete-the-orphans).

### 1.11 `parse()` returns a constrain-once sequence — `GTokenizer.kt:11-13`

`Iterator.asSequence()` is `constrainOnce()`, so a returned `Sequence` can be consumed exactly once:

```kotlin
val t = tokenizer.parse(src)
t.count()      // ok
t.toList()     // IllegalStateException: This sequence can be consumed only once.
```

- [ ] Return `Sequence { GTokenizerIterator(...) }` so the result is re-iterable for the
      `CharSequence` / `Iterable<Char>` overloads (a `Sequence<Char>` / `Iterator<Char>` source
      cannot be, and that's fine — document the asymmetry).

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
[1.13](#113-leading-dot-decimals-duplicate-a-digit--gtokenizerkt64-94--fixed): a lookahead that is
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
`.1` back as `0.1` — the lexeme question of [1.16](#116-leading-zeros-are-lost--gtokenizerkt64-94).

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

Spec [§9](./GCODE_spec.md#9-error-handling) wants a *lexical error* token, not an unhandled
exception escaping the tokenizer.

- [x] Reject a second `.` at lex time (emit an unknown/error token) and never let `BigDecimal` throw
      through the iterator.
- [x] Closed with [1.16](#116-number-lexemes-are-normalised-on-render--gtokenizerkt64-94-gtokenskt80-96--fixed):
      `X1.` keeps the lexeme `1.` and stays a `GFloat`. Spec
      [§3.1](./GCODE_spec.md#31-numeric-values) lists `2.` among the valid real forms, so `GUnknown`
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
([1.9](#19-gemptyline-is-unreachable--gsemanticskt19-21-glinerkt37)). Spec
[§2.1](./GCODE_spec.md#21-whitespace) classifies tab as whitespace.

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

The values are all numerically right (spec [§3.1](./GCODE_spec.md#31-numeric-values),
[§4.1](./GCODE_spec.md#41-command-letters)) — it is the `rawText()` round-trip invariant that does
not hold. These three lexemes are the only remaining round-trip gap in the corpus.

- [x] Decided: carry the original lexeme in `GInt`/`GFloat`. Round-trip holds unconditionally; the
      equality change is documented on `GNumber`.

### 1.17 Iterators do not guard `next()` — `GTokenizer.kt:26`, `GLiner.kt:25`

Neither `GTokenizerIterator.next()` nor `GLineIterator.nextLine()` checks `hasNext()`; they call
through to the source, so the exception type depends on the input overload — a `String`/`CharSequence`
source raises `StringIndexOutOfBoundsException` where a `List` source raises `NoSuchElementException`.
`Iterator.next()` is specified to throw the latter.

- [ ] Guard both and throw `NoSuchElementException` regardless of source.

### 1.18 Minor / decide-and-document

- [ ] On CRLF input `tailComment()` stops only at `'\n'`, so the `'\r'` lands *inside* the comment
      text (`;ab\r\n` → `GTailComment("ab\r")`). The text still round-trips, but the comment content
      carries a stray CR.
- [ ] `Char.isLetter()` accepts any Unicode letter, so `GЯ1` lexes `Я` as a `GLetter`. Spec
      [§1.1](./GCODE_spec.md#11-character-set-and-encoding) confines non-ASCII to comments and
      quoted strings.

---

## 2. Conformance with `GCODE_spec.md`

Well covered: balanced `{}` expressions, `""`-doubling quoted strings
([§3.4b](./GCODE_spec.md#34-string-values), the RepRapFirmware rule), both comment forms, `\r\n` vs
lone `\r` handling, and `rawText()` round-tripping as an explicit design invariant.

| Spec section | Status |
|---|---|
| §3.1 signed numbers | ✓ sign, leading zeros, leading and trailing dot all lex and round-trip |
| §4.1 subcodes (`G29.1`) | ✗ lexed as `GFloat(29.1)`; no subcode concept on `GCommand` |
| §3.2 value-less flag params | ~ representable as tokens, but nothing groups them into words/commands |
| §3.4a bare rest-of-line strings | ✗ `M117 Hello World` → one `GLetter` per character; `M30 /path/to/f.gco` → `GUnknown(/)`, letters, `GUnknown(.)` |
| §7.2 line-number continuity, `M110` | ✗ absent |
| §7.3 `N`/`*` pairing errors | ✗ a mismatched line silently degrades to `GSimpleLine` |
| §8.2 checksum verify / generate | ✗ `XorCheckSum` is **correct** (`N3 T0` → `GInt(57)`) but has **zero callers** |
| §8.4 CRC16 | ✗ absent |
| §1.3 line-length limit | ✗ absent |
| §2.1 whitespace-insensitive word assembly | ✗ no word assembly at all (see below) |
| §2.2 case-insensitivity | ✗ [bug 1.8](#18-packet-detection-is-case--and-position-exact--glinerkt34) |
| §3.5 `[…]` / `#param` (RS274) | ✗ absent (deferrable) |
| §5 block delete `/` | ✗ absent (deferrable) |
| §9 error reporting | ~ `GError` / `GNotIdentifierError` exist but are never constructed; errors surface as `GInt(-1)` sentinels |

**The word→command layer is disconnected.** `GCommandParser.parseLine` (`GLiner.kt:65`) is `private`
in a class with no other members, so nothing can call it — meaning `GCommandLine` and
`GNotIdentifierError` are never produced anywhere in the codebase. `GCodeReader` (`GLiner.kt:5-11`) is
an empty stub with its body commented out. Spec [§4](./GCODE_spec.md#4-identifiers-field-letters) and
[§5](./GCODE_spec.md#5-line-block-structure) are therefore unimplemented in practice, even though the
types for them exist.

---

## 3. Test coverage

**Current state — 295 tests, all green** (`:gcode:test`), up from 37. Test sources now mirror the
production packages: everything for `core.token` lives in
`src/test/kotlin/org/qw3rtrun/p3d/g/code/core/token/`.

| Suite | Tests | Covers |
|---|---|---|
| `GTokensTest` | 45 | token model: `rawText()` fidelity per kind, the number lexeme (identity, defaults, `Double` precision), quoted-string escaping, sealed-hierarchy membership (comments/separators are *not* `GElement`), `toSeq()`, `toToken()` conversions, scale-sensitive `GFloat` equality |
| `GTokenizerTest` | 140 | one nested group per token kind (letters, numbers incl. signed, non-canonical and malformed ones, quoted strings, tail/inline comments, expressions, separators incl. tabs and LF/CRLF/CR, checksum marker, unknown chars), iterator contract, all five input overloads, whole-line integration, 33 round-trip cases |
| `GLineIteratorTest` | 26 | line splitting, packet recognition, negative cases (`N` without `*`, `*` without `N`, `*` inside comment/string), zero, negative and large numbers, payload-reproduces-input property, line counting, exhaustion |
| `GSemanticsTest` | 23 | `GCommand.print()`, both constructors, line-type hierarchy, exhaustive `when` guard over `GLine`, `GCheckSumValue`, error messages |
| `GCorpusTest` | 8 | `marlin.gcode` (415 lines, parsed as checked out): tokenizes without failing, expected token kinds present, liner line count, one break token per `\n`, per-line round-trip, and the exact set of characters the lexer still does not understand |
| `XorCheckSumTest` | 17 | spec §8.2/§8.3 including the byte-by-byte worked example, known-line values, masking, order independence, streaming contract |
| `GDescriptionTest` | 11 | descriptor/field defaults, `optional`, mixed field types, equality |
| `GTest` | 9 | DSL: every `G`/`M`/`T` overload, parameter appending, emission order and count |
| decoder tests | 16 | unchanged (`Ok`, `TemperatureReported`, `FirmwareReport`) |

Removed in the refactor: `code/token/GTokenizerTest.kt` and `code/token/GCodeReaderTest.kt` — wrong
package, and between them 5 of 6 tests asserted nothing (`GCodeReaderTest` also dispatched on
`GCommandLine`/`GError`, which nothing produces). Their inputs are preserved as assertions in the new
suites; the two real-slicer corpora live on as round-trip cases in `GTokenizerTest`.

**Deliberate gaps** — no test asserts current behaviour for a known bug, so the fixes below stay free
to change it. Not covered on purpose: inline-comment *text* (1.2), `tail` contents (1.3),
`parseLines` (1.5), unterminated literals (1.7), case/whitespace-tolerant packet detection (1.8),
`GEmptyLine` production (1.9), adjacent-value `print()` (1.10), sequence re-iteration (1.11).

**Characterisation points** are marked in the tests and must be revisited with the fixes:

- [x] `GCorpusTest.each line round trips` — the quarantine list is empty as of
      [1.16](#116-number-lexemes-are-normalised-on-render--gtokenizerkt64-94-gtokenskt80-96--fixed);
      every line of the corpus now reproduces itself byte for byte
- [ ] `GCorpusTest.only the documented lexer gaps produce unknown tokens` pins the unknown-character
      set. `-` is gone with [1.1](#11-negative-numbers-do-not-lex--gtokenizerkt27-45--fixed); what is
      left (`. / \ ~ ! : | # ' ,`) all comes from the §2 gaps — bare rest-of-line strings and RS274
      parameters
- [ ] `GLineIteratorTest.a negative line number is indistinguishable from the missing-number sentinel`
      pins the ambiguity that [1.1](#11-negative-numbers-do-not-lex--gtokenizerkt27-45--fixed)
      created: `N-1` and a missing `N` value both report `GInt(-1)`. It must change with
      [§4.3](#43-priority-3--model-errors-instead-of-sentinels)

**Three pre-existing `GLineIteratorTest` assertions still freeze bugs** and must be updated together
with the fixes (line numbers are from before the sentinel test was added):

- [ ] `GLineIteratorTest.kt:116` — `tail == listOf(GInt(45))` locks in [1.3](#13-gpacketlinetail-is-off-by-one-and-can-crash--glinerkt51-glinerkt56)
- [ ] `GLineIteratorTest.kt:222` — `GInlineComment("feedrate)")` locks in [1.2](#12-inline-comments-corrupt-on-round-trip--gtokenizerkt147-160)
- [ ] `GLineIteratorTest.kt:88` — blank line as `GSimpleLine` locks in [1.9](#19-gemptyline-is-unreachable--gsemanticskt19-21-glinerkt37)

**Still untested, because unreachable or unimplemented:** `GCommandParser` (private, no callers),
`GCodeReader` (empty stub), `GTokenizer.parseLines` (bug 1.5), checksum verification/generation at
line level (does not exist).

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
[1.12](#112-crlf-yields-two-line-breaks--gtokenizerkt52-58) and
[1.13](#113-leading-dot-decimals-duplicate-a-digit--gtokenizerkt64-94).

---

## 4. Improvement plan

### 4.1 Priority 1 — stop corrupting data

The lookahead bugs shared one root cause: `ch` served both as "current character" and as
"pushed-back character" and was not always cleared. `number()` now keeps one `current` variable and
writes the pushed-back character to `ch` exactly once, on the way out.

- [x] Fix [1.12](#112-crlf-yields-two-line-breaks--gtokenizerkt52-58--fixed) CRLF double line break
- [x] Fix [1.13](#113-leading-dot-decimals-duplicate-a-digit--gtokenizerkt64-94--fixed) leading-dot digit duplication
- [x] Fix [1.14](#114-malformed-decimals-throw-out-of-the-lexer--gtokenizerkt74-91--fixed) multi-dot crash
- [x] Remove [1.6](#16-debug-println-in-the-hot-path--gtokenizerkt90--fixed) the `println`
- [x] Fix [1.1](#11-negative-numbers-do-not-lex--gtokenizerkt27-45--fixed) signed numbers
- [ ] Fix [1.2](#12-inline-comments-corrupt-on-round-trip--gtokenizerkt147-160) inline-comment delimiter
- [ ] Fix [1.3](#13-gpacketlinetail-is-off-by-one-and-can-crash--glinerkt51-glinerkt56) `tail` bounds + crash
- [x] Fix [1.4](#14-doubletotoken-destroys-precision--gtokenskt90-gtokenskt108--fixed) `BigDecimal.valueOf`
- [ ] Fix [1.5](#15-parselines-drops-line-terminators--gtokenizerkt16) `parseLines`
- [x] Fix [1.15](#115-tabs-are-unknown-tokens-and-gtab-is-dead--gtokenizerkt48-62--fixed) tab → `GTab`
- [x] Decide and implement
      [1.16](#116-number-lexemes-are-normalised-on-render--gtokenizerkt64-94-gtokenskt80-96--fixed)
      the number lexeme question
- [ ] Update the three assertions listed in [§3](#3-test-coverage) that still freeze 1.2, 1.3 and 1.9
      (the CRLF and round-trip characterisation points are now real assertions)

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
- [ ] Add tests for `GCommandParser`, `parseLines` and line-level checksums as those are fixed/wired
      up (4.3, 4.4)

### 4.3 Priority 3 — model errors instead of sentinels

- [ ] Replace `GInt(-1)` in `GLiner.kt:43,50,58` with nullable fields or dedicated `GError` subtypes
      (`GMissingLineNumber`, `GMalformedChecksum`). **Now due:**
      [1.1](#11-negative-numbers-do-not-lex--gtokenizerkt27-45--fixed) is fixed, so `-1` is a value a
      real line can carry, and `GLineIteratorTest.a negative line number is indistinguishable from
      the missing-number sentinel` pins the collision.
- [ ] Emit the [§7.3](./GCODE_spec.md#73-pairing-rule) errors: `N` without `*`, `*` without `N`.

### 4.4 Wire up or delete the orphans

- [ ] Connect `GCommandParser` into the pipeline (make `parseLine` public, or have `GLineIterator`
      produce `GCommandLine`) so [spec §4/§5](./GCODE_spec.md#4-identifiers-field-letters) is actually
      implemented — word assembly across whitespace, flag params, subcodes
- [ ] Delete the `GCodeReader` stub
- [ ] **Delete the `Double` path into `GFloat`** — the `constructor(float: Double)` on `GFloat`
      (`GTokens.kt:102-103`) and `fun Double.toToken()` (`GTokens.kt:119`). Neither has a production
      caller: the only use anywhere in the repo is
      `GTokensTest.float from a double does not expand the binary representation`, which exists only
      to pin [1.4](#14-doubletotoken-destroys-precision--gtokenskt90-gtokenskt108--fixed).

      *Why.* A `Double` cannot hold most authored decimals, and — unlike every other construction
      path after [1.16](#116-number-lexemes-are-normalised-on-render--gtokenizerkt64-94-gtokenskt80-96--fixed)
      — it gives the caller no way to state the lexeme the value should render as. `valueOf` keeps
      `1.05` readable but is equally faithful to accumulated error: `(0.1 + 0.2).toToken().rawText()`
      is `0.30000000000000004`, 19 characters against the ≤ 76 payload budget
      ([§1.3](./GCODE_spec.md#13-line-length)) and against the 3–5 decimals
      [§3.1](./GCODE_spec.md#31-numeric-values) asks generators to round to. That rounding is a
      call-site decision, and hiding it in a constructor is what made 1.4 possible.

      *Keep.* `constructor(int: Int)` (`Int` → `BigDecimal` is exact and lexeme-free by nature) and
      `BigDecimal.toToken()`. Callers that hold a `Double` pass
      `BigDecimal.valueOf(d).setScale(n, HALF_UP)`, a `String`, or a scaled `Int` — explicitly.

      *Tests.* The 1.4 test retires with the constructor; nothing replaces it, because the hazard
      is then gone by construction rather than guarded against. If a `Double` entry point is ever
      wanted, it belongs on the `G.kt` DSL or `GDescription` with an explicit scale parameter, and
      the rounding test belongs there too.
- [ ] Give `XorCheckSum` a caller: `GPacketLine.verify()` plus a framing encoder
      (`GCommand` → `N<n> …*<cs>`), which closes the [§8](./GCODE_spec.md#8-checksum-and-crc) gap and
      makes the `G.kt` DSL usable over a serial link
- [ ] Add line-number continuity tracking + `M110` handling for the host side
      ([§7.2](./GCODE_spec.md#72-semantics))

### 4.5 Hygiene

- [ ] Rename / split `GLiner.kt` — it holds three unrelated classes (`GCodeReader`, `GLineIterator`,
      `GCommandParser`) and none is named `GLiner`
- [ ] Make `GLineIterator.tokens` private (a public, already-consumed iterator is a trap)
- [ ] Drop the `GLineBreak.toString()` override — it hides the data-class output
      (`GLineBreak("\r\n").toString()` → `"GLineBreak"`), which is exactly the info you want in a
      failure message
- [ ] Make `GTokenizer` an `object` or top-level functions — it is stateless
- [ ] Tighten `GCommand.head` to `GLetter` so `GChecksum` cannot be a command head
- [ ] Rename `GFloat.float` — the property holds a `BigDecimal`, so the name reads as a lie
      (`GFloat("1.0").float.compareTo(…)`); `value` or `decimal` says what it is. Mechanical and
      small (4 test lines, no production call sites). Do it together with the `Double`-constructor
      removal in [§4.4](#44-wire-up-or-delete-the-orphans) so `GFloat`'s public shape changes once
- [x] Document that `GFloat` equality is scale-sensitive (`GFloat("1.0") != GFloat("1.00")`) — correct
      for round-trip fidelity, surprising for value comparison. The KDoc on `GNumber` now covers the
      whole lexeme-identity rule, of which the scale sensitivity is one case.
- [ ] Drop the stray `;` line terminators and unused `min` import left over from the migration

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
