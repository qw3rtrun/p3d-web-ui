# 12 — Marlin reply events (`marlin/event/`)

**Status: Tier 1 in.** The replies Marlin sends back while it works, one class each, decoded the way
[11](./11-marlin-commands.md) decodes commands — `GRs` for what a line *is*, a companion
`GRsDecoder` for reading one. `marlin/protocol/` already covers the RepRap base replies (`ok`,
`wait`, `start`, `rs`, `Error:`, `busy:`, `//`); this is the Marlin-specific layer above it.

**Goal.** A named, typed class per reply, so a host reads `TemperatureRs.hotend.current` rather than
re-deriving a regex at every call site, and so a reply that arrives unrecognised is *absence* rather
than a crash.

## Where the formats came from

**Marlin's G-code documentation does not document replies.** Every `marlinfw.org/docs/gcode/*` page
describes the request side only — parameters, not output. The authoritative source is the firmware
itself:

| | |
|---|---|
| string catalogue | `Marlin/src/core/language.h` — 328 `STR_*` defines, one per serial message |
| temperature fields | `Temperature::print_heater_state` / `print_heater_states` in `module/temperature.cpp` |
| position report | `report_current_position` in `module/motion.cpp`, `gcode/host/M114.cpp` |
| endstops | `Endstops::report_states` / `print_es_state` in `module/endstops.cpp` |
| capabilities | `gcode/host/M115.cpp` — 43 `Cap:` names |
| SD card | `sd/cardreader.cpp` |
| `echo:` / `Error:` prefixes | `serial_echo_start()` / `serial_error_start()` in `core/serial.cpp` |

Read against `MarlinFirmware/Marlin` **2.1.x**. Only *interface facts* are taken — the literal bytes
a field is written with — on the same footing as the parameter letters in [11](./11-marlin-commands.md),
and for the same licensing reason: the documentation prose is GPL-3.0 and is not copied.

## Why not the old `marlin/decoder/` package

`marlin/decoder/` decodes five of these already, on a `GEventDecoder` that returns `Optional`, with
one instantiated class per decoder. It stays for now: `backend/api`'s `PrinterReactor` wires it, and
that layer is deferred. So Tier 1 lands **beside** it, not over it, and the duplicate pairs are:

| new (`marlin/event/`) | old (`marlin/decoder/`) |
|---|---|
| `TemperatureRs` | `TemperatureReportedDecoder` → `TemperatureReported` / `OkTemperatureReported` |
| `FirmwareInfoRs` | `FirmwareReportDecoder` → `FirmwareReport` |
| `CapabilityRs` | `CapabilityReportDecoder` → `CapabilityReport` |
| `WaitRs` (in `protocol/`) | `WaitReceivedDecoder` → `WaitReceived` |
| `EchoMessage` | `UnknownStringDecoder` → `ReceivedUnknownEvent` |

Each new class implements the same `backend/core` event interface as the old one where there is one,
so migrating `PrinterReactor` later is a wiring change and not a rewrite. **Retiring `marlin/decoder/`
is the closing step of this todo and is blocked on the Reactor layer.**

## The tiers

### Tier 1 — printer state (done)

What a UI reads continuously. All in `marlin/event/`.

| Class | Wire format | Sent for |
|---|---|---|
| `TemperatureRs` | ` T:210.00 /210.00 B:60.00 /60.00 @:127 B@:80` | M105, M155, appended to `ok` |
| `PositionRs` | `X:0.00 Y:0.00 Z:0.00 E:0.00 Count X:0 Y:0 Z:0` | M114, M154 |
| `EndstopStateRs` + `EndstopReportHeader` | `Reporting endstop status` then `x_min: open` | M119 |
| `SdPrinting` / `SdNotPrinting` | `SD printing byte 1234/56789`, `Not SD printing` | M27 |
| `FirmwareInfoRs` | `FIRMWARE_NAME:Marlin ... UUID:...` | M115 |
| `CapabilityRs` | `Cap:EEPROM:1` | M115 |
| `EchoMessage` | `echo:<text>` | everywhere |
| `UnknownCommand` | `echo:Unknown command: "M9999"` | any unparsed command |

### Tier 2 — safety

`Error:Thermal Runaway, system stopped! Heater_ID: 0` · `Heating failed` · `Thermal Malfunction` ·
`MAXTEMP triggered` · `MINTEMP triggered` · `Error:Printer halted. kill() called!` ·
`Error:Printer stopped due to errors. Fix the error and use M999 to restart.` ·
`!! STOP called because of <x> error - restart with M999` · `!! KILL caused by <x>` ·
`Watchdog timeout. Reset required.` · `<E> cold extrusion prevented` · `too long extrusion prevented` ·
`Hotend too cold` · the heater-redundancy mismatch message.

These arrive **under the `Error:` and `!!` prefixes `ErrorRs` already claims**, so the open question
is whether they subtype `ErrorRs` or decode independently and leave `ErrorRs` as the fallback. Decide
that before writing any of them.

### Tier 3 — job lifecycle

SD file family (`Begin file list` / `<name> <size>` / `End file list`, `File opened: <n> Size: <s>`,
`File selected`, `Writing to file: <n>`, `Done saving file.`, `Done printing file`, and the eight
documented SD failures) · `Stats: ` print-job totals (M78) · filament-change prompts (M600) ·
`Active Extruder: <n>` · `Position saved` / `Restoring position` (G60/G61) · `endstops hit: `.

⚠️ Marlin 2.1.x sends **`No SD card`**, not the `SD init fail` the RepRap wiki lists. Check what the
printers on the bench actually emit before pinning either.

### Tier 4 — calibration and configuration

PID autotune (M303): `PID Autotune start` → ` bias: … d: … min: … max: …` → ` Ku: … Tu: …` →
` Classic PID ` → ` Kp: … Ki: … Kd: …` → `PID Autotune finished! …`, plus three documented failures.
MPC autotune (M306). Bed levelling and probing: `Bed X: … Y: … Z: …`, `Bilinear Leveling Grid:` and
its numeric grid, UBL/MBL mesh dumps, `Error:Probing Failed`, `Z Probe Past Bed`, `Probe Offset`.
EEPROM: `echo:Settings Stored (<n> bytes; crc <n>)`, `echo:Hardcoded Default Settings Loaded`,
`EEPROM Corrupt`. The **M503 settings dump** — ~35 `echo:` section headers, each followed by the `M…`
line that restores it. Boot banner: `Marlin <version>`, `echo: Last Updated: …`, `echo:Compiled: …`,
`PowerUp`, the four reset causes, ` Free Memory: <n>  PlannerBufferBytes: <n>`.

### Not planned

`M114 D` detail dump · M43 pin debugging · GRBL-emulation `<Idle|MPos:…>` · RepRapFirmware JSON
(`M408`/`M409` — another firmware) · binary file transfer.

## The multi-line problem

`GRsDecoder.decode(line: String)` reads **one line**. Four replies are several lines that mean one
thing: M20's file list, M119's endstop block, the PID autotune sequence, and M503's settings dump.

Tier 1 answers it for M119 the cheap way — **one event per line** (`EndstopReportHeader`, then an
`EndstopStateRs` each) and the caller assembles. That keeps the single-line interface honest and
costs nothing until a caller actually needs the block as a unit. Tiers 3 and 4 will need the real
answer, because a file list without its `End file list` is not merely incomplete, it is wrong. The
options are a stateful sibling interface that accumulates, or an assembler above the decoders that
holds the open block. **Not decided.**

## What is left

1. **Tier 2**, and the `ErrorRs`-subtyping question above.
2. **Tiers 3 and 4**, which need the multi-line decision first.
3. **Retire `marlin/decoder/`** once `PrinterReactor` can move — five duplicate pairs, listed above.
4. **`C@:` is ambiguous in Marlin itself** — the chamber and the cooler both write that field, so a
   machine with both cannot be read unambiguously. `TemperatureRs` maps it to the chamber, which is
   the commoner configuration. Upstream quirk, recorded rather than solved.
