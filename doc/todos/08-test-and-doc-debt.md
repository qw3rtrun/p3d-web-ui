# 08 — Test and documentation debt

**Goal.** Coverage lost in the Java→Kotlin migration is either recovered or explicitly written off,
and the project's own notes stop contradicting the build.

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

`.junie/GCODEK.md` phases 4–6 list all of them as *migrate*, so losing them looks unintentional rather
than decided. Recover them from git history before judging — the point is to read what each one
asserted, not to guess from the name.

- [ ] **Decide per suite: port, or record why it is obsolete.** Both outcomes are fine; an
      undocumented gap is not.
- [ ] Two are known to cover code that is still live and reachable:
      - `XorCheckSumTest.java` — a Kotlin `XorCheckSumTest` already exists with 17 tests including
        the spec §8.3 worked example, so this one is very likely genuinely superseded. Diff the two
        and close it out.
      - `GAwareDecoderTest.java` — covers `code/core/GAwareDecoder.java`, which is still in the tree
        and has no Kotlin replacement test.
- [ ] The rest (`GCoreCodec`, `GCoreDecoder`, `GLineCodec`, `CommandDescriptor`,
      `SetHotendTemperatureEncoder`, the old `GTest`) sit on the Java classes under
      `gcode/src/main/java/**`, which the Kotlin migration is progressively replacing. For each, the
      real question is whether the *class* has a future, not whether the test does — if the class is
      slated for deletion, say so and close the item.

## Documentation

- [ ] **Refresh `.junie/GCODEK.md`.** Its section 1, "Current Status & Blocker Analysis", is stale and
      has been for three passes. It claims `:gcode:compileKotlin` fails with unresolved `GCommand` /
      `GCommandLine` (`GCODEK.md:26`), sets Phase 1's goal as resolving those errors (`:249`), and
      leaves the phase unchecked (`:361-362`) — while `:gcode:test` builds and passes 398 tests and
      `GSemantics.kt` defines both types. Anyone reading it as orientation is misled on the first
      page.
- [ ] While there: reconcile GCODEK.md's phase list with this queue, or reduce it to a pointer at
      [00-index.md](./00-index.md). Two overlapping plans that disagree is worse than one.

## A gap worth filling

- [ ] **There is no fixture of firmware replies.** `gcode/src/test/resources/marlin.gcode` is 415
      lines of real commands *sent*, and it is what surfaced several of the lexer bugs. Nothing
      equivalent exists for what comes *back* — `ok`, temperature reports, `Resend:`, the capability
      report. Capturing one session would materially de-risk
      [06](./06-decoder-edge-portability.md) and [05](./05-line-numbering-and-session.md), both of
      which are currently specified only by the regexes they intend to delete.

## Verify

- [ ] Every suite in the list above is either ported and green, or has a one-line note here saying
      why it is gone.
- [ ] `.junie/GCODEK.md` section 1 describes a build that actually matches `./gradlew build`.
