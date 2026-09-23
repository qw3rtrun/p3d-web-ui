# G-code Base Syntax Specification

**Scope.** This document specifies the *common, command-agnostic* syntax of G-code as it is used by
3D-printer and CNC firmware: the lexical structure (tokens), value types, identifiers (field letters),
line/block structure, line numbering and checksums, comments, quoting and the transport-level
framing rules that depend on them.

**Out of scope.** The semantics of individual commands (`G0`, `G28`, `M104`, …), their parameter sets,
coordinate systems, motion planning, and any firmware-specific behaviour that is not part of the
general syntax.

**Terminology.** *Must*, *should* and *may* describe what a conforming parser/generator is expected to
do. Because G-code has no single normative standard, rules that differ between dialects are marked
and collected in [§10 Dialect matrix](#10-dialect-matrix).

---

## 1. Document model

G-code is a **line-oriented, flat, textual** format. There is no nesting, no block scoping and no
statement terminator other than the end of line.

```
file    ::= { line }
line    ::= sequence of words, comments and separators, terminated by an end-of-line marker
word    ::= a single-letter identifier optionally followed by a value
```

A line is also called a **block** (RS274/NGC terminology). One line is the unit of:

* transmission and acknowledgement over a serial/USB/network link,
* line numbering (`N`),
* checksum/CRC protection (`*`),
* error reporting and retransmission.

### 1.1 Character set and encoding

* G-code is **7-bit ASCII**. Bytes `0x20`–`0x7E` plus the line terminators are the portable set.
* Non-ASCII bytes are only meaningful inside comments and quoted strings, and only where the
  firmware documents an encoding (most firmware treats them as opaque bytes). Generators **should
  not** emit non-ASCII outside comments and quoted strings.
* `NUL` (`0x00`) must not appear in a line; firmware commonly uses it internally as the
  line terminator of the receive buffer.

### 1.2 End of line

| Terminator | Notes |
|---|---|
| `LF` (`\n`) | Canonical form; always accepted. |
| `CR LF` (`\r\n`) | Accepted; the `CR` is not part of the line content. |
| `CR` (`\r`) alone | Historic; accepted by many firmware, but **should not** be generated. |

A lone `CR` that is not followed by `LF` is treated by strict parsers as an unexpected character
rather than as a terminator.

An end-of-line marker **terminates a comment** started with `;` (see [§6](#6-comments)) and closes the
current block. The last line of a file need not be terminated.

### 1.3 Line length

Lines are bounded by the firmware's receive buffer. Exceeding the limit is a hard error (the line is
truncated or rejected, and — when line numbering is in use — a retransmission is requested).

| Dialect | Practical limit |
|---|---|
| Marlin | `MAX_CMD_SIZE` — 96 bytes by default, including the `N…` prefix and `*…` checksum, excluding the terminator |
| RepRapFirmware | 256 characters |
| RS274/NGC (LinuxCNC) | "The maximum line length is 256 characters" |

Generators that must interoperate with unknown firmware should keep lines **≤ 76 characters** of
payload so that the `N` prefix and `*` suffix still fit in a 96-byte buffer.

---

## 2. Lexical structure (tokens)

A conforming tokenizer produces the following token kinds. Token boundaries are determined by the
first character of the token (the grammar is LL(1) except for the number/`.`-lookahead case).

| Token | First character(s) | Lexeme |
|---|---|---|
| **Letter** (identifier) | `A`–`Z`, `a`–`z` | exactly one character |
| **Checksum marker** | `*` | exactly one character |
| **Integer** | `0`–`9` | `digit+` |
| **Decimal** | `0`–`9`, `.` | `digit* '.' digit*` (at least one digit overall) |
| **Sign** | `+`, `-` | part of a numeric value, see [§3.1](#31-numeric-values) |
| **Quoted string** | `"` | up to the closing quote, escapes applied |
| **Expression** | `{` (RRF) / `[` (RS274) | balanced, up to the matching close |
| **Parameter reference** | `#` (RS274) | `#digit+`, `#<name>`, `#<_name>` |
| **Tail comment** | `;` | up to (not including) the end of line |
| **Inline comment** | `(` | up to the matching `)` |
| **Block delete** | `/` (RS274, first non-blank char) | exactly one character |
| **Whitespace** | space, tab | one or more |
| **End of line** | `LF`, `CR LF` | see [§1.2](#12-end-of-line) |

Anything else is an **unknown/invalid token**. A robust parser should preserve it (for diagnostics and
round-tripping) rather than silently dropping it.

### 2.1 Whitespace

* Space and tab are **separators only**; they carry no meaning and may appear between any two tokens,
  including *between a letter and its value* (`X 10` ≡ `X10`) and *inside a number* in the most
  permissive dialects (`X1 0` ≡ `X10` in RS274/NGC — do not rely on this).
* RS274/NGC states: "Spaces and tabs are allowed anywhere on a line of code and do not change the
  meaning of the line, except inside comments."
* Whitespace is **significant inside comments and quoted strings**.
* Whitespace is **significant for checksums**: the checksum covers the bytes actually transmitted
  (see [§8.3](#83-what-the-checksum-covers)).
* Hosts routinely **strip** all whitespace and comments before transmission to save bandwidth.

### 2.2 Case

* The original NIST specification is **case-insensitive** outside comments; `g1x10` ≡ `G1 X10`.
* Marlin is case-insensitive only when `GCODE_CASE_INSENSITIVE` is enabled, and the fast parser
  (`FASTER_GCODE_PARSER`) requires **uppercase**: *"Arguments MUST be uppercase for fast G-Code
  parsing."*
* RepRapFirmware ≥ 1.19 is case-insensitive except inside quoted strings; ≤ 1.18 was case-sensitive.

**Rule for generators: always emit uppercase letters.** Case inside quoted strings and comments is
always preserved.

### 2.3 Grammar (EBNF)

```ebnf
file           = { line } ;
line           = [ ws ] [ block-delete ] [ line-number ] content [ checksum ] [ ws ] [ tail-comment ] eol ;

content        = { word | inline-comment | ws } ;
word           = letter [ ws ] [ value ] ;

letter         = "A".."Z" | "a".."z" ;
value          = number | quoted-string | expression | parameter-ref | bare-string ;

command-word   = ( "G" | "M" | "T" ) [ ws ] unsigned-int [ "." unsigned-int ] ;   (* subcode *)

line-number    = ( "N" | "n" ) [ ws ] [ sign ] unsigned-int [ ws ] ;
checksum       = "*" [ ws ] unsigned-int ;

number         = [ sign ] ( digit { digit } [ "." { digit } ] | "." digit { digit } ) ;
sign           = "+" | "-" ;
unsigned-int   = digit { digit } ;
digit          = "0".."9" ;

quoted-string  = '"' { char-or-escape } '"' ;
expression     = "{" { any - "}" | expression } "}"            (* RepRapFirmware *)
               | "[" { any - "]" | expression } "]" ;          (* RS274/NGC      *)
parameter-ref  = "#" ( unsigned-int | "<" name ">" ) ;         (* RS274/NGC      *)
bare-string    = { char - eol } ;                              (* command-specific, see §3.4 *)

tail-comment   = ";" { char - eol } ;
inline-comment = "(" { char - ")" } ")" ;
block-delete   = "/" ;
ws             = ( " " | tab ) { " " | tab } ;
eol            = "\n" | "\r\n" ;
```

Note that `command-word`, `line-number` and `checksum` are *specialisations* of `word` — they are
lexically ordinary words whose letters (`G`/`M`/`T`, `N`, `*`) are given structural meaning by the
parser.

---

## 3. Value types

A value is bound to the letter that immediately precedes it. Types are **not declared**; they follow
from the lexeme and from what the receiving command expects.

### 3.1 Numeric values

```
[+|-] digits [ . digits ]      |      [+|-] . digits
```

RS274/NGC: *"a number consists of an optional plus or minus sign, followed by zero to many digits,
followed, possibly, by one decimal point, followed by zero to many digits — provided that there is at
least one digit somewhere in the number."*

| Type | Lexical form | Notes |
|---|---|---|
| **Integer** | `123`, `-7`, `+0` | Used for counts, indices, tool/heater numbers, milliseconds. Range is firmware-defined; assume 32-bit signed and, for many parameters, 16-bit. |
| **Real / float** | `12.42`, `-0.5`, `.5`, `2.` | Single-precision (`float`) in Marlin and RepRapFirmware. Do not expect more than ~7 significant digits. |
| **Exponent form** | `1e3` | **Not** part of the base syntax. Never generate it. |
| **Hex / octal** | `0x1F` | Not supported. |

Recommendations for generators:

* Always write at least one digit before the decimal point (`0.5`, not `.5`).
* Do not emit thousands separators, spaces inside numbers, or a trailing `.`.
* Round coordinates to the precision the firmware can use (typically 3–5 decimals); extra digits only
  consume the line-length budget and the checksum surface.

### 3.2 Flag (value-less) parameters

A letter with **no** following value is a valid word. Its meaning is "parameter present", i.e. a
boolean flag:

```
G28 X Y          ; home X and Y, no values supplied
M18 X Y E        ; disable those steppers
```

Marlin's parser records such a parameter with `has_val == false`; `parser.seen('X')` is true while
`parser.seenval('X')` is false.

### 3.3 Boolean values

There is no boolean literal. Booleans are encoded as integers, conventionally `0` = off/false and
`1` = on/true, most often on `S` or `P`. Marlin's `value_bool()` is defined as *"seen and (no value or
value ≠ 0)"*, so `S1`, `S2` and a bare `S` are all true, and `S0` is false.

### 3.4 String values

Two mechanisms exist:

**(a) Bare rest-of-line strings.** A parameter with no numeric value may consume the remainder of the
line verbatim (Marlin calls this `string_arg`). This is enabled only for a small, fixed set of commands
(file names, LCD messages). Consequences for the syntax:

* the string is **not** delimited — everything up to the end of line belongs to it,
* it may contain spaces,
* a `;` inside such a string is still treated as a comment start by most parsers, so `;` cannot be
  embedded,
* a `*checksum` still terminates the payload when line numbering is in use.

**(b) Quoted strings.** Delimited by `"` — optional in Marlin (`GCODE_QUOTED_STRINGS`), standard in
RepRapFirmware. Quoting makes strings composable with other parameters on the same line:

```
M23 "long file name.gco"
M587 S"MyNetwork" P"pa ss;word"
```

Escaping is **dialect-specific**:

| Dialect | Embedded `"` | Other escapes |
|---|---|---|
| RepRapFirmware | doubled: `""` | `'x` forces the next character to lower case |
| Marlin | backslash: `\"` | `\\` for a literal backslash (`unescape_string()`) |

A quoted string protects its content from comment and whitespace stripping. Hosts that strip
whitespace **must** be quote-aware.

### 3.5 Expressions and parameters

Not part of the classic base syntax; two families exist, and both are lexically *values* that may
appear where a number is expected.

* **RS274/NGC (LinuxCNC):** `[ ... ]` expressions with `+ - * / **`, `MOD`, comparison operators
  (`EQ NE GT GE LT LE`), logical `AND OR XOR`, and `#`-prefixed parameters — numbered (`#3`), named
  local (`#<name>`), named global (`#<_name>`). Uninitialised parameters read as `0`.
* **RepRapFirmware meta-commands:** `{ ... }` expressions over object-model values and `var`/`set`
  variables, plus the meta-commands `if`/`elif`/`else`/`while`/`echo`/`abort`. Example shape:
  `G1 X{move.axes[0].max - 5}`.

A parser targeting either dialect must at minimum **lex balanced `{}`/`[]` groups as a single opaque
value token** so that expression bodies are not mistaken for words.

---

## 4. Identifiers (field letters)

An identifier is a **single letter**. Together with its value it forms a *word* (RepRap calls it a
*field*). Two letters are structural rather than parametric: `N` ([§7](#7-line-numbering)) and the
pseudo-letter `*` ([§8](#8-checksum-and-crc)).

### 4.1 Command letters

| Letter | Role |
|---|---|
| `G` | Standard (NIST-derived) command — motion, coordinate handling |
| `M` | Miscellaneous / firmware-defined command |
| `T` | Tool select |
| `D` | Debug command (Marlin, development builds only) |

* A command word is `G`/`M`/`T` followed by an **unsigned integer**, optionally followed by a
  **subcode**: `.` plus an unsigned integer (`G29.1`, `M0.1`; Marlin `USE_GCODE_SUBCODES`).
* Leading zeros are insignificant: `G1` ≡ `G01` ≡ `G001`.
* With `GCODE_MOTION_MODES` (Marlin) an axis letter may start a line, inheriting the last motion
  command — `X10 Y10` continues the previous `G0`/`G1`.

### 4.2 Parameter letters

The letters below are the conventional assignments. **Meaning is per-command**; this table is only the
common convention (RepRap wiki):

| Letter | Conventional meaning |
|---|---|
| `X` `Y` `Z` | Axis coordinates (integer or fractional) |
| `U` `V` `W` `A` `B` `C` | Additional / rotational axes |
| `E` | Extrudate length |
| `F` | Feedrate, mm/min |
| `S` | Generic parameter — seconds, temperature, voltage, on/off |
| `P` | Generic parameter — milliseconds, index, PID `P` |
| `I` `J` `K` | Arc offsets (X/Y/Z); PID `I` |
| `D` | Diameter; PID `D` |
| `H` | Heater number; endstop/offset selector |
| `L` | Generic count/parameter |
| `R` | Temperature; radius; restore-point number |
| `Q` | Reserved / unused |
| `N` | **Line number** — structural |
| `*` | **Checksum or CRC** — structural |

### 4.3 Rules

* Only `A`–`Z` are identifiers. No multi-character identifiers exist in the base syntax.
* Each letter **should** appear at most once per line. RS274/NGC forbids repeats within a block; most
  printer firmware silently uses the *first* occurrence (Marlin) — do not rely on either.
* **Word order is not significant** for parameters: `G1 X10 F600` ≡ `G1 F600 X10`. The exceptions are
  structural: `N` must come first and `*` must come last.
* Multiple commands on one line are not portable. Some parsers accept `G90 G0 X0` (splitting at each
  `G`/`M`); Marlin executes only the first command of a line. Generators **should emit one command per
  line**.
* An unrecognised parameter letter is normally ignored by the receiving command; an unrecognised
  *command* produces an error (`echo:Unknown command: …`).

---

## 5. Line (block) structure

The canonical, fully-decorated line is:

```
[N<line>] <cmd-letter><cmd-number>[.<subcode>] [<param>...] [*<checksum>] [; comment]
```

Example, with every optional element present:

```
N42 G1 X10.5 Y-3 E0.42 F1800*9 ; move and extrude
```

(The checksum is 9, over `N42 G1 X10.5 Y-3 E0.42 F1800` per [§8.3](#83-what-the-checksum-covers).
Earlier revisions of this example printed `*118`, which is not the XOR of anything on the line — the
module's own `XorCheckSumTest` has carried the correct value for this exact payload all along.)

Ordering constraints:

1. An optional **block-delete** `/` (RS274/NGC only) is the first non-blank character; when block
   delete is active such lines are skipped.
2. `N<line>` — *"if present, the line number should be the first field in a line."*
3. The **payload**: command word and parameter words, in any order.
4. `*<checksum>` — last field **before** any comment.
5. A **tail comment** to the end of line.

A line may be **empty** or contain only whitespace and/or comments; such a line is a no-op. Empty
lines still count for nothing in line numbering — a line number is only consumed by a line that is
actually transmitted.

---

## 6. Comments

| Form | Syntax | Terminates at |
|---|---|---|
| **Tail comment** | `;` … | end of line |
| **Inline comment** | `(` … `)` | the closing parenthesis |

```
; a whole-line comment
G28 ; home all axes
G28 (home) X Y          ; inline comment between words
```

Rules:

* A tail comment runs to the end of the line and cannot be closed; the rest of the line is comment.
* An inline comment **must open and close on the same line**. An unterminated `(` is a syntax error.
* A `;` inside an inline comment, and a `(` inside a tail comment, are ordinary characters.
* A `;` or `(` inside a **quoted string** is an ordinary character.
* Comments are semantically **inert**, except for "active comments" in RS274/NGC:
  `(MSG,…)`, `(DEBUG,…)`, `(PRINT,…)`, `(LOG,…)`.
* Comments occupy line-length budget and are covered by the checksum if transmitted; hosts usually
  strip them before sending.
* Nesting of `(` … `)` is not portable (RS274/NGC does not allow it; some tokenizers track depth).

---

## 7. Line numbering

### 7.1 Syntax

```
N<unsigned-int>
```

* `N` **should be the first field** of the line.
* RS274/NGC additionally allows a fractional line number: `N` followed by an unsigned integer,
  *"optionally followed by a period and another unsigned integer."*
* Marlin's parser skips `N` followed by any run of `-` and digits, i.e. a sign is tolerated.

### 7.2 Semantics

Line numbers exist for **transport reliability**, not for flow control: there is no `GOTO`, and a line
number never identifies a jump target.

* The number must be **exactly the previous number + 1**. A gap means a line was lost; the firmware
  rejects the line (`Error:Line Number is not Last Line Number+1, Last Line: <n>`) and asks for
  retransmission.
* **A repeat is not an error.** Marlin accepts `last_N` and `last_N - 1` and *silently discards* the
  line — no error, no resend request, and the counter does not move
  (`queue.cpp`: `if (WITHIN(gcode_N, serial.last_N - 1, serial.last_N)) continue;`). The reason is a
  race that the resend protocol itself creates: a host that has already retransmitted when the
  firmware's resend request arrives sends the same line twice, and treating the second copy as a
  fault would request a resend of a line that is already in flight, which does not converge. So the
  rejection rule is *below* the window, not *outside* it: `n < last_N - 1` or `n > last_N + 1`.
* `M110 N<n>` sets the current line-number counter, which is how a host resynchronises or starts a
  session (`M110 N0`).
* **An `M110` line is exempt from the continuity check** — `queue.cpp` guards it with
  `if (gcode_N != serial.last_N + 1 && !M110)`. It has to be: resynchronising is the one thing a host
  does *because* the sequence is already broken, so a counter reset that had to arrive in sequence
  would be useless. Note also that Marlin decides a line is an `M110` by searching the raw text for
  the substring (`strstr_P(command, PSTR("M110"))`), and then takes the **second** `N` on the line as
  the new counter value (`strchr(command + 4, 'N')`), which is why `M110`'s argument has to survive
  parsing as a parameter rather than being skipped as a line number.
* Line numbers are **optional**, and are conventionally omitted for G-code stored in files
  (SD card / internal storage), where there is no lossy link to protect.
* Because a line number changes the byte content of the line, it changes the checksum: the checksum
  must be computed **after** the `N` prefix has been prepended (see [§8.3](#83-what-the-checksum-covers)).

### 7.3 Pairing rule

**A line number and a checksum must both be present or both be absent.** Mixing them is an error:

* `N` without `*` → `Error:No Checksum with line number, Last Line: <n>`
* `*` without `N` → `Error:No Line Number with checksum, Last Line: <n>`

---

## 8. Checksum and CRC

### 8.1 Syntax

```
*<unsigned-int>
```

`*` is the last field before any comment. The value is written in **decimal**, without padding. The
number of digits distinguishes the two algorithms in RepRapFirmware: **1–3 digits = XOR checksum,
5 digits = CRC16**.

### 8.2 XOR checksum (the common algorithm)

The checksum is the bitwise XOR of every byte of the line preceding the `*`, masked to 8 bits. The
canonical RepRap formulation is:

```c
int cs = 0;
for (i = 0; cmd[i] != '*' && cmd[i] != NULL; i++)
   cs = cs ^ cmd[i];
cs &= 0xff;   /* Defensive masking */
```

Properties:

* Result range: `0`–`255`.
* Order-independent (XOR is commutative) — it detects single-byte corruption and most burst errors,
  but **not** transposed bytes and not two bytes corrupted in the same bit positions.
* Written as a plain decimal integer: `N3 T0*57`.

### 8.3 What the checksum covers

The covered range runs **from the `N` of the line-number field, inclusive, up to but not including
the `*`** — the bytes as transmitted, nothing normalised first. Therefore:

* the `N` and the line-number digits **are** included;
* every space that is actually sent **is** included — `N1 G1 X0*x` and `N1 G1X0*y` have different
  checksums;
* anything *before* the `N` — leading spaces or tabs used as indentation — is **not** covered;
* a comment placed *after* the `*` is **not** covered;
* the line terminator is **not** covered;
* consequently, generators must checksum the final byte string, and **must not** add or remove
  whitespace after computing it. Emitting no space before `*` is the convention.

Worked example — the line `N3 T0`:

| byte | `N` | `3` | ` ` | `T` | `0` |
|---|---|---|---|---|---|
| dec | 78 | 51 | 32 | 84 | 48 |

`78 ^ 51 = 125`; `125 ^ 32 = 93`; `93 ^ 84 = 9`; `9 ^ 48 = 57` → `N3 T0*57`.

**Where the range starts is not a detail, and earlier revisions of this section had it wrong.** They
said the checksum ran over "the bytes transmitted before the `*`" without saying where the range
began, which reads as "from the start of the line". Both firmwares that implement this field start at
the `N` instead, and they were read to settle it:

* **Marlin** ([`Marlin/src/gcode/queue.cpp`](https://github.com/MarlinFirmware/Marlin/blob/2.1.x/Marlin/src/gcode/queue.cpp),
  2.1.x) advances past leading spaces (`while (*command == ' ') command++;`) *before* taking the
  pointer it checksums from, requires `N` to be the first character at that point, and XORs
  `command[0 .. apos-1]`.
* **RepRapFirmware** ([`src/GCodes/GCodeBuffer/StringParser.cpp`](https://github.com/Duet3D/RepRapFirmware/blob/3.5-dev/src/GCodes/GCodeBuffer/StringParser.cpp))
  accumulates in `AddToChecksum`, whose body is guarded by `if (hadLineNumber)` and so does nothing
  until the `N` is seen; the `N` case sets that flag, resets the CRC and then adds the `N` itself.

The difference is only visible on an indented line, which is why it survived so long: for a line that
begins at its `N` the two readings agree byte for byte. On `" N1 G28"` they do not — 50 counting the
leading space, 18 without — and 18 is what firmware computes.

Two further consequences fall out of the same two sources:

* **The last `*` wins, not the first.** Marlin uses `strrchr`, so `N1 G28*12*13` is checksummed over
  `N1 G28*12` with a declared value of 13.
* **A line whose first non-space character is not `N` carries no checksum at all** as far as either
  firmware is concerned — neither starts accumulating, so the `*` field is never validated. A parser
  that reports on such a line (`(c)N1 G28*…`) is describing syntax, not predicting firmware.

### 8.4 CRC16 (RepRapFirmware)

RepRapFirmware also accepts a CRC in the same `*` field, emitted as **5 decimal digits**
(zero-padded) and computed over the same byte range as [§8.3](#83-what-the-checksum-covers). It is
strictly stronger than the XOR checksum — it is not commutative, so it catches the transpositions
§8.2 misses — and is preferred where supported.

**The variant is CRC-16/XMODEM.**

| parameter | value |
|---|---|
| polynomial | `0x1021` (CCITT, `x¹⁶ + x¹² + x⁵ + 1`) |
| initial value | `0x0000` |
| bit order | MSB-first; input and output unreflected |
| final XOR | none |
| width of the emitted field | 5 decimal digits, zero-padded |

"CCITT CRC-16 with polynomial 0x1021" names **four** different algorithms, and they agree on no
input. Naming the polynomial alone — as earlier revisions of this section did — is therefore not a
specification: an implementer who picks the most famous variant rejects every genuine line, which is
worse than not checking at all.

| variant | init | bit order | final XOR | `N3 T0` | `N1 M115` | `N1 G28` |
|---|---|---|---|---|---|---|
| **XMODEM** ← this one | `0x0000` | MSB-first | none | **`06939`** | **`30753`** | **`14291`** |
| CCITT-FALSE | `0xFFFF` | MSB-first | none | `02583` | `35311` | `14787` |
| KERMIT | `0x0000` | LSB-first | none | `20362` | `27219` | `55583` |
| X-25 | `0xFFFF` | LSB-first | `0xFFFF` | `33021` | `58915` | `11920` |

**Source.** Read off RepRapFirmware, the only firmware that accepts this field, rather than inferred:
[`src/Storage/CRC16.h`](https://github.com/Duet3D/RepRapFirmware/blob/3.5-dev/src/Storage/CRC16.h)
declares "CRC16 CCIT with initial CRC value Zero";
[`src/Storage/CRC16.cpp`](https://github.com/Duet3D/RepRapFirmware/blob/3.5-dev/src/Storage/CRC16.cpp)
is a table-driven MSB-first update, `crc = (crc << 8) ^ table[((crc >> 8) ^ c) & 0xff]`, whose table
row 1 is `0x1021`, and returns the accumulator unmodified.
[`src/GCodes/GCodeBuffer/StringParser.cpp`](https://github.com/Duet3D/RepRapFirmware/blob/3.5-dev/src/GCodes/GCodeBuffer/StringParser.cpp)
is what wires it to this field: `crc16.Reset(0)` on the `N`, `crc16.Update(c)` per covered character,
and a validation step that switches on the digit count — 1–3 compare against the XOR checksum, 5
against `crc16.Get()`, anything else is rejected outright.

**Worked vector** — the same line as §8.3's XOR example, so the two can be compared directly:

```
N3 T0*06939
```

Note the leading zero. The digit count is what selects the algorithm ([§8.1](#81-syntax)), so this
value written as `6939` is not a CRC with a missing digit — it is a four-digit field, which is
neither algorithm's width and is rejected.

### 8.5 Failure handling and the resend protocol

Checksums are only useful together with line numbers, because retransmission is addressed by line
number. The classic host↔firmware loop is:

1. Host sends `N<k> <payload>*<cs>`.
2. Firmware validates the checksum and the line number. **The two firmwares disagree on the
   order**, which decides which error a line that is both corrupt and out of sequence reports:
   RepRapFirmware checks the checksum first, at buffer-fill time in `StringParser::Put`, before the
   line number is looked at anywhere; Marlin checks the line number first and only then the checksum
   (`queue.cpp`). Either is defensible, since both reject the line and both ask for the same resend.
3. On success it executes/queues the line and replies `ok` (optionally with buffer/position data).
4. On failure it replies with an error naming the last good line and requests a resend:

```
Error:checksum mismatch, Last Line: 41
Resend: 42
ok
```

5. The host rewinds its send window to the requested number and resends from there.

Related framing facts that constrain the syntax:

* Motion commands (`G0`–`G3`, `G28`–`G32`, …) are **buffered**; the `ok` acknowledges *queuing*, not
  completion. Non-buffered commands are acknowledged only once the queue has drained. Hosts therefore
  keep a small window of unacknowledged lines (Marlin's `BUFSIZE`, default 4).
* Because retransmission is line-based, a line must never be split across two writes in a way that
  lets another line interleave.

---

## 9. Error handling

A parser should distinguish these classes:

| Class | Examples |
|---|---|
| **Lexical error** | unterminated `(` comment, unterminated `"` string, unbalanced `{`/`[`, stray character (`?`, `,`, `@`) |
| **Structural error** | `N` not first, `*` not last, `N` without `*` or vice versa, line too long |
| **Framing error** | checksum mismatch, line number not *last + 1* |
| **Semantic error** | unknown command, missing required parameter, value out of range — *outside this document's scope* |

Recommended behaviour:

* Framing errors → reject the whole line and request retransmission; do **not** execute it partially.
* Lexical/structural errors on a checksummed line are also framing errors from the host's point of
  view (the line as received is not usable).
* A parser used for tooling (linting, visualisation, round-tripping) should keep unknown tokens and
  original spelling rather than normalise, so that a file can be re-emitted byte-identically.

---

## 10. Dialect matrix

| Feature | RS274/NGC (LinuxCNC) | Marlin | RepRapFirmware |
|---|---|---|---|
| Case-insensitive | Yes | Only with `GCODE_CASE_INSENSITIVE`; uppercase required by `FASTER_GCODE_PARSER` | Yes since 1.19 (not inside strings) |
| Whitespace anywhere | Yes | Between words; not inside numbers | Between words |
| `;` comment | Yes | Yes | Yes |
| `( )` comment | Yes | Yes | Yes |
| Line number `N` | Yes, fractional allowed | Yes, sign tolerated | Yes |
| Checksum `*` | No | XOR, 1–3 digits | XOR (1–3 digits) or CRC16 (5 digits) |
| `N`/`*` must pair | n/a | Yes | Yes |
| Subcodes `G29.1` | n/a | With `USE_GCODE_SUBCODES` | Yes |
| Quoted strings | n/a | With `GCODE_QUOTED_STRINGS`, `\` escapes | Yes, `""` escape, `'` lower-casing |
| Value-less flags (`G28 X`) | No (words need values) | Yes | Yes |
| Expressions | `[ … ]`, `#params` | No | `{ … }`, object model, `var`/`set` |
| Block delete `/` | Yes | No | No |
| Multiple commands per line | Words combine in one block | First command only | First command only |
| Max line length | 256 | 96 (`MAX_CMD_SIZE`) | 256 |

---

## Appendix A — Reference checksum implementations

**C (canonical RepRap form)**

```c
uint8_t gcode_checksum(const char *line) {
    int cs = 0;
    for (int i = 0; line[i] != '*' && line[i] != '\0'; i++)
        cs ^= line[i];
    return cs & 0xff;
}
```

**Kotlin (streaming, as used by `:gcode`)**

```kotlin
interface CheckSumCalculator {
    fun add(ch: Char)
    fun get(): GInt
}

class XorCheckSum : CheckSumCalculator {
    private var sum = 0
    override fun add(ch: Char) { sum = sum xor ch.code }
    override fun get(): GInt = GInt(sum and 0xff)
}
```

**Python (framing a line for transmission)**

```python
def frame(payload: str, n: int) -> str:
    line = f"N{n} {payload}"
    cs = 0
    for b in line.encode("ascii"):
        cs ^= b
    return f"{line}*{cs & 0xff}"

frame("T0", 3)          # 'N3 T0*57'
frame("G1 X10 F600", 1)  # checksum computed over the exact bytes above
```

---

## Appendix B — Mapping to the `:gcode` module

This repository implements the syntax above in `gcode/src/main/kotlin/org/qw3rtrun/p3d/g/code/core/`.
The mapping is given here so that the spec and the code can be kept in step.

That package is written as a **portable core**: a deliberately small language surface (hand-written
iterators, integer and bitwise math, records, arrays, explicit state machines, no third-party
dependencies) so that it can be re-implemented in JS/TS, C or Rust as a transliteration rather than a
rewrite. Consequences visible in the types below: characters are classified by explicit comparison
rather than by Unicode-category predicates, malformed input yields a token that carries the offending
bytes instead of an exception, and every token exposes `rawText()` so a parse can be diffed against a
raw capture. The rules are recorded in the `low-level-protocol-dev` skill
(`.agents/skills/low-level-protocol-dev/SKILL.md`) and the outstanding gaps in
[the `:gcode` issue queue](https://github.com/qw3rtrun/p3d-web-ui/issues/18).

### B.1 Tokens — `token/GTokens.kt`

| Spec token ([§2](#2-lexical-structure-tokens)) | Type |
|---|---|
| Letter identifier | `GLetter(letter: Char)` : `GIdentifier` |
| Checksum marker `*` | `GChecksum` (object, `name == "*"`) : `GIdentifier` |
| Integer | `GInt(int: Int, lexeme: String = int.toString())` : `GNumber` |
| Decimal | `GFloat(value: BigDecimal, lexeme: String = value.toString())` : `GNumber` |
| Quoted string | `GQuotedString(string: String)` : `GString` — `rawText()` re-doubles `"` |
| Bare rest-of-line string ([§3.4](#34-string-values)a) | `GUnquotedString(string: String)` : `GString` — `rawText()` is the string itself, undelimited. **Never produced by the lexer**: which commands take one depends on the command number, so a decoder builds it |
| Expression `{ … }` | `GRawExpression` : `GExpression` |
| Tail comment `;` | `GTailComment(string, key = ";")` : `GComment` |
| Inline comment `( )` | `GInlineComment(string, "(", ")")` : `GComment` |
| Whitespace | `GSpace`, `GTab` : `GWhitespace` : `GSeparator` |
| End of line | `GLineBreak(breaker)` : `GSeparator` |
| Invalid token | `GUnknown(str)` |

Every token exposes `rawText()`, so a token stream round-trips to the original text.

Notable choices: **decimals are `BigDecimal`**, so no precision is lost at parse time
([§3.1](#31-numeric-values) precision limits apply only when the value reaches firmware); the
quoted-string escape is the **RepRapFirmware doubling** rule; and a **number carries the lexeme it
was read from** alongside its value, so `+5`, `01`, `.5` and `1.` re-print as written. The lexeme is
part of token identity — `GInt(1) != GInt(1, "01")` — and value comparison goes through `int` /
`value.compareTo`. There is no `Double` entry point into `GFloat`: a `Double` cannot hold most
authored decimals and gives the caller no way to state the lexeme the value should render as, so a
caller holding one passes a `String` or rounds explicitly.

**`BigDecimal` is this core's one admitted portability liability.** Arbitrary-precision decimal
exists in none of the port targets (JS/TS, C, Rust) without a library, so a port cannot transliterate
this type and must choose a replacement — a scaled integer pair (`mantissa × 10⁻ˢᶜᵃˡᵉ`) is the
equivalent that preserves the scale-sensitive identity above; a binary float is not. The cost is
bounded because **nothing in the module reads the parsed value**: `rawText()` returns the lexeme, so
round-tripping never touches `BigDecimal`, and a port that carries only the lexeme still lexes and
re-emits correctly. It is now the module's **only** such liability: the second one,
`GDDecimalField.default` in `GDescription.kt`, went with the unused descriptor that held it.
Recorded rather than fixed, per
[issue #4](https://github.com/qw3rtrun/p3d-web-ui/issues/4).

### B.2 Tokenizer — `token/GTokenizer.kt`

`GTokenizer.parse(…)` yields a lazy `Sequence<GToken>`/`Iterator<GToken>` over a character stream,
dispatching on the first character exactly as in the [§2](#2-lexical-structure-tokens) table:
space/tab/LF/CR → `GSpace`/`GTab`/`GLineBreak` (with `\r\n` lookahead, lone `\r` → `GUnknown`),
`A`–`Z`/`a`–`z` → `GLetter`, `0`–`9`, `.`, `+` or `-` → number, `"` → string, `{` → balanced
expression, `;` → tail comment, `(` → balanced inline comment, `*` → `GChecksum`, otherwise
`GUnknown`. The character classes are private one-line functions over explicit ASCII ranges;
there is no `Char.isLetter()`/`isDigit()`/`isWhitespace()` in the module, and no
`java.util.stream`/`kotlin.streams` either — the `Stream<Char>` and `Iterable<Char>` overloads are
both gone, nothing having called either, and the `Iterator`/`Sequence`/`CharSequence` ones cover
every caller.

`GTokenizer` is an **object**: it holds no state, so an instance per call site bought nothing. It
also carries `lines(text)`, the whole read pipeline under one name — text in, classified `GLine`s
out, the tokenizer and the liner wired together — because that is the module's primary operation and
every caller used to assemble it by hand. `GLiner` remains public for a caller that already holds
tokens, or that wants to drive the lines as an `Iterator`. The state machine itself,
`GTokenizerIterator`, is `internal` and its six scanners are private: which characters `number()`
consumes is how this lexer is built, not what it promises.

Deviations from this spec, as currently written (all verified by running the module):

* a lone `.`, a bare sign, a number with two decimal points (`1.2.3`) and an integer too large for
  `Int` all degrade to `GUnknown` carrying the original lexeme, rather than to a typed lexical error
  ([§9](#9-error-handling));
* an unterminated `(`, `"` or `{` degrades to `GUnknown` carrying the exact lexeme (`M(abc` →
  `[GLetter(M), GUnknown("(abc")]`) rather than to a typed lexical error ([§9](#9-error-handling));
* a subcode ([§4.1](#41-command-letters)) is lexed as a decimal: `G29.1` → `GLetter(G), GFloat(29.1)`;
* bare rest-of-line strings ([§3.4](#34-string-values)) are not recognised — `M117 Hello World`
  becomes one `GLetter` per character. This is a layering fact, not an open gap: the characters are
  reassembled one layer up, by a decoder that knows the command number (see [B.3](#b3-line-model--tokenglineskt-tokenglinerkt-tokengcommandskt-tokengfieldskt)).

Conforming as of the number-lexeme pass: the optional sign of [§3.1](#31-numeric-values) is part of
the number token (`G1 E-5` → `… GLetter(E), GInt(-5)`), a tab is `GTab`
([§2.1](#21-whitespace)), and `rawText()` round-trips **every** construct the lexer accepts,
non-canonical numbers included — `.5`, `01` and `1.` come back byte-identical. A comment keeps its
delimiters out of its text and only in its lexeme, so `G1 (feedrate) F1500` re-prints byte-identically,
and `parseLines(Sequence<String>)` re-inserts the caller-chosen terminator between lines, so
`["G28", "M104 S200"]` → `G28\nM104 S200`.

Both line terminators of [§1.2](#12-end-of-line) are handled: `\r\n` is one `GLineBreak("\r\n")`, and
a lone `\r` degrades to `GUnknown` without consuming the following character. A `;` comment ends at
either terminator character, so on CRLF input the CR is emitted by the `GLineBreak` and not kept in
`GTailComment.string`; `;ab\r\n` → `GTailComment("ab")` + `GLineBreak("\r\n")`, still byte-identical
on re-print.

[§1.1](#11-character-set-and-encoding) is enforced rather than assumed: a non-ASCII character outside
a comment or a quoted string is a [§9](#9-error-handling) lexical error carrying the character, so
`GЯ1` → `[GLetter(G), GUnknown(Я), GInt(1)]` and `X١` → `[GLetter(X), GUnknown(١)]`. Comments and
quoted strings are unaffected — `marlin.gcode` keeps the `’` and `µ` in its comment text. Note that
`String.toIntOrNull()` and `BigDecimal(String)` accept the whole Unicode `Nd` category, so it is the
digit class alone that keeps `X١` from lexing as `GInt(1, "١")` and `X١.٢` from lexing as
`GFloat(1.2)`.

### B.3 Line model — `token/GLines.kt`, `token/GLiner.kt`, `token/GCommands.kt`, `token/GFields.kt`

The module reads a line in two passes over two vocabularies. **A line is tokens**: its shape is
decided from token positions alone, and nothing above the lexer is built to answer it. **A command is
words**: fields are assembled only when a caller asks what the line commands.

| Spec concept | Type |
|---|---|
| Any line/block ([§5](#5-line-block-structure)) | `GLine { val raw: List<GToken>; val body: List<GToken> }` |
| Line of only whitespace and/or comments ([§5](#5-line-block-structure)) | `GMeaninglessLine` |
| Unnumbered line | `GSimpleLine` |
| `N…*…` framed line ([§7](#7-line-numbering), [§8](#8-checksum-and-crc)) | `GPacketLine(number, checksum, body, raw)` — also `GOrdered`, `GCheckSumControlled` |
| Line number ([§7.1](#71-syntax)), checksum field ([§8.1](#81-syntax)) | `GInt` — the integer behind the `N`, and behind the last `*` |
| A field ([§3](#3-value-types)) | `GWord`: a `GParameterWord` (identifier + value), a `GFlagWord` (identifier alone, [§3.2](#32-flag-value-less-parameters)), or a `GUnnamedStr` (value alone — [§3.4](#34-string-values)a's bare string, whose `id` is the empty `GEmptyId` so that every word has one) |
| One command + its parameters ([§4](#4-identifiers-field-letters)) | `GCommand(head: GParameterWord<GNumber>, params: List<GWord>)` |
| Structural error ([§9](#9-error-handling)) | `GError`: `GMissingChecksum`, `GMissingLineNumber`, `GMalformedLineNumber`, `GMalformedChecksum`, `GCheckSumFailedLine` |

`raw` is every token of the line in wire order, terminator included, and it is the stored value of
every line type — so "a line reproduces its input" holds by construction rather than by an override.
`body` is the range a command may be read from: `raw` for every type except `GPacketLine`, where it
is a **slice of `raw`** running from past the line number to the marker. The two therefore cannot
disagree.

`GLiner` is a single `Iterator<GLine>`: it splits a token stream at `GLineBreak` and classifies each
line from two positions found in one pass — the **first identifier**, which is the line's first field
([§5](#5-line-block-structure): whitespace ([§2.1](#21-whitespace)) and comments
([§6](#6-comments)) are not fields, so it is not necessarily token 0), and the **last `GChecksum`**,
since [§5](#5-line-block-structure) puts the checksum field last. A `*` the lexer put inside a
comment or a string is part of that token and is never an identifier, so it cannot be mistaken for
the marker. Both fields assemble across whitespace ([§2.1](#21-whitespace)) by skipping to the next
non-separator token, and [§8.3](#83-what-the-checksum-covers)'s covered range is then literally the
tokens from the `N` up to the `*`: indentation before the `N` is outside it, a space before the
marker is inside it, and the marker, its value and the terminator are outside it.

The [§7.3](#73-pairing-rule) pairing rule is decided on the *presence* of the two markers and is
decided **before** the [§7.1](#71-syntax) / [§8.1](#81-syntax) field-syntax rules, so it is reported
as typed [§9](#9-error-handling) variants rather than by falling back to `GSimpleLine`:

| Input | Result |
|---|---|
| `N1 G28*18`, `n1 g28*18`, ` N1 G28*18`, `\tN1 G28*18`, `(c)N1 G28*18` | `GPacketLine` — the match is case-insensitive ([§2.2](#22-case)) and position-tolerant |
| `N 1 G28 * 18` | both fields assemble across whitespace ([§2.1](#21-whitespace)) — though the extra bytes are covered, so the value differs |
| `N1 G28*12*59` | `GPacketLine(number = GInt(1), checksum = GInt(59))` — the **last** `*` is the field ([§5](#5-line-block-structure)) and `*12` is inside the bytes the checksum covers ([§8.3](#83-what-the-checksum-covers)). Marlin agrees: `get_serial_commands` uses `strrchr(command, '*')` |
| `N1 G28*18 G1 X5` | `GPacketLine` whose body is `G28` alone — what follows the marker is outside the frame, as in Marlin, which writes a `\0` over the `*` |
| `N1 G28*19` | `GCheckSumFailedLine(number, expected, received)` ([§8.5](#85-failure-handling-and-the-resend-protocol)) — well-framed, well-formed, wrong value |
| `N1 G28` | `GMissingChecksum(number = GInt(1))` |
| `G28*18`, `*12`, `* 12`, `*`, `*ABC` | `GMissingLineNumber` — the marker is unpaired wherever it sits and whether or not it carries a value |
| `N*`, `NX*12` | `GMalformedLineNumber` |
| `N1 G28*`, `N1*`, `N1 G28*X`, `N1 G28*10.5`, `N1 G28*1234` | `GMalformedChecksum(number = GInt(1))` — the marker is present but its value is not an integer of a width an algorithm claims ([§8.1](#81-syntax)); a host tells this from `GMissingChecksum` to decide a resend ([§8.5](#85-failure-handling-and-the-resend-protocol)) |

A `GPacketLine` is **only ever built for a line whose checksum verified**, so holding one means the
line is intact and there is no `verify()` to forget. Nothing is dropped by index arithmetic: the
trailing line break is a token like any other, so an unterminated line keeps its last token and a
short line does not throw — `N*` yields `GMalformedLineNumber`, not `IllegalArgumentException`
(TODO 1.3).

**Reading a line into commands is a decoder's job, not a second pass of this layer's.** `GFields.kt`
carries what the layer still owes a reader, as functions over tokens rather than a parser object:
`valueIndex()` is [§2.1](#21-whitespace)'s pairing rule — an identifier and the value behind it,
whitespace absorbed — in the module's only copy of it, and `headWord()` / `headEnd()` / `headKey()`
read the one field that is a command ([§2.3](#23-grammar-ebnf) makes `command-word` a specialisation
of `word`, so it is the same rule). A command number is accepted only as
`<unsigned-int>[.<unsigned-int>]` ([§4.1](#41-command-letters)) by `isCommandNumber()`, which the DSL
shares, so `G29.1` is one command word carrying `GFloat("29.1")` — the subcode stays on the number,
which is what re-emits `29.1` rather than `29` and `.1`. `isCommandLetter()` accepts `G`, `M` and a
line-initial `T`.

A **command-agnostic** decomposition — every field of a line into a `GWord`, the words split at each
`G`/`M` word ([§4.3](#43-rules)) and at a `T` word only while no command has started, with `N`
([§7](#7-line-numbering)) and `*` ([§8](#8-checksum-and-crc)) skipped as structural — used to be a
`GCommandParser` class in this layer. It has no production caller: a host that wants to know what a
line commands asks `MarlinCommands.decode`, which knows the command number and can therefore read
what words cannot ([§3.4](#34-string-values)a). It survives as `GWordReader` in the test sources,
because three corpus contracts measure the module against **arbitrary** lines — including commands
no class models — and a Marlin-only decoder cannot express that question.

Verified by running: the whole corpus is one command per line except `G53 G0 X0 Y0 Z0` and
`G53 G1 X20` (two each — `G53` is a modal prefix) and `M815 G0 X0 Y0|G0 Z10|M300 S440 P50`, which
that reader reports as **four** because `M815`'s argument is a bare rest-of-line string and a
command-agnostic reader cannot know it. That is not fixable there and is not a defect of it: the
same line through `MarlinCommands.decode` is one `GCodeMacrosM815` whose `gcode` property is the
whole macro. `GCorpusDecompositionTest` freezes that reading:
every line of both corpora, plus a hand-built edge-case set covering the table above, as its line
kind, its framing fields and its canonically encoded commands.

`isCommandLetter()` treats `G`, `M` and (line-initially) `T` as command letters, which is the
[§4.1](#41-command-letters) set minus Marlin's development-only `D`. A line whose tokens hold
no identifier is a `GMeaninglessLine` whatever those tokens are - `?` and `42 99` as much as a blank
line - because the first *identifier* is what the shape is read from and such a line has no field at
any position. [§9](#9-error-handling) would call the first two an error; a `GNotIdentifierError` was
declared for them and never produced, and has been deleted rather than left as a type no producer
fills and no consumer reads.

**A valued parameter sent bare does not round-trip.** `M104 F` is legal — the letter is present and
carries nothing — and the word model can say so (`GFlagWord`), but the decoder accessors collapse
"absent" and "present without a value" to `null`, because a `BigDecimal?` property has no third
state. So `M104 F` decodes with `factor = null` and re-encodes as `M104`: the only place in the
module where bytes that lexed correctly are lost. `hasWord()` is the only accessor that sees the
difference, which is why a parameter modelled as a flag is unaffected. Fixing it means a second
property per valued parameter across 831 of them, and Marlin's own reading is that a seen-but-
valueless letter is the default (`value_float()` returns 0), so this is recorded rather than fixed.
`MarlinDocExamplesTest` pins the affected doc examples line by line, and `stringArgStart()` depends
on this: a bare own-letter has to *end* the lettered run precisely because it cannot be represented.

Not yet covered by *this* layer: line-length limits, and [§3.4](#34-string-values)'s bare
rest-of-line strings. The latter is a layering fact rather than a gap: which commands take one
depends on the command number, and nothing below a decoder knows it, so the reading is done one
layer up - `GRqDecoder` is handed the tokens as lexed, and `MarlinWords.stringArg()` reassembles
the string for the 22 Marlin commands that take one. **Where such a string starts has no universal
rule**: it begins at the first field that is not one of *that command's* parameters, so `M117 H1
ello World` is all message while `M118 P1 ello World` is a `P1` and a message. Each decoder passes
its own letters, and reads its lettered parameters from `beforeStringArg()` - the region in front -
so that a `P1` inside a message is text rather than a parameter. The DSL writes one with
`bareString(text)`, which the encoder emits undelimited and last.

Head matching on that layer is [§2.2](#22-case)-tolerant in the reading direction: `GRqDecoder` and
`MarlinCommands.decode` compare through `headKey()`, which folds the command letter
to upper case, so `m104 s200` and `M104 S200` decode to the same command. The **number** keeps its
lexeme, so `M0105` still does not resolve, and the writing direction is unchanged - the DSL emits
uppercase, which is what [§2.2](#22-case) asks generators to do.

---

## References

1. RepRap wiki — *G-code*: <https://reprap.org/wiki/G-code> (fields table, `N`/`*` special fields,
   XOR checksum, CRC16, comments, case sensitivity, quoted strings, buffering)
2. Marlin Firmware — *G-code index / parser*: <https://marlinfw.org/meta/gcode/> and
   `Marlin/src/gcode/parser.cpp`, `parser.h`:
   <https://github.com/MarlinFirmware/Marlin/blob/2.1.x/Marlin/src/gcode/parser.cpp>
3. Marlin Firmware — *GCode quoted string support* (PR #16818):
   <https://github.com/MarlinFirmware/Marlin/pull/16818/files>
4. Marlin Firmware — *Code structure*: <https://marlinfw.org/docs/development/code_structure.html>
5. LinuxCNC — *G-code Overview* (RS274/NGC block syntax, numbers, parameters, expressions, comments,
   256-char limit, case insensitivity): <https://linuxcnc.org/docs/html/gcode/overview.html>
6. Duet3D — *G-code meta commands* (RepRapFirmware `{}` expressions, variables, conditional G-code):
   <https://docs.duet3d.com/User_manual/Reference/Gcode_meta_commands>
7. Kramer, T. R., Proctor, F. M., Messina, E. — *The NIST RS274/NGC Interpreter — Version 3*,
   NISTIR 6556, 2000.
</content>
