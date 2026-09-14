# 10 — The command-building DSL (`G.kt`)

**Status: the `:gcode` half is done.** One step remains and it is outside this module — see
*What is left* at the bottom.

**Goal.** `G.kt` is the facade for *writing* G-code, and the rule it is held to is: **any valid
G-code can be written with it.** [`GDslCorpusTest`](../../gcode/src/test/kotlin/org/qw3rtrun/p3d/g/code/GDslCorpusTest.kt)
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
- **`GWords.kt`** — a parameter builder per letter in [§4.2](../specs/GCODE_spec.md#42-parameter-letters),
  each in five shapes (`Int`, lexeme `String`, `BigDecimal`, `GValue`, and a flag), plus generic
  `word`/`flag`/`text`/`expr`/`tailComment`/`inlineComment` for everything the table does not list.
- **`G.kt`** — command builders returning values, line builders, and `GSender` as a thin sink.

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
- **Full Kotlin is allowed here.** The layering rule makes only `code/core` portable; `code/G.kt` is a
  host-side facade over it, so infix and extension functions are fine. A port re-implements the core
  and writes its own facade.

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

## What is left

**`G.java` still exists and `:backend` still imports it.** `GSender` is a drop-in for it — same named
operations (`m105`, `m115`, `m155`, `m140`, `tempReport`, `autoReportTemp`, `firmwareInfo`,
`setBedTemperature`) and a `Consumer<String>` constructor so `new GSender(this::onG)` compiles from
Java, pinned by a Java-language test. Finishing the replacement means editing three files outside
`:gcode`, which the module-only scope rules out for now:

- `backend/terminal/.../GFlux.java` — `new G(this::onG)` → `new GSender(this::onG)`.
- `backend/api/.../PrinterState.java` — field and `onOnline(G)` parameter types.
- `backend/api/.../PrinterReactor.java` — `new G(...)`, plus its reflective dispatch over
  `GEncodable`, which is the dead Java island [08](./08-test-and-doc-debt.md) still has to decide
  about. `GSender.send` takes a `GBlock`/`GCommand`, not a `GEncodable`, so that decision and this
  migration are the same piece of work.
