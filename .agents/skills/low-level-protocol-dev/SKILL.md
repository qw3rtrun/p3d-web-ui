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
[the `:gcode` review record](../../../doc/gcode-completed.md): almost every confirmed bug is a
hidden abstraction doing something the spec did not ask for, or a lookahead the author could not see. Explicit code is not
verbosity here, it is the only way the behaviour is reviewable against a spec.

## The layering rule

| Layer | Where | May depend on |
|---|---|---|
| **Portable core** | `gcode/src/main/kotlin/.../code/core/**` — tokens, tokenizer, block reader, checksums, encoders | Kotlin stdlib basics only (see the allow-list). No JVM library, no third-party, no other module |
| **Writing facade** | `gcode/src/main/kotlin/.../code/dsl/**` — `G.kt`, `GWords.kt`, `GSender` | The core. **Not governed by this skill** — see below |
| **Domain edge** | `gcode/.../marlin/**` (commands, `event/`), `gcode/.../protocol/**` (the base-protocol replies) | The core, and nothing else. This module depends on **no** backend module: the application's event model is reached by a bridge in `:backend:terminal`, never by implementing its interfaces here |
| **Transport** | `:backend:terminal` (Reactor, reactor-netty) | Anything on the JVM — but governed by `reactive-transport-dev`, not by this skill |
| **App** | `:backend:api` (WebFlux), `:app` | Anything. Reactor, Spring, coroutines all live here |

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
- extension-function DSLs over std-library or in-module types: each is a constructor or a plain
  call a port spells as a function (`"057".asGInt()`, `tokens.valueIndex(i)`)

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
| operator overloading, delegated properties, `inline`/`reified` generics, deep generic hierarchies | plain functions and concrete types | the further from a C-shaped program, the more the port is a rewrite |

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

`GFramedBlock.tail` computed `subList(i + 1, tokens.size - 1)`, assuming a trailing line break. Input
without one silently dropped a real token, and a short block threw (TODO 1.3). Test the terminator
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
- [GitHub issue #18](https://github.com/qw3rtrun/p3d-web-ui/issues/18) is the work queue: one numbered issue per piece of
  work, in dependency order, plus [doc/gcode-completed.md](../../../doc/gcode-completed.md) as the
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

- ~~**`java.util.regex` in seven reply decoders.**~~ **Done** — there is none left anywhere in
  `gcode/src/main`, and the only mention of it is the prose in `GRsScan.kt` saying why. Every
  decoder walks the line with that file's primitives: `startsWithAscii`, `sameAscii`,
  `equalsAscii`, `blanksEnd`, `digitsEnd`, `numberEnd`, `asciiTrim`. Five of the seven carried
  `(?>...)` atomic groups, which exist in Java and PCRE and in neither Rust's `regex` crate nor C,
  so a port could only have re-derived them.

  **Read this before writing a new decoder.** The conversion turned up rules that were encoded in
  patterns and tested by nothing: an axis endstop name carries one optional character and it may be
  a letter; `FIRMWARE_NAME:`'s anchor is case-sensitive while the rest of the module folds; the
  fields of an advanced `ok` are separated by a **space** and not a blank; a sensor temperature may
  be signed while a `@` power may not; and `OkRsDecoder`'s own KDoc example had been wrong since it
  was written. Each is a test now rather than a `?` in a pattern — and each was found by
  **characterising first**: the cases go in green against the code as it stands, and only then does
  the code change.
- ~~**`ignoreCase = true` at 11 sites in 8 files**, and `.trim()` at 32 sites in 13.~~ **Done** —
  both are zero in this module's code now; what is left of them is the prose in `GTokens.kt`,
  `GHead.kt` and `GRsScan.kt` saying why they are not used. `GRsScan.kt` is the one copy of the
  rule: `startsWithAscii`, `sameAscii` and `equalsAscii` fold through the token layer's
  `asciiFold`, and `asciiTrim`/`edgeEnd`/`edgeStart` strip [§2.1](../../../doc/specs/GCODE_spec.md#21-whitespace)'s
  two blanks and [§1.2](../../../doc/specs/GCODE_spec.md#12-end-of-line)'s two terminators and
  nothing else. `GRsScanTest` pins the difference from `trim()` on U+000C, U+00A0 and U+2028.
  **`asciiTrim` still allocates**, which is the half not fixed: a decoder that scanned indices
  instead of trimming first would do both in one walk, and eight decoders in a chain each trim the
  same line.
- `code/core/token/GTokens.kt`, `GTokenizer.kt` — `java.math.BigDecimal` is the core's only external
  type, and it is in the hot path.

**This module depends on nothing, and that is now load-bearing in the other direction.** It used to
be listed here as debt — `:gcode` borrowed `:backend:core`'s event types, so the module was not
extractable — and the dependency has since been removed *and reversed*: `:backend:api` declares
`api project(':gcode')`, so the application reads a reply as the `GRs` this module decoded.
`doc/sub_boundaries.txt` rules 6-8 state it. What that buys is a rename here being visible all the
way to a TypeScript client, and what it costs is that a rename here is visible all the way to a
TypeScript client — `EventStreamContractTest` in `:frontend:web-ui` pins the class names that reach
the browser. Check it before renaming anything under `marlin/event/` or `protocol/`.

These are tracked in [the issue queue](https://github.com/qw3rtrun/p3d-web-ui/issues/18) — mainly
[02](https://github.com/qw3rtrun/p3d-web-ui/issues/4) and
[14](https://github.com/qw3rtrun/p3d-web-ui/issues/16), which inherited the portability pass when
[06](https://github.com/qw3rtrun/p3d-web-ui/issues/8) was closed as superseded. Do not add to the
list.
