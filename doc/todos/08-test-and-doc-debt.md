# 08 — Test and documentation debt

**Goal.** Coverage lost in the Java→Kotlin migration is either recovered or explicitly written off,
and the one test that covers live code is recovered.

**Depends on:** nothing. **Blocks:** nothing.

## Eight orphaned Java test suites

Commit `f0d0944` removed **11 Java test suites**; three were ported to Kotlin and eight were not:

```
gcode/src/test/java/org/qw3rtrun/p3d/g/GTest.java
gcode/src/test/java/org/qw3rtrun/p3d/g/code/core/GAwareDecoderTest.java
gcode/src/test/java/org/qw3rtrun/p3d/g/code/core/GCoreCodecTest.java
gcode/src/test/java/org/qw3rtrun/p3d/g/code/core/GCoreDecoderTest.java
gcode/src/test/java/org/qw3rtrun/p3d/g/code/core/GLineCodecTest.java
gcode/src/test/java/org/qw3rtrun/p3d/g/code/core/XorCheckSumTest.java
gcode/src/test/java/org/qw3rtrun/p3d/g/code/decoder/CommandDescriptorTest.java
gcode/src/test/java/org/qw3rtrun/p3d/g/encoder/SetHotendTemperatureEncoderTest.java
```

Recover them with `git show f0d0944^:<path>` before judging — the point is to read what each one
asserted, not to guess from the name.

**The reachability question is already answered.** An earlier version of this file said "the real
question is whether the *class* has a future" and left it open per suite. It is not open: a reference
grep over `gcode/src/main`, `backend/` and `app/` shows that **seven of the eight suites cover code
unreachable from any production entry point**, and the eighth covers live Kotlin.

| Suite | Class under test | Non-self references in production |
|---|---|---|
| `GAwareDecoderTest` | `code/core/GAwareDecoder.java` | **0** |
| `GLineCodecTest` | `code/cmd/GLineCodec.java` | **0** |
| `SetHotendTemperatureEncoderTest` | `encoder/SetHotendTemperatureEncoder.java` | **0** |
| `CommandDescriptorTest` | `code/descr/CommandDescriptor.java` | 1 — `DescriptorCreator.java`, which itself has **0** |
| `GCoreCodecTest` | `code/core/GCoreCodec.java` | 1 — `GLineCodec`, which has **0** |
| `GCoreDecoderTest` | `code/core/GCoreDecoder.java` | 1 — `GCoreCodec`, above |
| `GTest.java` | `g/G.java` | **3** — see the inversion below |
| `XorCheckSumTest` | `code/core/XorCheckSum` (Kotlin) | live, and covered — but see below |

So `code/core/**`'s Java cluster (`GAwareDecoder`, `GCodec`, `GCoreCodec`, `GCoreDecoder`,
`GCoreEncoder`), `code/cmd/GLineCodec`, `code/descr/{CommandDescriptor, DescriptorCreator}` and
`encoder/SetHotendTemperatureEncoder` are one connected island of dead code reachable from nothing.
That collapses six of the eight items from "decide per suite" into **one deletion decision**.

- [ ] **Decide the dead island in one go**: delete the classes and close the six suites, or keep the
      classes and say why. Both outcomes are fine; an undocumented gap is not. Do not port tests for
      code you are about to delete.
- [ ] **`XorCheckSumTest` is _not_ superseded — this is the one real port in this file.** An earlier
      version called it "very likely genuinely superseded" because a Kotlin `XorCheckSumTest.kt`
      exists (12 `@Test` plus a 6-case `@CsvSource`). Diffing them shows the vector sets are
      **disjoint**. The Java suite's three payloads are absent from Kotlin:

      ```
      N1 M115            -> 39
      N1 M155 S1         -> 97
      N2 M117 Hello World! -> 7
      ```

      The Kotlin suite covers `N3 T0`, `N1 G28`, `N1 M110 N1`, `N42 G1 X10.5 Y-3 E0.42 F1800`,
      `N0 M110`, `A`. Nothing overlaps. `N2 M117 Hello World!` is the valuable one: a payload with
      spaces and punctuation, over live code, bearing on
      [§8.3](../specs/GCODE_spec.md#8-checksum-and-crc) and on
      [09](./09-deferred-spec-gaps.md)'s bare rest-of-line strings. Port all three as `@CsvSource`
      rows and check they pass — if one does not, that is a checksum bug, not a bad vector.

      **Done in [04](./04-encoder-and-checksum.md).** All three are `@CsvSource` rows in
      `XorCheckSumTest.kt` and all three passed on the first run, so `XorCheckSum` was right and the
      vectors were good. This checkbox is closed; the dead-Java-island decision above is not.

## The inversion worth naming

`GTest.java` is listed above as covering `g/G.java`, and that is the item: **the live class is
untested and the tested class is dead.**

- `gcode/src/main/java/org/qw3rtrun/p3d/g/G.java` is the **only** `:gcode` type any other module
  imports — three production files: `backend/api/.../PrinterReactor.java`,
  `backend/api/.../PrinterState.java`, `backend/terminal/.../GFlux.java`. It has had **zero** tests
  since `f0d0944`.
- `gcode/src/main/kotlin/org/qw3rtrun/p3d/g/code/dsl/G.kt` — the Kotlin DSL that the current
  `GTest.kt` covers — has **zero** production callers.

The deleted `GTest.java` asserted `"M105 T0"`, *with* a separating space, so the pre-Kotlin encoder
used the single-space rule that [04](./04-encoder-and-checksum.md) proposes to adopt. Hand that
observation to 04; it is evidence, not a coincidence.

- [ ] Decide which `G` survives, then give the survivor tests. If it is `G.java`, port
      `GTest.java`'s four assertions against it. If the intent is that `code/dsl/G.kt` replaces it, then
      `:backend`'s three imports are the migration work and that belongs in a new queue file, not
      here — say so and close this item.

## Documentation

`.junie/GCODEK.md` **no longer exists.** It was deleted in `3e22daf`, the same commit that wrote this
queue, which is why two checklist items here cited it down to line numbers (`GCODEK.md:26`, `:249`,
`:361-362`). Both of those items are satisfied by deletion and have been removed. The surviving
orientation doc is `.junie/AGENTS.md` (144 lines), which was checked and carries **no** stale
blocker claims — no `compileKotlin` failure, no unresolved `GCommand` / `GCommandLine`, no phase
list, and nothing about `:gcode` beyond one accurate line describing the module (`AGENTS.md:27`).

- [ ] Nothing to fix. Confirm `AGENTS.md` still matches `./gradlew build` when this file is picked
      up, and if it does, strike this section rather than inventing work for it.

## A gap worth filling

- [ ] **There is no fixture of firmware replies.** `gcode/src/test/resources/marlin.gcode` is 415
      lines of real commands *sent*, and it is what surfaced several of the lexer bugs. Nothing
      equivalent exists for what comes *back* — `ok`, temperature reports, `Resend:`, the capability
      report. Capturing one session would materially de-risk
      [06](./06-decoder-edge-portability.md) and [05](./05-line-numbering-and-session.md), both of
      which are currently specified only by the regexes they intend to delete. The same item is
      listed in 06; do it in whichever file reaches it first and strike it from the other.

## Verify

- [ ] Every suite in the list above is either ported and green, or has a one-line note here saying
      why it is gone.
- [ ] The three `XorCheckSum` vectors above are in `XorCheckSumTest.kt` and passing, and the
      `:gcode:test` count has risen by three.
- [ ] If the dead island was deleted: `./gradlew build` green, and a reference grep for each deleted
      class comes back empty across `gcode/`, `backend/` and `app/`.
