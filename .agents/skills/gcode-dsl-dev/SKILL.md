---
name: gcode-dsl-dev
description: Write or review the G-code building DSL in this repo - the `code/dsl` package (`G.kt`, `GWords.kt`, `GSender`), the host-side facade for *writing* G-code. Use for command and parameter builders, line/block builders, comments, the Java-facing sink, and anything measured by GDslCorpusTest. This layer is deliberately the INVERSE of the portable core: full Kotlin, infix and extension functions, BigDecimal, JVM types and throwing on a bad call site are all correct here. Do NOT use for `code/core` (tokenizer, liner, checksums, encoder) - that is `low-level-protocol-dev`.
---

# The G-code building DSL

## Which layer am I in

This skill governs **one package**: `gcode/src/main/kotlin/org/qw3rtrun/p3d/g/code/dsl/**` —
`G.kt` (commands, lines, `GSender`) and `GWords.kt` (parameter words). Its tests are
`dsl/GTest.kt`, `dsl/GDslCorpusTest.kt` and `dsl/GSenderJavaInteropTest.java`.

Everything under `code/core/**` is the **portable core** and is governed by
[`low-level-protocol-dev`](../low-level-protocol-dev/SKILL.md) instead. If your edit touches both,
they are two commits under two standards, not one.

## The inversion — read this before applying any core habit

The core is written to transliterate into JS/TS, C and Rust. **The DSL is not ported at all** — a
port re-implements `code/core` and writes its own facade. So portability stops at the package
boundary, and the core's rules do not merely relax here, several of them *reverse*:

| `low-level-protocol-dev` forbids | `code/dsl` requires | Why the reversal is correct |
|---|---|---|
| **Errors are values; never throw on bad input** | `require(...)` → `IllegalArgumentException` | A tokenizer's input arrives off a serial link, where malformed is the normal case. A builder's input is *source code someone wrote*. `X("abc")` is a programmer error at the call site, and failing there beats discovering it when a printer answers `echo:Unknown command`. `GWords.kt` states this in its own KDoc: "this module throws only for those." |
| `BigDecimal` | a `BigDecimal` overload on **every** letter, plus `GSender.m140(temp: BigDecimal)` | The core admits `BigDecimal` as its one portability liability (todo 02). Here it is not a liability at all — callers hold JVM decimals and the facade's job is to accept them. |
| Extension and infix functions, "no extension DSLs" | `infix fun GCommand.comment`, `infix fun GBlock.comment`, `GCommand.line()` | These *are* the facade. `G(1, X(10)) comment " move"` is the ergonomic win the layer exists to provide. |
| JVM types | `java.util.function.Consumer` in `GSender`'s secondary constructor | `void` is not `Unit`, so `new GSender(this::onG)` will not compile against `(String) -> Unit`. This overload is the entire reason the Java facade can be swapped out without touching its callers. |
| `map`/`filter`/`toList` and allocation | `params.toList()`, `parts + tailComment(text)` | The ban is about **per-byte hot paths**. A builder runs once per authored line; clarity wins. |
| "Write C, not idiomatic Kotlin" | idiomatic Kotlin — overloads, default arguments, `vararg` | Optimise for the **call site**, not for transliteration. |

**What does not reverse.** Two core rules hold with full force, because they are about the bytes on
the wire rather than about the language:

1. **Byte-exactness.** See *The lexeme rule*.
2. **Spec citation.** Every non-obvious rule carries its section number in a comment
   (`// spec 8: * is the line's checksum field`). Density is bought with comments here too.

## The rule, as a number

> **Any valid G-code can be written with the DSL.**

`GDslCorpusTest` is the gate, and it is not a smoke test — it takes every line of
`gcode/src/test/resources/marlin.gcode` apart and **rewrites it through the DSL's public builders
only**, never from the parsed objects (that would test the encoder instead), then compares:

| Assertion | Value | Meaning |
|---|---|---|
| `inexpressible` | **empty list** | no quarantine; there is no real line the DSL cannot say |
| `expressible` | **303** | non-blank corpus lines, all of them |
| `byteExact` | **279** | identical bytes, modulo the encoder's canonical spacing |
| `notByteExact.size` | **24** | four named causes, pinned in a comment in the test |

**These numbers are a contract.** A change either keeps them or updates them *together with* the
breakdown comment in the test and [todo 10](../../../doc/todos/10-command-dsl.md). Moving
`byteExact` silently is the failure this test exists to prevent — which is why its failure message
prints every differing line.

The 24 are **not** DSL gaps, and none should be "fixed" here: 20 are bare rest-of-line strings that
need the command number to lex (spec 3.4a, deferred to todo 09 — a *model* gap above this layer),
and the other 4 are inputs the encoder deliberately will not reproduce because emitting them would
change the checksum for no gain. Before treating a corpus difference as your bug, check it is not
one of those four.

## The lexeme rule

**The `String` overload is a number, not text.** `X("10.50")` is the decimal 10.50 written with the
digits the caller chose.

```kotlin
X(10)             // X10
X("10.50")        // X10.50    <- not X10.5
X("01")           // X01       <- leading zero survives
S(text("Hello"))  // S"Hello"  <- text is explicit
S(expr("bed[0]")) // S{bed[0]}
```

A number token carries its lexeme as part of its identity. **Never re-render a number from its
value** — it changes the bytes, and with them the checksum. This is the one place a core invariant
reaches up into the facade, and it is why `number(lexeme)` in `GWords.kt` keeps the string verbatim
and validates it by hand (spec 3.1: optional sign, digits, at most one `.`, at least one digit)
rather than through `BigDecimal`'s own parser, which accepts more.

For actual text use `text(…)` (spec 3.4); for an expression `expr(…)` (spec 3.5).

## Rules specific to this layer

### Never restate a parser rule — share it

`command()` validates through `GCommandParser.isCommandNumber`, which was lifted to a companion for
exactly this. **A builder and a parser disagreeing about what a command number is would let a
round-trip test pass on input no firmware accepts.** If you need a rule the parser already knows,
lift it the same way; do not copy the condition.

### Structural fields: `*` is refused, `N` is not

- `*` (`GChecksum`) as a parameter is refused — it is the line's checksum field and
  `GEncoder.frame` is what puts one there. A block carrying its own `*` would be checksummed twice.
- **`N` is deliberately allowed.** Spec 7.1 makes only the *first field of a line* a line number, so
  an `N` inside a command is an ordinary parameter — and `M110 N7` sets the line-number counter
  (spec 7.2). Refusing it made `M110` unwritable.

That over-wide-guard mistake has now been made **twice** in this module — once in
`GCommandParser.isStructural` (todo 05) and once here — and both times it was found by something
real failing, not by review. When you add a guard, write out the line it would refuse and check that
line is genuinely invalid.

### Letter collisions resolve the way the *parser* resolves them

A Kotlin name cannot return two types, so where a letter is both a command and a parameter, one
spelling wins — and it is always the parser's reading, so the two halves agree:

- **`T`** heads a command: `T(0)` → `T0`. As a parameter (`M105 T1`, `G29 T`, 40 corpus lines) write
  `word('T', 1)` or `flag('T')`.
- **`D`** is a parameter: `D(3)` → `D3`. The Marlin debug *command* is `command('D', 3)` — which the
  parser still reads back as a parameter. That asymmetry is the spec's own ambiguity, not this
  file's, and a named test pins it.

Both are covered by tests in `GTest.kt`. If you add a builder for a colliding letter, add the test
that states which way it resolved and why.

### Adding a parameter letter means five declarations

21 letters are covered. Each appears in exactly five shapes, and a letter that skips one is
inconsistent in a way callers will hit:

```kotlin
val Q: GWord = GFlagWord(GLetter('Q'))          // flag - spec 3.2
fun Q(value: Int): GWord = word('Q', value)
fun Q(lexeme: String): GWord = word('Q', lexeme)
fun Q(value: BigDecimal): GWord = word('Q', value)
fun Q(value: GValue): GWord = word('Q', value)
```

For a letter spec 4.2 does not list, do **not** add a builder — the generic escape hatches
`word`/`flag`/`text`/`expr`/`tailComment`/`inlineComment` exist for it, and a test pins that path.

### Builders return values; the sink stays separate

Nothing in `G.kt` emits. A command can be built, inspected and asserted on before anything is sent.
The Java facade this replaces took its sink in the constructor and returned `Unit`, which is why
*its own test* had to keep a mutable list to see what it had produced. Do not reintroduce that.

`GSender` is deliberately thin and **holds no line number and no window** — that is
`GCodeReader`/`GSendWindow`'s job (todo 05), and duplicating it here would give a caller two
counters that can disagree. Its named operations (`m105`, `m115`, `m155`, `m140`, `tempReport`,
`autoReportTemp`, `firmwareInfo`, `setBedTemperature`) exist only to carry the Java facade's callers
across unchanged; each is one line of DSL with no privileges.

### The dependency runs one way

`dsl` imports `code.core.GEncoder` and `code.core.token.*`. **Nothing in `code/core` imports `dsl`**
— check that this still holds after any change, because it is what keeps the core portable on its
own:

```bash
grep -rn 'code\.dsl' gcode/src/main/kotlin/org/qw3rtrun/p3d/g/code/core/   # must print nothing
```

## Testing

Follow the `tdd` skill for the loop. On top of it, for this layer:

- **`GDslCorpusTest` is the acceptance test.** New expressive power should move a number in it, or
  you have not demonstrated the power. A refactor must leave all four numbers untouched.
- **Assert the rendered string, not the object graph.** `assertEquals("G1 X10.5 F1800", encode(...))`
  is the claim; asserting a `GRQ`'s shape tests the model, which `core` already tests.
- **Every builder ships a round-trip-through-the-parser case.** `GTest.kt`'s
  `everything the DSL frames parses back as a verified packet` frames DSL output and asserts it
  comes back as a `GPacketLine` — i.e. the checksum verifies. A builder that emits bytes the parser
  rejects is the defect class this layer can actually introduce.
- **Refusals are tests too.** `require` is the contract here, so a rule you add needs an
  `assertThrows<IllegalArgumentException>` naming what it refuses. See
  `a command number the parser would refuse is refused here too`.
- `@Nested inner class` per area (`Commands`, `Lines`, `Framing`, `TheSink`), backtick names stating
  one behaviour, plain JUnit 5 assertions, no mocks.
- **The Java interop test is load-bearing.** `GSenderJavaInteropTest.java` is written in Java on
  purpose: it pins that `new GSender(this::onG)` compiles. Do not port it to Kotlin — that would
  delete the only thing it tests.

## Documentation duty

- [`GCODE_spec.md`](../../../doc/specs/GCODE_spec.md) is the syntax authority. Cite section numbers
  where a rule is non-obvious; the DSL cites spec 3.1, 3.2, 3.4, 3.5, 4.1, 4.2, 4.3, 5, 6, 7.1, 7.2
  and 8 today.
- [todo 10](../../../doc/todos/10-command-dsl.md) is this layer's record: the rule, the corpus
  numbers, the decisions and the 24-line breakdown. When behaviour or a number changes, it changes
  in the same commit.
- Todo 10's **What is left** is the standing next step: `G.java` still exists and three `:backend`
  files still import it. Finishing that means swapping `import org.qw3rtrun.p3d.g.G` for
  `import org.qw3rtrun.p3d.g.code.dsl.GSender` in `GFlux.java`, `PrinterState.java` and
  `PrinterReactor.java` — outside `:gcode`, and tangled with the dead-Java-island decision in
  todo 08.

## Review checklist

```bash
./gradlew :gcode:test --console=plain 2>&1 | grep -E '^e:|FAILED|BUILD'

# the core must not depend on the facade
grep -rn 'code\.dsl' gcode/src/main/kotlin/org/qw3rtrun/p3d/g/code/core/

# every letter still has its flag shape (21 today)
grep -c '^val [A-Z]: GWord = GFlagWord' gcode/src/main/kotlin/org/qw3rtrun/p3d/g/code/dsl/GWords.kt
```

- [ ] `GDslCorpusTest`'s four numbers unchanged, or changed with the comment and todo 10 together
- [ ] no number re-rendered from its value; lexeme preserved end to end
- [ ] new rules validated by sharing the parser's function, not by a copied condition
- [ ] any new guard checked against a real line it would refuse
- [ ] new builder has a rendered-string test, a round-trip-through-the-parser case, and a refusal test
- [ ] a new letter has all five shapes, or uses the generic helpers instead
- [ ] spec section cited for every non-obvious rule
- [ ] nothing in `code/core` imports `dsl`
