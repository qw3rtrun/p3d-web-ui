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

`:gcode:compileTestKotlin` and `:gcode:test` are **green** — **242 tests**, 0 failures, 0 skipped
(37 at the time of the review; see [§3](#3-test-coverage) for the breakdown of the test pass).

No production code has been touched yet — every bug in [§1](#1-confirmed-bugs) is still open, and the
new tests were written specifically not to depend on the buggy behaviour.

- [ ] **Refresh `.junie/GCODEK.md`** — §1 "Current Status & Blocker Analysis" is stale. It claims
  `:gcode:compileKotlin` fails with unresolved `GCommand` / `GCommandLine` and leaves Phase 1
  unchecked; `GSemantics.kt` now defines both and Phase 1 is effectively complete.

---

## 1. Confirmed bugs

Ordered by impact. Every symptom is an observed run result.

### 1.1 Negative numbers do not lex — `GTokenizer.kt:27-45`

`-` and `+` fall through the `when` to `GUnknown`:

```
G1 E-5 F1800
→ [GLetter(G), GInt(1), GSpace, GLetter(E), GUnknown(-), GInt(5), GSpace, GLetter(F), GInt(1800)]
```

Spec [§3.1](./GCODE_spec.md#31-numeric-values) requires the optional sign to be part of the number.
Every retraction and relative move in real slicer output is affected — including the fixture already
in the repo (`GTokenizerTest.kt:20`, `G1 E-5 F1800`).

- [ ] Lex `[+|-]` as part of `GNumber`, or introduce an explicit sign token that the number builder
      consumes. Decide how `rawText()` should re-print `+5` (preserve or normalise) and test it.

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

### 1.4 `Double.toToken()` destroys precision — `GTokens.kt:90`, `GTokens.kt:108`

`GFloat(float: Double) : this(BigDecimal(float))` uses the exact binary expansion:

```
1.05.toToken().rawText()               → 1.0500000000000000444089209850062616169452667236328125
GFloat(BigDecimal.valueOf(1.05))       → 1.05
```

Any line generated through the `Double` path is unusable.

- [ ] Use `BigDecimal.valueOf(double)` (or `double.toString()`) in the `Double` constructor.

### 1.5 `parseLines` drops line terminators — `GTokenizer.kt:16`

`flatMap { it.asSequence() }` concatenates the strings without re-inserting `\n`:

```
["G28", "M104 S200"]   →   G28M104 S200
```

Anything fed from `BufferedReader.lineSequence()` / `File.readLines()` silently merges commands into
one line.

- [ ] Re-insert the terminator between elements (and decide whether the caller or the tokenizer owns
      the choice of `\n` vs `\r\n`).

### 1.6 Debug `println` in the hot path — `GTokenizer.kt:90`

`println(str)` inside `number()` fires for every numeric token. It is currently polluting test
stdout (bare `1`, `1500`, `28` … lines in the JUnit XML output).

- [ ] Delete it.

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

### 1.12 CRLF yields two line breaks — `GTokenizer.kt:52-58`

`space('\r')` returns `GLineBreak("\r\n")` but leaves the consumed `'\n'` in the `ch` lookahead
field, so the next call emits a second `GLineBreak("\n")`:

```
marlin.gcode (415 CRLF lines)  →  GLineIterator produces 703 lines
whole-file rawText             →  8745 chars out of 8454 in  (one extra \n per CRLF)
```

Every line is counted twice on Windows-authored files and on serial input that uses CRLF, which also
breaks line numbering and any framing built on it (spec §7). Same root cause as
[1.13](#113-leading-dot-decimals-duplicate-a-digit--gtokenizerkt64-94): a lookahead that is not
cleared.

- [ ] Clear `ch` when returning the `\r\n` break.

### 1.13 Leading-dot decimals duplicate a digit — `GTokenizer.kt:64-94`

When `number()` starts on `'.'`, it reads the first digit into `ch`, appends it to the buffer and
never clears `ch` — so the digit is emitted a second time as its own token:

```
X.5   →  [GLetter(X), GFloat(0.5), GInt(5)]      reprint: X0.55
```

Real files hit this — `marlin.gcode` lines `G92 .1 ;TODO` and `M851 X0.20 Y.40` are the only two
lines in the corpus that fail round-trip.

- [ ] Clear the lookahead after consuming the first fractional digit.

### 1.14 Malformed decimals throw out of the lexer — `GTokenizer.kt:74-91`

A second decimal point is appended to the buffer and passed to `BigDecimal`, which throws:

```
X1.2.3   →  EX: java.lang.NumberFormatException: Character array contains more than one decimal point.
```

Spec [§9](./GCODE_spec.md#9-error-handling) wants a *lexical error* token, not an unhandled
exception escaping the tokenizer. Related: `X1.` silently becomes `GFloat(1)`, dropping the trailing
dot and breaking round-trip.

- [ ] Reject a second `.` at lex time (emit an unknown/error token) and never let `BigDecimal` throw
      through the iterator.

### 1.15 Tabs are unknown tokens and `GTab` is dead — `GTokenizer.kt:48-62`

`space()` only handles `' '`, `'\n'` and `'\r'`; everything else falls through to `GUnknown`, so:

```
G1\tX1   →  [GLetter(G), GInt(1), GUnknown(\t), GLetter(X), GInt(1)]
```

`GTab` is therefore unreachable — the second dead token type after `GEmptyLine`
([1.9](#19-gemptyline-is-unreachable--gsemanticskt19-21-glinerkt37)). Spec
[§2.1](./GCODE_spec.md#21-whitespace) classifies tab as whitespace.

- [ ] Map `'\t'` to `GTab`.

### 1.16 Leading zeros are lost — `GTokenizer.kt:64-94`

`GInt` holds an `Int`, so the lexeme's leading zeros disappear:

```
G01   →  [GLetter(G), GInt(1)]      reprint: G1
```

Command numbers are numerically equivalent (spec [§4.1](./GCODE_spec.md#41-command-letters)), but the
`rawText()` round-trip invariant does not hold.

- [ ] Decide explicitly: keep the lexeme (so round-trip holds) or document `GInt` as normalising.

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
| §3.1 signed numbers | ✗ [bug 1.1](#11-negative-numbers-do-not-lex--gtokenizerkt27-45) |
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

**Current state — 242 tests, all green** (`:gcode:test`), up from 37. Test sources now mirror the
production packages: everything for `core.token` lives in
`src/test/kotlin/org/qw3rtrun/p3d/g/code/core/token/`.

| Suite | Tests | Covers |
|---|---|---|
| `GTokensTest` | 39 | token model: `rawText()` fidelity per kind, quoted-string escaping, sealed-hierarchy membership (comments/separators are *not* `GElement`), `toSeq()`, `toToken()` conversions, scale-sensitive `GFloat` equality |
| `GTokenizerTest` | 95 | one nested group per token kind (letters, numbers, quoted strings, tail/inline comments, expressions, separators, checksum marker, unknown chars), iterator contract, all five input overloads, whole-line integration, 19 round-trip cases |
| `GLineIteratorTest` | 25 | line splitting, packet recognition, negative cases (`N` without `*`, `*` without `N`, `*` inside comment/string), zero and large numbers, payload-reproduces-input property, line counting, exhaustion |
| `GSemanticsTest` | 23 | `GCommand.print()`, both constructors, line-type hierarchy, exhaustive `when` guard over `GLine`, `GCheckSumValue`, error messages |
| `GCorpusTest` | 7 | `marlin.gcode` (415 lines): tokenizes without failing, expected token kinds present, liner line count, per-line round-trip, and the exact set of characters the lexer still does not understand |
| `XorCheckSumTest` | 17 | spec §8.2/§8.3 including the byte-by-byte worked example, known-line values, masking, order independence, streaming contract |
| `GDescriptionTest` | 11 | descriptor/field defaults, `optional`, mixed field types, equality |
| `GTest` | 9 | DSL: every `G`/`M`/`T` overload, parameter appending, emission order and count |
| decoder tests | 16 | unchanged (`Ok`, `TemperatureReported`, `FirmwareReport`) |

Removed in the refactor: `code/token/GTokenizerTest.kt` and `code/token/GCodeReaderTest.kt` — wrong
package, and between them 5 of 6 tests asserted nothing (`GCodeReaderTest` also dispatched on
`GCommandLine`/`GError`, which nothing produces). Their inputs are preserved as assertions in the new
suites; the two real-slicer corpora live on as round-trip cases in `GTokenizerTest`.

**Deliberate gaps** — no test asserts current behaviour for a known bug, so the fixes below stay free
to change it. Not covered on purpose: signed numbers (1.1), inline-comment *text* (1.2), `tail`
contents (1.3), `Double.toToken()` (1.4), `parseLines` (1.5), unterminated literals (1.7),
case/whitespace-tolerant packet detection (1.8), `GEmptyLine` production (1.9), adjacent-value
`print()` (1.10), sequence re-iteration (1.11), CRLF (1.12), leading-dot/multi-dot/trailing-dot
decimals (1.13, 1.14), tabs (1.15), leading zeros (1.16).

**Two characterisation points** are marked in the tests and must be revisited with the fixes:

- [ ] `GCorpusTest.each line round trips` quarantines the two leading-dot lines — the list must
      become empty with [1.13](#113-leading-dot-decimals-duplicate-a-digit--gtokenizerkt64-94)
- [ ] `GCorpusTest.only the documented lexer gaps produce unknown tokens` pins the unknown-character
      set — it shrinks as [1.1](#11-negative-numbers-do-not-lex--gtokenizerkt27-45) and the §2 gaps
      are implemented

**Three pre-existing `GLineIteratorTest` assertions still freeze bugs** and must be updated together
with the fixes:

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

The three lookahead bugs (1.12, 1.13, and the `X1.` case of 1.14) share one root cause: `ch` is used
both as "current character" and "pushed-back character" and is not always cleared. Fix them together.

- [ ] Fix [1.12](#112-crlf-yields-two-line-breaks--gtokenizerkt52-58) CRLF double line break
- [ ] Fix [1.13](#113-leading-dot-decimals-duplicate-a-digit--gtokenizerkt64-94) leading-dot digit duplication
- [ ] Fix [1.14](#114-malformed-decimals-throw-out-of-the-lexer--gtokenizerkt74-91) multi-dot crash
- [ ] Fix [1.1](#11-negative-numbers-do-not-lex--gtokenizerkt27-45) signed numbers
- [ ] Fix [1.2](#12-inline-comments-corrupt-on-round-trip--gtokenizerkt147-160) inline-comment delimiter
- [ ] Fix [1.3](#13-gpacketlinetail-is-off-by-one-and-can-crash--glinerkt51-glinerkt56) `tail` bounds + crash
- [ ] Fix [1.4](#14-doubletotoken-destroys-precision--gtokenskt90-gtokenskt108) `BigDecimal.valueOf`
- [ ] Fix [1.5](#15-parselines-drops-line-terminators--gtokenizerkt16) `parseLines`
- [ ] Fix [1.15](#115-tabs-are-unknown-tokens-and-gtab-is-dead--gtokenizerkt48-62) tab → `GTab`
- [ ] Remove [1.6](#16-debug-println-in-the-hot-path--gtokenizerkt90) the `println`
- [ ] Update the three assertions and two characterisation points listed in [§3](#3-test-coverage)

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
      (`GMissingLineNumber`, `GMalformedChecksum`). `-1` is indistinguishable from a parsed `N-1` once
      [1.1](#11-negative-numbers-do-not-lex--gtokenizerkt27-45) is fixed.
- [ ] Emit the [§7.3](./GCODE_spec.md#73-pairing-rule) errors: `N` without `*`, `*` without `N`.

### 4.4 Wire up or delete the orphans

- [ ] Connect `GCommandParser` into the pipeline (make `parseLine` public, or have `GLineIterator`
      produce `GCommandLine`) so [spec §4/§5](./GCODE_spec.md#4-identifiers-field-letters) is actually
      implemented — word assembly across whitespace, flag params, subcodes
- [ ] Delete the `GCodeReader` stub
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
- [ ] Document that `GFloat` equality is scale-sensitive (`GFloat("1.0") != GFloat("1.00")`) — correct
      for round-trip fidelity, surprising for value comparison
- [ ] Drop the stray `;` line terminators and unused `min` import left over from the migration

---

## Appendix — probe output

Verbatim results from the temporary probe test (deleted after the review):

```
roundtrip inline comment      | false
inline comment raw            | G1 (feedrate)) F1500
unterminated string raw       | M"asd"
unterminated inline raw       | Mabcc
negative value tokens         | [GLetter(G), GInt(1), GSpace, GLetter(E), GUnknown(-), GInt(5), GSpace, GLetter(F), GInt(1800)]
double toToken rawText        | 1.0500000000000000444089209850062616169452667236328125
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
