---
name: low-level-protocol-dev
description: Write or review low-level byte/text protocol code in this repo - tokenizers, line framing, checksums and CRC, serial/TCP transports, encoders and decoders. Use for any Kotlin under :gcode that must stay portable to JS/TS, C or Rust. Enforces a dependency-free, simple-subset Kotlin style (iterators, arrays, integer math, explicit state machines) and rules out regex, Optional, BigDecimal, streams, coroutines and third-party utilities in the protocol core.
---

# Low-level protocol development

## Why this style

Two reasons, both concrete.

**Portability.** The protocol core is planned to be re-implemented in JS/TS, C and Rust. Anything
written in the intersection of those four languages transliterates; anything written in Kotlin's
upper half gets rewritten from scratch, and the rewrite is where behaviour drifts. So the core is
written in that intersection: iterators, integer and bitwise math, logic operators, records, arrays,
lists, hand-written state machines. Nothing else.

**Byte-exactness.** Regex, locale-aware string helpers and Unicode-wide character predicates all
decide things a wire protocol has to state explicitly. Look at the bug list in
[the `:gcode` review record](../../../doc/todos/99-completed.md): almost every confirmed bug is a
hidden abstraction doing something the spec did not ask for, or a lookahead the author could not see. Explicit code is not
verbosity here, it is the only way the behaviour is reviewable against a spec.

## The layering rule

| Layer | Where | May depend on |
|---|---|---|
| **Portable core** | `gcode/src/main/kotlin/.../code/core/**` — tokens, tokenizer, liner, checksums, encoders | Kotlin stdlib basics only (see the allow-list). No JVM library, no third-party, no other module |
| **Writing facade** | `gcode/src/main/kotlin/.../code/dsl/**` — `G.kt`, `GWords.kt`, `GSender` | The core. **Not governed by this skill** — see below |
| **Domain edge** | `gcode/.../decoder/**`, `gcode/.../event/**` | The core, plus `:backend:core` message types |
| **Transport / app** | `:backend:terminal` (Netty), `:backend:api` (WebFlux), `:app` | Anything. Reactor, Spring, coroutines all live here |

**`code/dsl` is not this skill's territory, and several rules below invert there.** It is the
host-side facade for *writing* G-code, it is never ported, and full Kotlin — infix and extension
functions, `BigDecimal`, JVM types, and `require(...)` throwing on a bad argument — is correct in
it. Load [`gcode-dsl-dev`](../gcode-dsl-dev/SKILL.md) for that package instead. Being under
`code/` does not make a file part of the portable core; only `code/core/**` is.

Keep the JVM at the edges. A `Mono`, a `Flux`, a Spring bean or a coroutine must never appear inside
the core: the core is a pure function from bytes to tokens and back, driven by whatever transport the
edge happens to be. That is what makes a port possible at all — you re-implement the core and keep
the host's own I/O layer.

## Language surface

### Use

- `class`, `data class` (→ struct/record), `enum class`, `object` for singletons
- `sealed interface` / `sealed class` for a **closed** set of variants (→ tagged union, discriminated
  union, Rust `enum`). Keep the variant list short and flat.
- `interface` with plain methods (→ vtable, function pointer, trait)
- top-level functions; plain member functions
- hand-written `Iterator<T>`: `hasNext()` / `next()`, one field of lookahead
- `Array`, `ByteArray`, `IntArray`, `CharArray`, `List`, `MutableList`/`ArrayList`, `StringBuilder`
- `if`, `when`, `while`, `do/while`, `for` over an index range, `break`, `continue`
- `Int`, `Long`, `Char`, `Byte`, `Boolean`; `and or xor inv shl shr ushr`; `+ - * / %`
- `String` by index: `length`, `get`, `substring`, explicit character comparison
- local `var`, explicit `null` checks, nullable types for "absent"

### Do not use in the core

| Instead of | Write | Why |
|---|---|---|
| `Regex`, `java.util.regex.Pattern` | a hand-rolled scanner or state machine | no equivalent semantics across four languages; hides backtracking cost; unreviewable against a byte spec |
| `java.util.Optional` | a nullable type, or a variant of a sealed result | Optional is JVM-only and allocates; nullability is native in all four targets |
| `org.apache.commons.*`, Guava, any utility library | a five-line private helper | `commons-lang3` is on every module's classpath via `p3d.java-conventions.gradle` — that is not a licence to use it in the core |
| `BigDecimal`, `BigInteger` | the lexeme `String` plus `Int` mantissa/scale if arithmetic is needed | arbitrary-precision decimal does not exist in C, Rust or JS without a library |
| `Double`, `Float` in a protocol path | decimal text, or a scaled integer | binary floating point cannot hold authored decimals; rounding is a call-site decision, never a constructor's |
| `Sequence`/`Stream` chains, `map`/`filter`/`flatMap`/`groupBy` over hot paths, `java.util.stream`, `kotlin.streams.*` | an explicit loop or a hand-written iterator | pipeline semantics (laziness, `constrainOnce`) differ per language; loops transliterate exactly |
| coroutines, `Flow`, Reactor `Mono`/`Flux` | a synchronous iterator the edge drives | concurrency belongs to the transport layer |
| `Char.isDigit()`, `isLetter()`, `isWhitespace()` | explicit ASCII range checks | these accept the whole Unicode category. `isLetter()` is why `GЯ1` *used* to lex as a letter, and `isDigit()` + `toIntOrNull` is why `X1١` used to lex as `GInt(11)` — both fixed in todo 01, so `GTokenizer.kt` is now the worked example rather than the counter-example. The wire format is 7-bit ASCII ([spec §1.1](../../../doc/specs/GCODE_spec.md#11-character-set-and-encoding)) |
| `String.trim()`, `equals(ignoreCase = true)`, `uppercase()` | explicit ASCII comparison and folding | locale- and Unicode-dependent; a Turkish locale changes `i`/`I` |
| exceptions for malformed input | a variant carrying the offending bytes | see *Errors are values* |
| reflection, annotations, Lombok, Jackson, Spring | plain constructors and plain functions | none of it exists in a port |
| operator overloading, delegated properties, `inline`/`reified` generics, extension-function DSLs, deep generic hierarchies | plain functions and concrete types | the further from a C-shaped program, the more the port is a rewrite |

Generics: one type parameter, no variance annotations, no bounds beyond a single interface. If you
need more, you probably want a sealed variant set instead.

## Patterns that work here

### One field of lookahead, cleared exactly once

The canonical shape, as in `GTokenizerIterator`: a single `ch` field holding the character that was
read but not consumed.

```kotlin
class Scanner(private val chars: Iterator<Char>) : Iterator<Token> {
    private var ch: Char? = null                       // the one lookahead slot

    override fun hasNext() = ch != null || chars.hasNext()

    override fun next(): Token {
        ch = ch ?: chars.next()
        return when {
            isDigit(ch!!) -> number(ch!!)
            // ...
            else -> { val c = ch!!; ch = null; Unknown(c) }
        }
    }
}
```

**The pitfall, three times over in this repo's history** (TODO 1.12, 1.13, 1.14): the field served
both as "current character" and as "pushed-back character", and a path forgot to clear it — CRLF
produced two line breaks, `X.5` duplicated its digit. The rule that fixed it: a sub-scanner keeps one
local `current` variable, and writes `ch` **exactly once, on the way out**, with whatever stopped it
(`null` at end of input). Never assign `ch` inside a scanning loop.

### Classify bytes explicitly

```kotlin
private fun isDigit(c: Char) = c >= '0' && c <= '9'
private fun isUpper(c: Char) = c >= 'A' && c <= 'Z'
private fun isLower(c: Char) = c >= 'a' && c <= 'z'
private fun isLetter(c: Char) = isUpper(c) || isLower(c)
private fun asciiUpper(c: Char) = if (isLower(c)) (c.code - 32).toChar() else c
```

Six lines, no locale, identical in TS/C/Rust.

### Errors are values

A lexer, framer or decoder **never throws on malformed input** — malformed input is the normal case on
a serial link. Emit a variant that carries the original bytes and, where you can, the offset:
`GUnknown("1.2.3")` rather than the `NumberFormatException` that used to escape the tokenizer
(TODO 1.14). Two consequences worth stating in tests: the stream keeps going after the bad token, and
the bad token still re-prints byte-identically.

Reserve exceptions for programmer error — `next()` past the end is `NoSuchElementException`, and that
is the iterator contract, not a protocol error.

### Round-trip fidelity is an invariant, not a nicety

Every token carries `rawText()`, and re-printing a token stream reproduces its input byte for byte.
This is what lets you diff a parse against a real capture. It also decides API shape: a number token
carries the **lexeme** it was read from alongside its value, because `+5`, `01`, `.5` and `1.` all
have a canonical form that differs from what was written (TODO 1.16). When you add a token kind, add
its round-trip case in the same commit.

### Streaming accumulators for checksums and CRC

```kotlin
interface CheckSumCalculator { fun add(ch: Char); fun get(): GInt }
```

One byte in, integer state, mask on the way out (`XorCheckSum`). No buffering, no allocation, no
second pass — so the same code works on a growing serial buffer and on a whole file. Write CRC16 the
same way; a table, if you use one, is a `IntArray` literal, not a generated structure.

### Framing: never index from the end to skip a terminator

`GPacketLine.tail` computed `subList(i + 1, tokens.size - 1)`, assuming a trailing line break. Input
without one silently dropped a real token, and a short line threw (TODO 1.3). Test the terminator
explicitly, and make every buffer-boundary case — empty, no terminator, terminator only, split
mid-token — a named test.

### Explicit state machines

For anything with modes (quoted string, nested comment, escape handling, resend protocol), use an
`enum class` plus a `when` over `(state, byte)`. No pushdown beyond a small `Int` nesting counter. It
reads as a table, ports as a table, and reviews against the spec as a table.

## Testing

Follow the `tdd` skill for the loop; these are the protocol-specific rules on top.

- **Red first, and check the reason.** Run the new tests before the fix and read the failures. 23
  failures all in the number cases is a good red; a compile error is not a red.
- **Every codec needs three kinds of test:** round-trip fidelity (parameterised `@ValueSource` of raw
  lines), no-throw over arbitrary/hostile input, and a **real capture** — `gcode/src/test/resources/marlin.gcode`
  is the fixture, and writing the corpus test is what surfaced TODO 1.12 and 1.13.
- **Plain JUnit 5 assertions** (`assertEquals`, `assertTrue`), backtick test names stating one
  behaviour. **No mocks** — protocol code is pure functions over bytes, there is nothing to mock.
- **Assert bytes, not shapes.** `assertEquals(listOf(GLetter('E'), GInt(-5)), tokens("E-5"))` beats
  asserting the list size and a type.
- **Never let a test silently freeze a bug.** If current behaviour must stand for now, say so in a
  comment naming the TODO item and call it a characterisation point, so the fix knows to change it.
- Verify by running and quote real output. Every finding in the review record is an observed run
  result, not a reading.

## Documentation duty

- [`GCODE_spec.md`](../../../doc/specs/GCODE_spec.md) is the syntax authority, dialect-neutral, with a
  §10 dialect matrix. Appendix B maps it to the module and lists **current deviations "verified by
  running"** — when observable behaviour changes, that list changes in the same commit, or it becomes
  a lie.
- [`doc/todos/`](../../../doc/todos/00-index.md) is the work queue: one numbered file per piece of
  work, in dependency order, plus [99-completed.md](../../../doc/todos/99-completed.md) as the
  record. A finding gets: the symptom as observed output, the spec section it violates, the file and
  lines, and a checkbox. A fix flips the checkbox and records what covers it.
- Cite the spec section in code comments where a rule is non-obvious (`// spec 3.1: at least one
  digit somewhere in the number`). That comment is what a reviewer — or a port author — checks against.

## Translation notes

| Kotlin here | TS/JS | C | Rust |
|---|---|---|---|
| `data class` | object literal / `interface` | `struct` | `struct` with `derive` |
| `sealed interface` + `when` | tagged union + `switch` on `kind` | `struct` with a tag `enum` + `switch` | `enum` + `match` |
| `Iterator<T>` | `Iterator`/generator | struct + `next()` returning a bool | `Iterator` trait |
| `StringBuilder` | array of chars + `join` | growable `char[]` + length | `String::push` |
| `Char` | `number` (code unit) | `char`/`uint8_t` | `u8`/`char` |
| nullable `T?` | `T \| null` | sentinel or out-param + bool | `Option<T>` |
| `and`/`or`/`xor`/`shl` | `&`/`\|`/`^`/`<<` | same | same |

Write what a competent C programmer would write in **one** function: locals, explicit control flow,
index arithmetic, a single pass. "If a function has no obvious C shape, reshape it until it does" —
*reshape*, not decompose. Splitting a scan across small helpers that each name one line of code is
the wrong direction here: it allocates, it hides the flow, and it is the thing a port has to undo.
One screen of C is the size to aim for, and that is considerably larger than one screen of idiomatic
Kotlin.

Density is bought with comments, not paid for with them: cite the spec section beside every
non-obvious rule (`// spec 3.1: at least one digit somewhere in the number`) so dense code stays
reviewable against the spec.

## Review checklist

Before calling protocol work done, grep the core for the things that do not port:

```bash
grep -rn 'Regex\|java\.util\.regex\|Optional\|StringUtils\|BigDecimal\|BigInteger' <core>
grep -rn 'isDigit()\|isLetter()\|isWhitespace()\|ignoreCase\|\.trim()' <core>
grep -rn 'java\.util\.stream\|kotlin\.streams\|Mono\|Flux\|suspend\|Flow<' <core>
grep -rn 'Double\|Float' <core>
```

Then check by hand:

- [ ] no import outside `kotlin.*` in the portable core
- [ ] every new token/variant has a round-trip test
- [ ] malformed input yields a variant, never a thrown exception
- [ ] no buffer index computed relative to `size` to skip a terminator
- [ ] lookahead written once per scan, on exit
- [ ] spec section cited for every non-obvious rule; Appendix B and the TODO updated

## Known debt in this repo

Live examples of what this skill rules out, so nobody copies them as precedent:

- `decoder/OkDecoder.kt`, `TemperatureReportedDecoder.kt`, `FirmwareReportDecoder.kt` —
  `java.util.regex`; these are the *edge*, but the same lines are what a port has to reimplement, and
  the regexes are unreviewable against
  [spec §9](../../../doc/specs/GCODE_spec.md#9-error-handling). Note
  `CapabilityReportDecoder.kt` is **not** a regex user — it is a prefix guard plus `split(":")`; the
  four-file version of this list was wrong and sent one reviewer looking for a pattern that is not
  there.
- `decoder/**` — `java.util.Optional` on all eight decoders *and* on the `GEventDecoder` fun
  interface, which extends `java.util.function.Function` / `Predicate`, so the JVM types are in the
  supertype list rather than just the return position.
- `decoder/CapabilityReportDecoder.kt` — `org.apache.commons.lang3.StringUtils` for `isNotBlank`
  (`:21`) and `isNumeric` (`:29`), both a two-line loop. This is the **only** `StringUtils` user;
  `FirmwareReportDecoder.kt` and `WaitReceivedDecoder.kt` are clean.
- `decoder/**` — `ignoreCase = true` at 8 sites in 5 files, plus `.trim()` in `OkDecoder.kt:12` and
  `FirmwareReportDecoder.kt:22`. Locale-dependent, and trimming the Unicode whitespace set rather
  than the four characters [§2.1](../../../doc/specs/GCODE_spec.md#21-whitespace) defines.
- `code/core/token/GTokens.kt`, `GTokenizer.kt` — `java.math.BigDecimal` is the core's only external
  type, and it is in the hot path.
- `:gcode` depends on `:backend:core` for event types, so the module as a whole is not extractable
  yet; only `code/core/**` is close.

These are tracked in [`doc/todos/`](../../../doc/todos/00-index.md) — mainly
[01](../../../doc/todos/01-ascii-and-lexer-portability.md),
[02](../../../doc/todos/02-number-representation.md) and
[06](../../../doc/todos/06-decoder-edge-portability.md). Do not add to the list.
