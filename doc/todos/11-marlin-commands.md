# 11 — The Marlin command set (`marlin/MarlinGRQ.kt`, `MarlinMRQ.kt`, `MarlinTRQ.kt`)

**Status: the command set is in.** 295 classes covering 287 distinct codes, generated from Marlin's
own documentation, split by command letter across `marlin/MarlinGRQ.kt`, `MarlinMRQ.kt` and
`MarlinTRQ.kt` — each implementing `GRQ` and each written the way the `ReportHotendTemperature`
reference is written. `MarlinCommands`, the prototype registry and decode lookup, is an `object` in
`marlin/MCommands.kt` alongside the `MarlinG` shortcuts; the classes are top-level in one package,
so which file a class lives in is invisible to callers. Three follow-ups are listed
under *What is left*.

**Goal.** A named, typed Kotlin class per G-code Marlin supports, sitting on top of the
[`code/dsl`](./10-command-dsl.md) builders: `encode()` produces a `GCommand`, `decodeParams` reads
one back. Where [10](./10-command-dsl.md) answers *can this project write any valid G-code*, this
answers *does it know what Marlin's commands are called and what they take*.

## Why it is generated

| | |
|---|---|
| distinct codes | **287** (54 `G`, 233 `M`, 8 `T`, minus 4 irregular) |
| classes | **295** — a code documented as several variants gets one class per variant |
| parameter slots | **1044** |
| generated Kotlin | ~13,400 lines of classes (2,800 `G` + 10,300 `M` + 300 `T`), ~700 of registry, ~2,200 of test |

Hand-writing that is not a typing problem, it is a *verification* problem: a transposed parameter
letter in one of 1044 slots is invisible in review and shows up when a printer answers
`echo:Unknown command`. Marlin publishes the command reference as structured YAML front matter, so
the parameter sets can be taken from it rather than guessed, and a Marlin release becomes a re-run.

The pipeline and how to re-run it are in [`tools/marlin/README.md`](../../tools/marlin/README.md).
`doc/marlin-gcode/commands.json` is the vendored, reviewable middle step — review *that* diff, not
the 14,000 lines downstream of it.

**Only interface facts are vendored** — code, parameter letter, type, optional, value name. The
Marlin documentation is GPL-3.0 and this repository publishes a Maven artifact, so the prose is
cited by URL and not copied. The example command lines are taken, as a test fixture.

## Decisions

- **Every parameter is nullable and absent by default.** 831 of the 882 documented parameters are
  optional, and `M105` is not `M105 T0` — an unset parameter must not reach the wire. This was the
  one thing the hand-written reference could not express: `val index: Int` defaulting to 0 always
  emitted `T0`, so the common bare form was unreachable.
- **Flags are `Boolean = false`, not nullable.** For a letter that carries no value, absent and
  false are the same statement.
- **The 46 documented-as-required parameters also get defaults.** `GRQ.decode` is an instance
  method, so the registry needs a no-argument prototype of every command. The KDoc marks them
  required; the type system does not. A cost worth knowing about.
- **Decimals go out through their lexeme.** `word(letter, v.toPlainString())`, so the number is
  validated as a G-code number on the way in and can never reach the wire in scientific notation —
  [10](./10-command-dsl.md)'s lexeme rule, applied one layer up.
- **One class per documented variant.** Marlin has six `G29` pages, one per bed-leveling system,
  and two each for `G34`, `M665`, `M666`. Their titles differ, so `BedLevelingUnified` and
  `BedLevelingBilinear` are separate classes and no parameter set is lost. `MarlinCommands.decode`
  can only return one class for a shared head and documents that; build with the variant you mean.
- **`char` is a flag.** `M860`-`M869` type `X`/`Y`/`Z`/`E` as `char` with a value named `axis`, but
  the described usage is `M860 X` with nothing after it, and an axis letter as the *value* of an
  `X` parameter is not a thing. A judgement call, recorded in `kind_of`.

## Findings

**The reference implementation's `decode` threw for every input.** `ReportHotendTemperature`
searched its *parameters* for the letter `M` — the command letter, which never appears among them —
so `find` returned null and the non-null cast threw. `M105 T2` included. Fixed to read `T`, and
`MCommandsTest` now pins it.

**`toString()` printed the token tree.** The Java record it replaced returned a `String` from
`encode()`; the Kotlin one returns a `GCommand`, so `"(" + encode() + ')'` interpolated the whole
parse tree. `PrinterReactor` logs commands with `"-> {}"`, so that went straight into the log.

**A generated test cannot check the data it was generated from.** `MarlinCommandsTest` proves
encode and decode agree with each other — a letter extracted wrongly would be wrong on both sides
and pass. `MarlinDocExamplesTest` is the independent check: 302 example command lines written by
Marlin's own authors, asserting that every parameter letter in them is one some class models. It
found five real things on its first run, all of them now pinned rather than smoothed over.

**A parameter the docs give a value is often sent bare.** `M104 F`, `G61 S0 XY`, `M919 XYZE`,
`M420 C` — bare means "on". A `BigDecimal?` cannot hold "present, no value" because `null` already
means absent, so decoding such a line drops the letter. 13 of the 302 examples. Building the valued
form always works; only reading a bare one back loses it. Pinned as a list, not a count, in
`MarlinDocExamplesTest`.

## What is left

- [x] **The duplicate `M105` is resolved.** There was briefly a hand-written
      `ReportHotendTemperature` and a generated `ReportTemperatures`. There is now one class: it
      keeps the **hand-written name**, which is the one the Java record used and the one callers
      know, and the **generated shape**, which has the `R` redundant-sensor flag and a nullable `T`
      so a bare `M105` is expressible. `NAME_OVERRIDES` in the generator is where that name is
      pinned; add to it when a doc title is not the name this project wants.
- [ ] **Report four gaps upstream.** `M569 S`, `M123 S`, `M672 S` and `M919 E` appear in the pages'
      own examples but not in their parameter lists, and `G092.md`'s `codes:` omits `G92.1` though
      its examples use it. Pinned in `MarlinDocExamplesTest.undocumentedUpstream` with the reading
      that produced each.
- [ ] **`M43 T` has no home.** `M043-T.md` declares its code as the string `M43 T`, which is not a
      letter plus a number, so its `L`/`R`/`S`/`W` parameters cannot attach to `M43`. Decide whether
      the pin-watch sub-mode deserves a hand-written class.
- [ ] **Bare rest-of-line strings**, which block `M117`, `M23`, `M28`, `M30`, `M33`, `M118`,
      `M810`-`M819`, `M928` and `M0`/`M1` from carrying their argument, and the dotted quads in
      `M552`/`M553`/`M554`. All of it waits on [09](./09-deferred-spec-gaps.md) — this layer marks
      the affected classes in their KDoc and excludes them from the example test by name.
