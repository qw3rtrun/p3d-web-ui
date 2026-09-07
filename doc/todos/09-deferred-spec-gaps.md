# 09 — Deferred spec gaps

**Goal.** The parts of [`GCODE_spec.md`](../specs/GCODE_spec.md) the module knowingly does not
implement. Each is independent; none blocks anything else.

These are deferred on purpose, not overlooked. Nothing in the printer path needs them today, and each
carries a design question worth more than the feature. Take them one at a time, in whatever order
demand arrives.

## Bare rest-of-line strings — [§3.4a](../specs/GCODE_spec.md#34-string-values)

The largest gap, and the source of most of what the lexer still cannot classify.

```
M117 Hello World      →  [GLetter(M), GInt(117), GSpace, GLetter(H), GLetter(e), GLetter(l), ...]
M30 /path/to/f.gco    →  [..., GUnknown(/), GLetter(p), ..., GUnknown(.), GLetter(g), ...]
```

Commands like `M117` and `M30` take the rest of the line as an uninterpreted string. Everything the
corpus census still reports as unknown — `! # ' , . / : \ | ~` — comes from here or from RS274
parameters below.

**The design question:** the lexer cannot know this without knowing the command, which inverts the
current layering — tokens do not depend on commands. Options are a two-pass lex (structure, then
re-lex the tail once the command is known), a lexer mode the caller sets, or a table of
rest-of-line commands consulted mid-scan. `GDescription` already has a `strTail: Boolean` on
`GDescriptor`, which suggests the table was the intended answer.

- [ ] Decide the layering, then implement. Round-trip must hold: the string re-emits exactly.

## Subcodes — [§4.1](../specs/GCODE_spec.md#41-command-letters)

`G29.1` lexes as `GLetter(G)` + `GFloat(29.1)` with no subcode concept.

- [ ] **Scheduled in [03](./03-word-and-command-layer.md)**, listed here only so the spec gap map is
      complete. If 03 defers it, it lands back here.

## RS274/NGC parameters and expressions — [§3.5](../specs/GCODE_spec.md#35-expressions-and-parameters)

`#<param>` and `[…]` expressions. `#` is in the unknown-character set. Balanced `{…}` expressions
already work and are the obvious model to follow.

- [ ] Deferrable indefinitely unless an RS274 dialect is actually targeted. Note that `[…]` currently
      survives *inside* `{…}` because expression contents are opaque, so slicer placeholders like
      `{first_layer_bed_temperature[0]}` already round-trip.

## Block delete — [§5](../specs/GCODE_spec.md#5-line-block-structure)

A leading `/` marks a line skippable when block delete is active. RS274/NGC only.

- [ ] Small once the line classifier is the place it belongs — it is a fifth line kind alongside
      empty / simple / packet / error, or a flag on the line.

## Line-length limit — [§1.3](../specs/GCODE_spec.md#13-line-length)

No length is enforced anywhere. Spec §9 classes an over-long line as a **structural error**, and the
payload budget (≤ 76 characters) is what makes rounding decisions matter in
[02](./02-number-representation.md) and [04](./04-encoder-and-checksum.md).

- [ ] Decide where it belongs: the liner can detect it on input, and the encoder must respect it on
      output. The encoder side is the one that actually prevents breakage.

## CRC16 — [§8.4](../specs/GCODE_spec.md#84-crc16-reprapfirmware)

CCITT CRC-16, polynomial `0x1021`, emitted as five zero-padded decimal digits over the same byte
range as the XOR checksum. Strictly stronger, preferred by RepRapFirmware where supported.

- [ ] Write it in the same streaming shape as `XorCheckSum` — one byte in, integer state, mask out —
      so it drops into the same interface. A lookup table, if used, is an `IntArray` literal.
- [ ] **The digit count selects the algorithm**: 1–3 digits XOR, 5 digits CRC16.
      [04](./04-encoder-and-checksum.md) is asked to leave room for this in whatever type carries the
      checksum; if it did, this becomes a small addition rather than a signature change.

## Module extractability

`:gcode` depends on `:backend:core` for the event types its decoders return, so the module as a whole
cannot be lifted out even after [06](./06-decoder-edge-portability.md). Only `code/core/**` is close
to portable.

- [ ] Noted, not scheduled. It becomes real the day someone actually starts the JS/TS, C or Rust port.
