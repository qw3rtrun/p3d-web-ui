# Marlin command generation

Four files under `gcode/src/main/kotlin/.../marlin/` are **generated** — `MarlinGRQ.kt`,
`MarlinMRQ.kt` and `MarlinTRQ.kt` hold all 295 command classes, split by command letter, and
`MarlinRQ.kt` holds the `MarlinCommands` registry and the `MarlinG` shortcuts — and so are its
tests and the doc-example fixture. Nothing here runs during the
Gradle build; the output is checked in and the build only compiles it.

## Why generated

Marlin documents 287 distinct codes with 1044 parameter slots between them. Hand-writing that is
about 14,000 lines in which a single transposed parameter letter is invisible until a printer
rejects the command. Generating it from Marlin's own documentation means the parameter sets are
never guessed, and a Marlin release is a re-run rather than a re-read.

## Regenerating

```bash
# 1. fetch the upstream documentation
curl -sSL -o docs.tar.gz \
  https://codeload.github.com/MarlinFirmware/MarlinDocumentation/tar.gz/refs/heads/master
tar xzf docs.tar.gz

# 2. distil it into the vendored data file (pass the commit you fetched, for provenance)
python3 tools/marlin/extract_marlin_docs.py MarlinDocumentation-master/_gcode <commit-sha>

# 3. generate the Kotlin and the tests
python3 tools/marlin/gen_mcommands.py

# 4. the generated tests are the check
./gradlew :gcode:test
```

Step 2 writes `doc/marlin-gcode/commands.json`; step 3 writes the four Kotlin files above,
`MarlinCommandsTest.kt` and `gcode/src/test/resources/marlin-doc-examples.txt`. Review the JSON
diff first — it is the reviewable artifact, and a surprise there is a doc change worth
understanding before it becomes 14,000 lines of Kotlin.

Both scripts run on a bare Python 3 (no `pip`, no PyYAML): the front matter is regular enough to
read directly, and a hand-rolled reader keeps the tool usable on a machine with nothing installed.

## What is taken, and what is not

Only **interface facts**: the code, each parameter's letter, its type, whether it is optional, and
the doc's short name for its value. Descriptions, notes and article bodies are *not* copied — the
Marlin documentation is GPL-3.0, this repository publishes a Maven artifact, and the prose is not
needed to know that `M105` takes `R` and `T`. Every generated class links to its upstream page
instead. The example command lines *are* taken, as a test fixture: they are the independent check
that the extracted letters match what Marlin's own authors write.

## Decisions worth knowing

- **Every parameter is nullable and absent by default.** 831 of 882 documented parameters are
  optional, and `M105` and `M105 T0` are different commands, so an unset parameter must not reach
  the wire. Flags are `Boolean = false` rather than nullable, because absent and false are the same
  thing for a letter that carries no value.
- **The reading half is a `companion object : GRQDecoder<T>`.** `head()` and `decodeParams()`
  describe the command *type*, not one command, so they sit on the companion and a call site reads
  `ReportHotendTemperature.decode(cmd)` with no instance. `MarlinCommands.decoders` lists the 295
  companions and `byHead` is built from that; `MarlinCommands.all` stays as the bare-instance
  enumeration the tests sweep. `all`, `info` and `decoders` are index-aligned.
- **The 93 parameterless classes keep a hand-rolled `equals`/`hashCode`.** They are `class X`, not
  `object`, because `X()` call sites exist in the facade and in hand-written tests, and their
  companion's `decodeParams` therefore has to return `X()` rather than `this`. The `hashCode`
  literal is `zlib.crc32(name)`, **not** Python's `hash()` — string hashing is salted per process,
  so `hash()` made every regeneration emit 93 different constants and a diff of pure churn. The
  generator now aborts if two class names collide on that literal.
- **A code can be documented more than once.** Marlin has six `G29` pages, one per bed-leveling
  system, and two each for `G34`, `M665` and `M666`. Each becomes its own class, named from its
  title (`BedLevelingUnified`, `BedLevelingBilinear`), so none is lost. `MarlinCommands.decode`
  can only pick one of them and says so — build with the variant you mean.
- **Property names come from the doc's value name when it is usable and unique, else the letter.**
  `M105 T<index>` gives `index`; `M204`'s four parameters are all named `accel` upstream, so they
  fall back to `p`, `r`, `t`, `s`.
- **Four irregular codes are skipped**: `M43 T`, `T?`, `Tc`, `Tx`. None is a letter plus a number,
  so no builder in this project can express one. The extractor prints every skip.

## Known gaps

`MarlinDocExamplesTest` pins these against Marlin's own examples rather than hiding them:

- **A valued parameter sent bare** — `M104 F`, `G61 S0 XY`, `M919 XYZE` — cannot be read back,
  because `null` on a `BigDecimal?` already means absent. Building the valued form always works.
- **Rest-of-line strings** — `M117 Hello`, `M23 file.gco`, `M810`'s macro bodies — wait on
  [todo 09](../../doc/todos/09-deferred-spec-gaps.md). The generated class says so in its KDoc.
- **Dotted quads** — `M552 P192.168.1.55` is not a number and not a quoted string.
- **Undocumented upstream** — `M569 S`, `M123 S`, `M672 S`, `M919 E` appear in examples but not in
  their pages' parameter lists, and `G92.1` is used in an example but missing from `G092.md`'s
  `codes:`. Report upstream rather than guessing a type here.
