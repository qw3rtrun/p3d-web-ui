# G-Code Submodule (`:gcode`) Java to Kotlin Migration Plan

## 1. Overview & Current State

The `:gcode` submodule is responsible for parsing, tokenizing, AST representation, encoding, and decoding of G-code
commands and response events from 3D printers.

### Migration Objectives

- Migrate all Java sources in `:gcode` to idiomatic Kotlin (Kotlin 2.3.x).
- Standardize the AST, lexer, and parser model around Kotlin language features (sealed interfaces, data classes/objects,
  sequences, extension functions, pattern matching).
- Fix current in-progress Kotlin compilation errors.
- Ensure 100% test coverage and preserve compatibility with consumer modules (`:backend:core`, `:backend:terminal`,
  `:backend:api`, `:app`) without modifying other modules during this phase.

### Current Submodule Statistics

- **Java Sources (`src/main/java`)**: 56 files across 6 packages.
- **Kotlin Sources (`src/main/kotlin`)**: 6 files across 3 packages (partially implemented / WIP).
- **Java Tests (`src/test/java`)**: 11 test suites across 6 packages.
- **Kotlin Tests (`src/test/kotlin`)**: 3 test suites across 2 packages.

### Current Status & Blocker Analysis

The project currently fails to compile during `:gcode:compileKotlin` due to incomplete in-flight refactorings:

1. `org.qw3rtrun.p3d.g.code.G.kt`: Unresolved reference `GCommand`.
2. `org.qw3rtrun.p3d.g.code.core.token.GLineReader.kt`: Unresolved references to `GCommand` and `GCommandLine`, plus
   type inference ambiguities.
3. `org.qw3rtrun.p3d.g.code.GTest.kt`: References `GCommand.print()` which is not yet declared.

---

## 2. Architecture & Subsystem Breakdown

The `:gcode` submodule is partitioned into the following functional layers:

```
+-------------------------------------------------------------------------+
|                              DSL / High-Level API                       |
|   org.qw3rtrun.p3d.g.G, org.qw3rtrun.p3d.g.code.*                       |
+------------------------------------+------------------------------------+
                                     |
+------------------------------------+------------------------------------+
|                      Command Codecs & Descriptors                       |
|   org.qw3rtrun.p3d.g.code.descr.*, org.qw3rtrun.p3d.g.code.decoder.*    |
|   org.qw3rtrun.p3d.g.encoder.*                                         |
+------------------------------------+------------------------------------+
                                     |
+------------------------------------+------------------------------------+
|                       Core AST & Tokenizer Layer                        |
|   org.qw3rtrun.p3d.g.code.core.token.*, org.qw3rtrun.p3d.g.code.core.*  |
|   org.qw3rtrun.p3d.g.code.cmd.*                                        |
+------------------------------------+------------------------------------+
                                     |
+------------------------------------+------------------------------------+
|                     Domain Events & Event Decoders                      |
|   org.qw3rtrun.p3d.g.event.*, org.qw3rtrun.p3d.g.decoder.*              |
+-------------------------------------------------------------------------+
```

### Layer Details:

1. **Tokenizer & Lexer Layer (`org.qw3rtrun.p3d.g.code.core.token`)**:
    - `GTokens.kt`: Sealed hierarchy of token elements (`GToken`, `GElement`, `GIdentifier`, `GLiteral`, `GComment`,
      `GSeparator`, `GString`, `GNumber`, `GExpression`).
    - `GTokenizer.kt`: Character stream/sequence tokenizer parsing raw strings into token sequences.
    - `GSemantics.kt` & `GLiner.kt`: Converts flat token streams into structured line models (`GLine`,
      `GCommandLine`, syntax errors).
2. **Core AST & Checksum Layer (`org.qw3rtrun.p3d.g.code.core`, `org.qw3rtrun.p3d.g.code.cmd`)**:
    - Legacy/Java AST: `GField`, `GIntField`, `GDoubleField`, `GStrField`, `GFlagField`, `GQuoteField`, `GComment`,
      `CheckSum`, `XorCheckSum`.
    - Command representation: `GCommand`, `GLine`, `GLineCodec`, `GCoreCodec`.
3. **Descriptors & Codecs (`org.qw3rtrun.p3d.g.code.descr`, `org.qw3rtrun.p3d.g.code.decoder`)**:
    - Schema descriptors for G-code definitions (`CommandDescriptor`, `DescriptorCreator`, `GCode`, `GParam`,
      `GStrValue`).
    - Decoders converting tokens/fields into structured domain objects (`GCodeDecoder`, `ParameterDecoder`,
      `StrValueDecoder`, `ValueDecoder`).
4. **Events & Printer Response Decoders (`org.qw3rtrun.p3d.g.event`, `org.qw3rtrun.p3d.g.decoder`)**:
    - Response events implementing `:backend:core` `GEvent` interfaces (`OKReceived`, `AdvancedOKReceived`,
      `TemperatureReported`, `OkTemperatureReported`, `WaitReceived`, `ReceivedUnknownEvent`).
    - Line decoders for parsing printer responses (`OkDecoder`, `TemperatureReportedDecoder`, `FirmwareReportDecoder`,
      `CapabilityReportDecoder`, `CompositeDecoder`, `WaitReceivedDecoder`, `UnknownStringDecoder`).
5. **High-Level Command DSL & Encoders (`org.qw3rtrun.p3d.g`, `org.qw3rtrun.p3d.g.code`,
   `org.qw3rtrun.p3d.g.encoder`)**:
    - Typed commands (`ReportHotendTemperature`, `AutoReportHotendTemperature`, `SetHotendTemperature`,
      `SetBedTemperature`, `FirmwareInfo`).
    - Command DSL emitter `G` (`m105`, `m115`, `m140`, `m155`, etc.).

---

## 3. Inventory of Source Files

### 3.1 Existing Kotlin Files (WIP / To Finalize)

| Package                              | File              | Status | Action Required                                       |
|--------------------------------------|-------------------|--------|-------------------------------------------------------|
| `org.qw3rtrun.p3d.g.code`            | `G.kt`            | Broken | Align with finalized `GCommand` AST model             |
| `org.qw3rtrun.p3d.g.code.core`       | `GDescription.kt` | Valid  | Verify compatibility with descriptor layer            |
| `org.qw3rtrun.p3d.g.code.core.token` | `GTokens.kt`      | Valid  | Enhance with missing token types if needed            |
| `org.qw3rtrun.p3d.g.code.core.token` | `GSemantics.kt`   | WIP    | Define `GCommandLine`, `GCommand` AST representations |
| `org.qw3rtrun.p3d.g.code.core.token` | `GTokenizer.kt`   | Valid  | Remove debug `println`, clean up edge cases           |
| `org.qw3rtrun.p3d.g.code.core.token` | `GLiner.kt`  | Broken | Implement `GCommandLine` and `GLineIterator` cleanly  |

### 3.2 Java Files to Migrate

#### Package: `org.qw3rtrun.p3d.g.event` (6 files)

- `OKReceived.java` &rarr; `OKReceived.kt` (data object / data class implementing `OKReceivedEvent`)
- `AdvancedOKReceived.java` &rarr; `AdvancedOKReceived.kt` (data class implementing `AdvancedOkReceivedEvent`)
- `TemperatureReported.java` &rarr; `TemperatureReported.kt` (data class implementing `TemperatureReportedEvent`)
- `OkTemperatureReported.java` &rarr; `OkTemperatureReported.kt` (data class implementing `TemperatureReportedEvent`,
  `OKReceivedEvent`)
- `WaitReceived.java` &rarr; `WaitReceived.kt` (data class implementing `WaitReceivedEvent`)
- `ReceivedUnknownEvent.java` &rarr; `ReceivedUnknownEvent.kt` (data class implementing `UnknownEvent`)

#### Package: `org.qw3rtrun.p3d.g.decoder` (8 files)

- `GEventDecoder.java` &rarr; `GEventDecoder.kt` (fun interface / interface with decode method)
- `OkDecoder.java` &rarr; `OkDecoder.kt`
- `TemperatureReportedDecoder.java` &rarr; `TemperatureReportedDecoder.kt`
- `FirmwareReportDecoder.java` &rarr; `FirmwareReportDecoder.kt`
- `CapabilityReportDecoder.java` &rarr; `CapabilityReportDecoder.kt`
- `CompositeDecoder.java` &rarr; `CompositeDecoder.kt`
- `WaitReceivedDecoder.java` &rarr; `WaitReceivedDecoder.kt`
- `UnknownStringDecoder.java` &rarr; `UnknownStringDecoder.kt`

#### Package: `org.qw3rtrun.p3d.g.code.core` (20 files)

- `CheckSum.java` &rarr; `CheckSum.kt`
- `XorCheckSum.java` &rarr; `XorCheckSum.kt`
- `GCodeSyntaxException.java` &rarr; `GCodeSyntaxException.kt`
- `QuoteUtils.java` &rarr; `QuoteUtils.kt` (or Kotlin String extension functions)
- `GElement.java` &rarr; `GElement.kt`
- `GField.java` &rarr; `GField.kt`
- `GNamedField.java` &rarr; `GNamedField.kt`
- `GUnnamedField.java` &rarr; `GUnnamedField.kt`
- `GIntField.java` &rarr; `GIntField.kt`
- `GDoubleField.java` &rarr; `GDoubleField.kt`
- `GStrField.java` &rarr; `GStrField.kt`
- `GFlagField.java` &rarr; `GFlagField.kt`
- `GQuoteField.java` &rarr; `GQuoteField.kt`
- `GQuote.java` &rarr; `GQuote.kt`
- `GComment.java` &rarr; `GComment.kt`
- `GCodec.java` &rarr; `GCodec.kt`
- `GCoreCodec.java` &rarr; `GCoreCodec.kt`
- `GCoreDecoder.java` &rarr; `GCoreDecoder.kt`
- `GCoreEncoder.java` &rarr; `GCoreEncoder.kt`
- `GAwareDecoder.java` &rarr; `GAwareDecoder.kt`

#### Package: `org.qw3rtrun.p3d.g.code.cmd` (4 files)

- `G.java` &rarr; `G.kt`
- `GCommand.java` &rarr; `GCommand.kt`
- `GLine.java` &rarr; `GLine.kt`
- `GLineCodec.java` &rarr; `GLineCodec.kt`

#### Package: `org.qw3rtrun.p3d.g.code.descr` (6 files)

- `GCode.java` &rarr; `GCode.kt` (annotation or descriptor model)
- `GEncodable.java` &rarr; `GEncodable.kt`
- `GParam.java` &rarr; `GParam.kt`
- `GStrValue.java` &rarr; `GStrValue.kt`
- `CommandDescriptor.java` &rarr; `CommandDescriptor.kt`
- `DescriptorCreator.java` &rarr; `DescriptorCreator.kt`

#### Package: `org.qw3rtrun.p3d.g.code.decoder` (4 files)

- `GCodeDecoder.java` &rarr; `GCodeDecoder.kt`
- `ParameterDecoder.java` &rarr; `ParameterDecoder.kt`
- `StrValueDecoder.java` &rarr; `StrValueDecoder.kt`
- `ValueDecoder.java` &rarr; `ValueDecoder.kt`

#### Package: `org.qw3rtrun.p3d.g.code` (5 files)

- `AutoReportHotendTemperature.java` &rarr; `AutoReportHotendTemperature.kt`
- `FirmwareInfo.java` &rarr; `FirmwareInfo.kt`
- `ReportHotendTemperature.java` &rarr; `ReportHotendTemperature.kt`
- `SetBedTemperature.java` &rarr; `SetBedTemperature.kt`
- `SetHotendTemperature.java` &rarr; `SetHotendTemperature.kt`

#### Package: `org.qw3rtrun.p3d.g.encoder` (2 files)

- `Encoder.java` &rarr; `Encoder.kt`
- `SetHotendTemperatureEncoder.java` &rarr; `SetHotendTemperatureEncoder.kt`

#### Package: `org.qw3rtrun.p3d.g` (1 file)

- `G.java` &rarr; `G.kt`

---

## 4. Phased Migration Strategy

To guarantee continuous buildability and safety, the migration is structured into ordered phases with verified
checkpoints at each step.

```
+--------------------------------------------------------------------------------+
| Phase 1: Fix Kotlin Compilation & Complete Core Token / AST Hierarchy          |
| - Implement GCommandLine, GCommand in GSemantics.kt                            |
| - Fix GLineReader.kt and G.kt compilation                                      |
| - Verify GTokenizerTest and GCodeReaderTest                                    |
+---------------------------------------+----------------------------------------+
                                        |
+---------------------------------------v----------------------------------------+
| Phase 2: Migrate Domain Events (org.qw3rtrun.p3d.g.event)                      |
| - Convert all 6 event classes to Kotlin data classes                           |
| - Verify backend:core event interface implementations                          |
+---------------------------------------+----------------------------------------+
                                        |
+---------------------------------------v----------------------------------------+
| Phase 3: Migrate Response Decoders (org.qw3rtrun.p3d.g.decoder)                |
| - Convert GEventDecoder interface and 7 decoder classes to Kotlin              |
| - Migrate all corresponding decoder test suites to Kotlin                      |
+---------------------------------------+----------------------------------------+
                                        |
+---------------------------------------v----------------------------------------+
| Phase 4: Migrate Core AST, Checksums & Codecs (org.qw3rtrun.p3d.g.code.core)   |
| - Convert CheckSum, XorCheckSum, GField hierarchies, and core codecs           |
| - Migrate GAwareDecoderTest, GCoreCodecTest, XorCheckSumTest                   |
+---------------------------------------+----------------------------------------+
                                        |
+---------------------------------------v----------------------------------------+
| Phase 5: Migrate Descriptors, Code Decoders & Cmd (org.qw3rtrun.p3d.g.code.*)  |
| - Convert CommandDescriptor, DescriptorCreator, ParameterDecoders, GCommand    |
| - Migrate CommandDescriptorTest, GLineCodecTest                                |
+---------------------------------------+----------------------------------------+
                                        |
+---------------------------------------v----------------------------------------+
| Phase 6: Migrate High-Level Commands, Encoders & DSL (org.qw3rtrun.p3d.g.*)    |
| - Convert command models, encoders, and main G DSL class                       |
| - Migrate SetHotendTemperatureEncoderTest and root GTest                       |
+---------------------------------------+----------------------------------------+
                                        |
+---------------------------------------v----------------------------------------+
| Phase 7: Final Verification & Java Source Cleanup                              |
| - Verify no residual Java files in :gcode/src/main/java and src/test/java      |
| - Run `./gradlew :gcode:test` and full project build `./gradlew test`          |
| - Ensure zero regressions in dependent modules                                 |
+--------------------------------------------------------------------------------+
```

### Detailed Phase Execution Plan

### Phase 1: Fix Existing Kotlin Compilation & Complete Token AST

- **Goal**: Resolve all existing compilation errors in `:gcode:compileKotlin`.
- **Tasks**:
    1. Define `GCommand` and `GCommandLine` in `org.qw3rtrun.p3d.g.code.core.token.GSemantics.kt`:
       ```kotlin
       data class GCommandLine(val cmds: List<GCommand>, override val line: List<GToken>) : GLine
       data class GCommand(val head: GIdentifier, val params: List<GElement>) {
           fun print(): String = ...
       }
       ```
    2. Fix `GLiner.kt` type inference and token iteration logic.
    3. Fix `G.kt` command builder functions to create `GCommand` instances matching the AST.
    4. Fix `GTest.kt` and ensure `GTokenizerTest.kt` and `GCodeReaderTest.kt` execute cleanly.
- **Verification**: Run `./gradlew :gcode:compileKotlin`.

### Phase 2: Migrate Domain Events (`org.qw3rtrun.p3d.g.event`)

- **Goal**: Convert all event records to Kotlin data classes with null-safety and full Java interop.
- **Files**:
    - `OKReceived.java` &rarr; `OKReceived.kt`
    - `AdvancedOKReceived.java` &rarr; `AdvancedOKReceived.kt`
    - `TemperatureReported.java` &rarr; `TemperatureReported.kt`
    - `OkTemperatureReported.java` &rarr; `OkTemperatureReported.kt`
    - `WaitReceived.java` &rarr; `WaitReceived.kt`
    - `ReceivedUnknownEvent.java` &rarr; `ReceivedUnknownEvent.kt`
- **Verification**: Compile and ensure consumers in `:backend:api` and `:backend:terminal` continue referencing events
  seamlessly.

### Phase 3: Migrate Response Decoders (`org.qw3rtrun.p3d.g.decoder`)

- **Goal**: Convert G-code event decoder interfaces and regex-based response decoders to Kotlin.
- **Files**:
    - `GEventDecoder.java` &rarr; `GEventDecoder.kt`
    - `OkDecoder.java`, `TemperatureReportedDecoder.java`, `FirmwareReportDecoder.java`, `CapabilityReportDecoder.java`,
      `CompositeDecoder.java`, `WaitReceivedDecoder.java`, `UnknownStringDecoder.java` &rarr; `.kt`
- **Tests**:
    - `OkDecoderTest.java`, `TemperatureReportedDecoderTest.java`, `FirmwareReportDecoderTest.java` &rarr; `.kt`
- **Verification**: Run `./gradlew :gcode:test --tests "org.qw3rtrun.p3d.g.decoder.*"`.

### Phase 4: Migrate Core AST, Checksum & Core Codecs (`org.qw3rtrun.p3d.g.code.core`)

- **Goal**: Convert G-code field types, checksum calculators, syntax exceptions, and core encoders/decoders.
- **Files**:
    - Checksums: `CheckSum.java`, `XorCheckSum.java`
    - Exceptions & Utils: `GCodeSyntaxException.java`, `QuoteUtils.java`
    - AST Fields: `GElement.java`, `GField.java`, `GNamedField.java`, `GUnnamedField.java`, `GIntField.java`,
      `GDoubleField.java`, `GStrField.java`, `GFlagField.java`, `GQuoteField.java`, `GQuote.java`, `GComment.java`
    - Codecs: `GCodec.java`, `GCoreCodec.java`, `GCoreDecoder.java`, `GCoreEncoder.java`, `GAwareDecoder.java`
- **Tests**:
    - `GAwareDecoderTest.java`, `GCoreCodecTest.java`, `GCoreDecoderTest.java`, `XorCheckSumTest.java` &rarr; `.kt`
- **Verification**: Run `./gradlew :gcode:test --tests "org.qw3rtrun.p3d.g.code.core.*"`.

### Phase 5: Migrate Descriptors, Code Decoders & Cmd (`org.qw3rtrun.p3d.g.code.descr`,
`org.qw3rtrun.p3d.g.code.decoder`, `org.qw3rtrun.p3d.g.code.cmd`)

- **Goal**: Convert schema reflection/descriptors, command codecs, and line codec structures.
- **Files**:
    - `descr`: `GCode.java`, `GEncodable.java`, `GParam.java`, `GStrValue.java`, `CommandDescriptor.java`,
      `DescriptorCreator.java`
    - `decoder`: `GCodeDecoder.java`, `ParameterDecoder.java`, `StrValueDecoder.java`, `ValueDecoder.java`
    - `cmd`: `GCommand.java`, `GLine.java`, `GLineCodec.java`, `G.java`
- **Tests**:
    - `CommandDescriptorTest.java`, `GLineCodecTest.java` &rarr; `.kt`
- **Verification**: Run `./gradlew :gcode:test --tests "org.qw3rtrun.p3d.g.code.*"`.

### Phase 6: Migrate High-Level Commands, Encoders & DSL (`org.qw3rtrun.p3d.g.*`)

- **Goal**: Convert typed G-code command representations and high-level DSL entry points.
- **Files**:
    - Commands: `ReportHotendTemperature.java`, `AutoReportHotendTemperature.java`, `SetHotendTemperature.java`,
      `SetBedTemperature.java`, `FirmwareInfo.java`
    - Encoders: `Encoder.java`, `SetHotendTemperatureEncoder.java`
    - Root DSL: `org.qw3rtrun.p3d.g.G.java` &rarr; `G.kt`
- **Tests**:
    - `SetHotendTemperatureEncoderTest.java`, `GTest.java` &rarr; `.kt`
- **Verification**: Run `./gradlew :gcode:test`.

### Phase 7: Cleanup & Multi-Module Interoperability Check

- **Goal**: Remove all legacy Java source files in `:gcode`, verify clean build across the entire project.
- **Tasks**:
    1. Confirm `gcode/src/main/java` and `gcode/src/test/java` directories are deleted / empty.
    2. Run `./gradlew :gcode:test --rerun` to ensure 100% test pass rate.
    3. Run `./gradlew :backend:terminal:test` and `./gradlew :backend:api:test` to confirm Kotlin binary compatibility
       with Java consumers.
    4. Run `./gradlew build` to verify full project build and packaging.

---

## 5. Kotlin Design Conventions & Idioms for `:gcode`

1. **Sealed Hierarchies for AST & Events**:
    - Use `sealed interface` and `data class` / `data object` for immutable tree structures to leverage
      compiler-enforced exhaustive `when` expressions.
2. **Null Safety**:
    - Replace Java `Optional<T>` return types with idiomatic nullable types `T?` in internal Kotlin APIs, while
      maintaining Java-compatible methods where required.
3. **Sequences & Streams**:
    - Leverage `Sequence<T>` for lazy token and line processing in lexer/parser pipelines instead of intermediate `List`
      allocations.
4. **Extension Functions & Operators**:
    - Utilize extension conversions (`Int.toToken()`, `String.toToken()`) and operator overloading (`plus`, `invoke`)
      for compact, expressive command creation.
5. **DSL Builders**:
    - Use Kotlin DSL features (lambda with receiver, vararg, default parameters) for `G` command emission.
6. **Eliminate Lombok**:
    - Replace Lombok `@Value`, `@AllArgsConstructor`, `@Getter`, `@RequiredArgsConstructor` with native Kotlin
      constructors and data classes.

---

## 6. Migration Progress Checklist

- [ ] **Phase 1: Fix Compilation & Complete Token AST**
    - [ ] Implement `GCommandLine` and `GCommand` in `GSemantics.kt`
    - [ ] Fix `GLiner.kt` parsing logic
    - [ ] Fix `G.kt` builder methods
    - [ ] Verify `GTokenizerTest.kt` and `GCodeReaderTest.kt`
- [ ] **Phase 2: Domain Events (`org.qw3rtrun.p3d.g.event`)**
    - [ ] `OKReceived.kt`
    - [ ] `AdvancedOKReceived.kt`
    - [ ] `TemperatureReported.kt`
    - [ ] `OkTemperatureReported.kt`
    - [ ] `WaitReceived.kt`
    - [ ] `ReceivedUnknownEvent.kt`
- [ ] **Phase 3: Response Decoders (`org.qw3rtrun.p3d.g.decoder`)**
    - [ ] `GEventDecoder.kt`
    - [ ] `OkDecoder.kt` & `OkDecoderTest.kt`
    - [ ] `TemperatureReportedDecoder.kt` & `TemperatureReportedDecoderTest.kt`
    - [ ] `FirmwareReportDecoder.kt` & `FirmwareReportDecoderTest.kt`
    - [ ] `CapabilityReportDecoder.kt`
    - [ ] `WaitReceivedDecoder.kt`
    - [ ] `UnknownStringDecoder.kt`
    - [ ] `CompositeDecoder.kt`
- [ ] **Phase 4: Core AST, Checksum & Codecs (`org.qw3rtrun.p3d.g.code.core`)**
    - [ ] `CheckSum.kt` & `XorCheckSum.kt` & `XorCheckSumTest.kt`
    - [ ] `GCodeSyntaxException.kt` & `QuoteUtils.kt`
    - [ ] AST field definitions (`GField.kt`, `GIntField.kt`, etc.)
    - [ ] `GCodec.kt`, `GCoreCodec.kt`, `GCoreDecoder.kt`, `GCoreEncoder.kt`
    - [ ] `GAwareDecoder.kt` & `GAwareDecoderTest.kt`
    - [ ] `GCoreCodecTest.kt` & `GCoreDecoderTest.kt`
- [ ] **Phase 5: Descriptors & Codecs (`org.qw3rtrun.p3d.g.code.descr` & `org.qw3rtrun.p3d.g.code.decoder` &
  `org.qw3rtrun.p3d.g.code.cmd`)**
    - [ ] `GCode.kt`, `GEncodable.kt`, `GParam.kt`, `GStrValue.kt`
    - [ ] `CommandDescriptor.kt` & `DescriptorCreator.kt` & `CommandDescriptorTest.kt`
    - [ ] `GCodeDecoder.kt`, `ParameterDecoder.kt`, `StrValueDecoder.kt`, `ValueDecoder.kt`
    - [ ] `GCommand.kt`, `GLine.kt`, `GLineCodec.kt`, `GLineCodecTest.kt`
- [ ] **Phase 6: High-Level Commands, Encoders & DSL (`org.qw3rtrun.p3d.g.*`)**
    - [ ] `AutoReportHotendTemperature.kt`, `FirmwareInfo.kt`, `ReportHotendTemperature.kt`
    - [ ] `SetBedTemperature.kt`, `SetHotendTemperature.kt`
    - [ ] `Encoder.kt`, `SetHotendTemperatureEncoder.kt` & `SetHotendTemperatureEncoderTest.kt`
    - [ ] `G.kt` & `GTest.kt`
- [ ] **Phase 7: Cleanup & Final Verification**
    - [ ] Delete all legacy `.java` files from `:gcode`
    - [ ] Run `./gradlew :gcode:test --rerun`
    - [ ] Run `./gradlew test` (full suite across all subprojects)
