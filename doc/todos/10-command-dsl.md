# 10 — The command-building DSL (`code/dsl`)

**Status: done.** `G.java` is deleted and all three `:backend` callers are on `GSender` — see
*How it finished* at the bottom.

**Goal.** The `code/dsl` package is the facade for *writing* G-code — `G.kt` for commands and
lines, `GWords.kt` for parameters — and the rule it is held to is: **any valid G-code can be
written with it.** [`GDslCorpusTest`](../../gcode/src/test/kotlin/org/qw3rtrun/p3d/g/code/dsl/GDslCorpusTest.kt)
makes that a number rather than an aspiration.

## Why it needed work

The old `G.kt` sugared the *head* of a command — `G`, `M`, `T` — and nothing else. Parameters were
raw model constructors at the call site (`GParameterWord(GLetter('X'), GInt(10))`), which is the
opposite of a DSL: the verbose part was the part a facade should remove.

Measured against the rule, over `marlin.gcode`'s 303 non-blank lines:

| | before | after |
|---|---|---|
| expressible at all | — | **303 / 303** |
| byte-exact (canonical spacing) | 156 | **279** |

The single largest gap was not a missing overload. **`G.kt`'s unit was a `GCommand`, and the rule is
about lines** — a line is commands *plus* comments, `GCommand` has no comment, and 125 of those 303
lines carry one. 41% of a real corpus was unreachable for that reason alone.

## What changed

- **`GBlock` / `GBlockPart` in `code/core/token`** — a line being built: an ordered list of commands
  and comments. Deliberately *not* `GSemantic`, which is the parse direction and carries the exact
  bytes read; a block carries what the author chose and lets the encoder decide the bytes.
- **`GEncoder.encode(GBlock)` and `GEncoder.frame(n, GBlock)`.** Framing a block splits positionally:
  everything up to the last non-comment part is payload and is checksummed; a *trailing* comment goes
  after the `*` and is not covered ([§5](../specs/GCODE_spec.md#5-line-block-structure),
  [§8.3](../specs/GCODE_spec.md#83-what-the-checksum-covers)). A comment *between* commands is
  transmitted before the `*` and so **is** covered.
- **`GWords.kt` in `code/dsl`** — a parameter builder per letter in [§4.2](../specs/GCODE_spec.md#42-parameter-letters),
  each in five shapes (`Int`, lexeme `String`, `BigDecimal`, `GValue`, and a flag), plus generic
  `word`/`flag`/`text`/`expr`/`tailComment`/`inlineComment` for everything the table does not list.
- **`G.kt` in `code/dsl`** — command builders returning values, line builders, and `GSender` as a
  thin sink.

## Decisions

- **Builders return values; the sink is separate.** The old facade took its sink in the constructor
  and returned `Unit`, so a command could not be built and inspected — its own test had to keep a
  `mutableListOf` to see what it had produced.
- **The `String` overload is a number, not text.** `X("10.50")` is the decimal written with the
  digits the caller chose, which is the only way to say `X10.50` rather than `X10.5`; a number's
  lexeme is part of its identity and re-rendering from the value would change the bytes and with them
  the checksum. Text is `text("…")`, an expression is `expr("…")`.
- **Command numbers are validated with the parser's own rule.** `GCommandParser.isCommandNumber` was
  lifted to a companion so the DSL shares it. A builder and a parser disagreeing about what a command
  number is would let a round-trip test pass on input no firmware accepts.
- **Full Kotlin is allowed here, and the package boundary is what says so.** The layering rule makes
  only `code/core` portable. The DSL sits beside it in its own package — `code/dsl`
  (`org.qw3rtrun.p3d.g.code.dsl`), holding `G.kt` and `GWords.kt` — as a host-side facade over the
  core, so infix and extension functions, `BigDecimal` and `java.util.function.Consumer` are all
  fine there. The dependency runs one way only: `dsl` imports `code/core` and `code/core/token`, and
  nothing in the core imports `dsl`. That is what makes the core portable on its own — a port
  re-implements `code/core` and writes its own facade, and never has to look inside `code/dsl`.

## Findings

**Command letters and parameter letters overlap, and a Kotlin name can only mean one thing.** Two
letters are affected, and each is resolved the way the *parser* resolves it so the two halves agree:

- `T` heads a command (`T(0)` → `T0`); as a parameter — `M105 T1`, `G29 T`, 40 corpus lines — it is
  `word('T', 1)` / `flag('T')`.
- `D` is a parameter (`D(3)` → `D3`, §4.2 diameter and PID `D`); the Marlin debug *command* §4.1
  lists is `command('D', 3)`, which the parser still reads back as a parameter. That asymmetry is the
  spec's own ambiguity.

**An over-wide structural guard, for the second time.** The first draft refused `N` as a command
parameter. `M110 N7` is exactly the case where an `N` *is* one — §7.1 makes only the first field of a
**line** a line number — so the guard made `M110` unwritable. This is the same mistake
[05](./05-line-numbering-and-session.md) had to fix in `GCommandParser.isStructural`, found the same
way: by something real failing, not by review.

## The 24 lines that are not byte-exact

None is a gap in the DSL, and they are pinned in the test with this breakdown:

- **20 bare rest-of-line strings** — `M0 Click to continue`, `M23 /path/f.gco`. These need the
  command number to lex ([§3.4a](../specs/GCODE_spec.md#34-string-values), deferred to
  [09](./09-deferred-spec-gaps.md)); today they lex letter by letter. A **model** gap above the DSL,
  and the one item here worth fixing.
- **2 × `G29 F 10.0`** — a space between a letter and its value. §2.1 permits it, the parser reads it
  as one word, and the encoder has no way to ask for it — emitting it would change the checksum for
  no gain.
- **1 × `G61 XY S0`** — two flags with no space between them. Read as two words, written back with
  the canonical separator.
- **1 × `G92 .1 ;TODO`** — a value with no letter in front of it, which is not a word at all (§4), so
  no command carries it.

## How it finished

**`G.java` is deleted; all three `:backend` callers construct a `GSender`.** The import swap was the
easy half. Two things made it more than a rename:

**`G.code(GEncodable)` had no counterpart, and should not have got one.** The old facade's real work
was `consumer.accept(code.encode())` — each command record encoded *itself* with a `String.format`.
`GSender.send` takes a `GBlock`/`GCommand`, and giving it a `GEncodable` overload would have pulled
the Java island into the DSL, backwards. Instead `PrinterState`'s four `handle(…)` methods now name
the operation they mean (`g.m104(index, temp)`, `g.m140(temp)`, `g.m105(index)`, `g.m155(period)`),
which is what `GSender`'s named operations are for. `m104` was the one missing — added with tests.

**The formatting was a live bug.** `SetBedTemperature.encode()` was
`format("M140 S%.2f", temp)` with no `Locale`, so under a comma-decimal default locale it put

```
M140 S60,00
```

on the wire — not a number by [§3.1](../specs/GCODE_spec.md#31-numeric-values), and a command the
firmware rejects. Observed, not reasoned: `String.format(Locale.GERMANY, "M140 S%.2f", 60.0)`
returns exactly that. `PrinterState.wireTemp` now builds the `BigDecimal` itself
(`BigDecimal.valueOf(c).setScale(2, HALF_UP)`), which is locale-independent, and the DSL writes the
scale it is handed. The two-decimal shape is unchanged, so every other byte is identical.

Pinned by `backend/api/.../PrinterStateGcodeTest` — eight cases, every expectation captured from the
old facade *before* it was deleted, so it passes against both implementations. The exception is the
locale case, which the old code failed.

### What this leaves for [08](./08-test-and-doc-debt.md)

The island is smaller and its shape is now clear, which is the input that decision was waiting on:

- **`GEncodable.encode()` has no caller on the wire path any more.** `G.java:70` was the only one.
  It is not dead, though — each record's `toString()` calls it, and `PrinterReactor` logs
  `"-> {}"`, so `encode()` is now a *debug representation*. If it stays, it should say so.
- **`GEncodable` survives as a marker**, for `PrinterReactor`'s reflective dispatch filter and the
  `handle(Mono<T extends GEncodable>)` bound. Nothing else needs it.
- **Four of the five records are live and not removable**: `SetHotendTemperature`,
  `SetBedTemperature`, `AutoReportHotendTemperature` and `ReportHotendTemperature` are
  `@RequestBody` types on `PrinterController`, deserialised by Jackson.
- **`g.code.FirmwareInfo` is now unreferenced** — `G.java` was its only user. It is the one class in
  the island that can just go.
- **`@GCode` and `@GParam` are read by nothing**, which is why nobody noticed that
  `ReportHotendTemperature` is annotated `@GParam("I")` while its `encode()` emitted `T`. Harmless
  today; a trap if anyone ever writes the annotation processor the island implies.
